#Requires -Version 7.4
[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('Start', 'Stop', 'Status')]
    [string]$Action,

    [ValidateNotNullOrEmpty()]
    [string]$JdkHome,

    [switch]$Rebuild,

    [securestring]$DatabasePassword,

    [ValidateRange(5, 600)]
    [int]$ReadyTimeoutSeconds = 90
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false

$root = Split-Path -Parent $PSScriptRoot
$runtime = Join-Path $root 'logs/kaipay-demo'
$statePath = Join-Path $runtime 'state.json'
$targetPath = Join-Path $runtime 'target.json'
$protectedPath = Join-Path $runtime 'protected-identities.txt'
$composePath = Join-Path $root 'docker-compose.yml'
$kafkaOverlay = Join-Path $root 'scripts/kaipay-demo-kafka.compose.yml'
$postgresOverlay = Join-Path $root 'scripts/kaipay-demo-postgres-guard.compose.yml'
$postgresGuard = Join-Path $root 'scripts/kaipay-demo-postgres-guard.sh'
$composeOverride = Join-Path $runtime 'dependencies.compose.yml'
$backend = Join-Path $root 'backend'
$frontend = Join-Path $root 'frontend'
$jar = 'target/KaiPay-0.0.1-SNAPSHOT.jar'
$vite = 'node_modules/vite/bin/vite.js'
# Docker names and loopback ports are global on the engine/host. An optional
# checkout-local instance file selects a separate set, e.g. for an isolated
# rehearsal beside the default installation. Without it the defaults apply.
$instancePath = Join-Path $runtime 'instance.json'
$instanceOverlay = Join-Path $runtime 'instance.compose.yml'
function Read-Instance {
    $default = [ordered]@{ name = 'kaipay'; postgresPort = 25432; kafkaPort = 29092; backendPort = 28080; frontendPort = 28081 }
    if (!(Test-Path -LiteralPath $instancePath)) { return $default }
    try { $configured = Get-Content -LiteralPath $instancePath -Raw | ConvertFrom-Json -AsHashtable -NoEnumerate }
    catch { throw "Invalid instance file $instancePath." }
    if ($configured -isnot [Collections.IDictionary] -or $configured.Count -ne $default.Count -or
        !$configured.ContainsKey('name') -or $configured.name -isnot [string] -or $configured.name -cnotmatch '\A[a-z][a-z0-9]{2,30}\z' -or
        $configured.name -ceq $default.name) { throw "Instance file $instancePath must name a distinct lowercase instance." }
    $selected = [ordered]@{ name = $configured.name }
    # The default installation's ports, including its Kafka UI on 28048, stay reserved.
    $defaultPorts = @($default.postgresPort, $default.kafkaPort, $default.backendPort, $default.frontendPort, 28048)
    foreach ($key in @('postgresPort', 'kafkaPort', 'backendPort', 'frontendPort')) {
        if (!$configured.ContainsKey($key) -or $configured[$key] -isnot [long] -or $configured[$key] -lt 1024 -or
            $configured[$key] -gt 65535 -or $configured[$key] -in $defaultPorts) { throw "Instance file $instancePath has an invalid or default $key." }
        $selected[$key] = [int]$configured[$key]
    }
    if (@($selected.postgresPort, $selected.kafkaPort, $selected.backendPort, $selected.frontendPort | Sort-Object -Unique).Count -ne 4) {
        throw "Instance file $instancePath ports must be distinct."
    }
    return $selected
}
$instance = Read-Instance
$isolatedInstance = $instance.name -cne 'kaipay'
$apiUrl = "http://127.0.0.1:$($instance.backendPort)"
$webUrl = "http://127.0.0.1:$($instance.frontendPort)"
$dependencies = [ordered]@{
    postgres = @{ Port = $instance.postgresPort; ContainerPort = '5432/tcp' }
    kafka = @{ Port = $instance.kafkaPort; ContainerPort = '9094/tcp' }
}
$apps = [ordered]@{ backend = $instance.backendPort; frontend = $instance.frontendPort }
$contextArgs = @()
$engine = $null
$composeTargetVolume = $null
$composeKafkaClusterId = $null
$composePostgresSystemId = $null

function Invoke-Native([string]$Executable, [string[]]$Arguments, [int]$Timeout = 10) {
    $startInfo = [Diagnostics.ProcessStartInfo]::new($Executable)
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.RedirectStandardInput = $true
    foreach ($argument in $Arguments) { $startInfo.ArgumentList.Add($argument) }
    $child = [Diagnostics.Process]::Start($startInfo)
    try {
        $child.StandardInput.Close()
        $stdout = $child.StandardOutput.ReadToEndAsync()
        $stderr = $child.StandardError.ReadToEndAsync()
        if (!$child.WaitForExit($Timeout * 1000)) {
            try { $child.Kill($true) } catch [InvalidOperationException] { if (!$child.HasExited) { throw } }
            throw "$(Split-Path -Leaf $Executable) $($Arguments[0]) timed out after ${Timeout}s."
        }
        if ($child.ExitCode -ne 0) {
            throw "$(Split-Path -Leaf $Executable) $($Arguments[0]) exited $($child.ExitCode). $($stderr.Result.Trim())"
        }
        return $stdout.Result.Trim()
    } finally { $child.Dispose() }
}

function Invoke-Docker([string[]]$Arguments, [int]$Timeout = 10) {
    Invoke-Native $docker ($script:contextArgs + $Arguments) $Timeout
}

function Connect-Docker {
    $script:docker = (Get-Command docker.exe -CommandType Application | Select-Object -First 1).Source
    $context = Invoke-Native $docker @('context', 'show')
    $endpoint = Invoke-Native $docker @('context', 'inspect', $context, '--format', '{{.Endpoints.docker.Host}}')
    if ($endpoint -notmatch '^npipe:/{4}\./pipe/(dockerDesktopLinuxEngine|docker_engine)$') {
        throw "Docker context '$context' must target the local Windows Docker Engine. Select the local Linux-container context."
    }
    $script:contextArgs = @('--context', $context)
    $null = Invoke-Docker @('compose', 'version')
    try {
        $info = Invoke-Docker @('info', '--format', '{{.OSType}} {{.ID}}')
    } catch { throw "Docker is unavailable in context '$context'. Start Docker Desktop in Linux container mode. $($_.Exception.Message)" }
    if ($info -notmatch '^linux (\S+)$') { throw 'Docker must be running in Linux container mode.' }
    $script:engine = "$endpoint $($Matches[1])"
}

function Invoke-Compose([string[]]$Arguments, [int]$Timeout = 10, [switch]$ProtectExisting) {
    if (!$script:composeTargetVolume -or !$script:composeKafkaClusterId -or !$script:composePostgresSystemId -or
        !(Test-Path -LiteralPath $kafkaOverlay -PathType Leaf) -or !(Test-Path -LiteralPath $postgresOverlay -PathType Leaf) -or
        !(Test-Path -LiteralPath $postgresGuard -PathType Leaf)) { throw 'Verified dependency overlays and target identities are required.' }
    $files = @('--file', $composePath, '--file', $kafkaOverlay, '--file', $postgresOverlay)
    if ($isolatedInstance) {
        Write-InstanceOverlay
        $files += @('--file', $instanceOverlay)
    }
    if ($ProtectExisting) {
        # Compose may reconcile even a pre-existing default network during create --no-recreate.
        $overlay = @()
        if (Test-ComposeResource 'network' "$($instance.name)_default" 'default') {
            $overlay += @('networks:', '  default:', '    external: true', "    name: $($instance.name)_default")
        }
        if (Test-ComposeResource 'volume' "$($instance.name)_postgres_data" 'postgres_data') {
            $overlay += @('volumes:', '  postgres_data:', '    external: true', "    name: $($instance.name)_postgres_data")
        }
        if ($overlay.Count) {
            [IO.File]::WriteAllLines($composeOverride, [string[]]$overlay)
            $files += @('--file', $composeOverride)
        }
    }
    $previous = $env:COMPOSE_REMOVE_ORPHANS
    $previousVolume = $env:KAIPAY_DEMO_KAFKA_VOLUME
    $previousClusterId = $env:KAIPAY_DEMO_KAFKA_CLUSTER_ID
    $previousPgId = $env:KAIPAY_EXPECTED_PG_SYSTEM_ID
    $previousPgGuard = $env:KAIPAY_DEMO_PG_GUARD_PATH
    try {
        $env:COMPOSE_REMOVE_ORPHANS = 'false'
        $env:KAIPAY_DEMO_KAFKA_VOLUME = $script:composeTargetVolume
        $env:KAIPAY_DEMO_KAFKA_CLUSTER_ID = $script:composeKafkaClusterId
        $env:KAIPAY_EXPECTED_PG_SYSTEM_ID = $script:composePostgresSystemId
        $env:KAIPAY_DEMO_PG_GUARD_PATH = $postgresGuard
        Invoke-Docker (@('compose', '--project-name', $instance.name, '--project-directory', $root) + $files + $Arguments) $Timeout
    } finally { $env:COMPOSE_REMOVE_ORPHANS = $previous; $env:KAIPAY_DEMO_KAFKA_VOLUME = $previousVolume; $env:KAIPAY_DEMO_KAFKA_CLUSTER_ID = $previousClusterId; $env:KAIPAY_EXPECTED_PG_SYSTEM_ID = $previousPgId; $env:KAIPAY_DEMO_PG_GUARD_PATH = $previousPgGuard }
}

function Write-InstanceOverlay {
    # Generated only for an isolated instance; the default installation uses no overlay.
    $content = @(
        'services:'
        '  postgres:'
        "    container_name: $($instance.name)-postgres"
        '    ports: !override'
        "      - `"127.0.0.1:$($instance.postgresPort):5432`""
        '  kafka:'
        "    container_name: $($instance.name)-kafka"
        '    ports: !override'
        "      - `"127.0.0.1:$($instance.kafkaPort):9094`""
        '    environment:'
        "      KAFKA_ADVERTISED_LISTENERS: INTERNAL://kafka:9092,EXTERNAL://localhost:$($instance.kafkaPort)"
        '  kafka-ui:'
        "    container_name: $($instance.name)-kafka-ui"
        '    ports: !reset []'
        ''
    ) -join "`n"
    if (!(Test-Path -LiteralPath $instanceOverlay -PathType Leaf) -or [IO.File]::ReadAllText($instanceOverlay) -cne $content) {
        [IO.File]::WriteAllText($instanceOverlay, $content)
    }
}

function Save-State {
    $temporary = "$statePath.tmp"
    [IO.File]::WriteAllText($temporary, (ConvertTo-Json -InputObject $state -Depth 5))
    [IO.File]::Move($temporary, $statePath, $true)
}

function Get-OwnedProcess([string]$Name) {
    $record = $state.apps[$Name]
    if (!$record) { return }
    try { $process = [Diagnostics.Process]::GetProcessById([int]$record.pid) }
    catch [ArgumentException] { return }
    try {
        # Open and retain the handle so Kill targets this process even if the PID is reused.
        $null = $process.Handle
        if (!$process.HasExited -and "$($process.StartTime.ToUniversalTime().Ticks)" -eq $record.started -and
            $process.MainModule.FileName -eq $record.path) { return $process }
    } catch {
        if (!$process.HasExited) { $process.Dispose(); throw }
    }
    $process.Dispose()
}

function Test-PortListening([int]$Port) {
    $client = [Net.Sockets.TcpClient]::new()
    $deadline = [Threading.CancellationTokenSource]::new(500)
    try {
        $client.ConnectAsync([Net.IPAddress]::Loopback, $Port, $deadline.Token).AsTask().GetAwaiter().GetResult()
        return $true
    } catch { return $false }
    finally { $client.Dispose(); $deadline.Dispose() }
}

function Assert-PortAvailable([string]$Name, [int]$Port) {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
    try {
        $listener.ExclusiveAddressUse = $true
        $listener.Start()
    } catch {
        throw "$Name cannot bind 127.0.0.1:$Port. Use 'netstat -ano -p TCP' to identify an existing listener, or check Windows port reservations. $($_.Exception.Message)"
    } finally { $listener.Stop() }
}

function Get-Container([string]$Name, [string]$Id = '') {
    $nameFilter = "name=^/$($instance.name)-$Name`$"
    $arguments = @('ps', '-aq', '--no-trunc')
    if ($Id) { $arguments += @('--filter', "id=$Id") } else { $arguments += @('--filter', $nameFilter) }
    $found = Invoke-Docker $arguments
    if (!$found) { return }
    if ($found -cnotmatch '^[a-f0-9]{64}$') { throw "Docker returned an unexpected container ID for $Name." }
    # Inspect only metadata used here. Full inspect includes container environment variables.
    $format = '{"name":{{json .Name}},"state":{{json .State.Status}},"started":{{json (printf "at:%s" .State.StartedAt)}},"health":{{if .State.Health}}{{json .State.Health.Status}}{{else}}null{{end}},"labels":{{json .Config.Labels}},"ports":{{json .HostConfig.PortBindings}},"image":{{json .Image}},"mounts":{{json .Mounts}}}'
    $container = Invoke-Docker @('inspect', '--type', 'container', '--format', $format, $found) | ConvertFrom-Json -AsHashtable
    $container.id = $found
    return $container
}

function Read-Target {
    if (!(Test-Path -LiteralPath $targetPath -PathType Leaf)) { throw "Missing $targetPath. Prepare an independently verified target after the approved cutover." }
    $target = Get-Content -LiteralPath $targetPath -Raw | ConvertFrom-Json -AsHashtable
    $rules = @{
        engine = '^npipe:/{4}\./pipe/(dockerDesktopLinuxEngine|docker_engine) \S+$'
        postgresSystemId = '^\d{10,25}$'; postgresDatabaseOid = '^\d{1,10}$'
        postgresVolume = '^[a-zA-Z0-9][a-zA-Z0-9_.-]{1,127}$'
        postgresImage = '^sha256:[a-f0-9]{64}$'; kafkaImage = '^sha256:[a-f0-9]{64}$'
        kafkaVolume = '^[a-zA-Z0-9][a-zA-Z0-9_.-]{1,127}$'
        kafkaClusterId = '^[a-zA-Z0-9_-]{20,24}$'
        generation = '^[a-z0-9][a-z0-9-]{2,47}$'
    }
    if ($target -isnot [Collections.IDictionary] -or $target.Count -ne $rules.Count) { throw 'Invalid target manifest fields.' }
    foreach ($key in $rules.Keys) {
        if (!$target.ContainsKey($key) -or $target[$key] -isnot [string] -or $target[$key] -cnotmatch $rules[$key]) { throw "Invalid target manifest field $key." }
    }
    return $target
}

function Assert-MountedTarget([string]$Name, $Container, $Target) {
    $volume = if ($Name -eq 'postgres') { $Target.postgresVolume } else { $Target.kafkaVolume }
    $image = if ($Name -eq 'postgres') { $Target.postgresImage } else { $Target.kafkaImage }
    $destination = if ($Name -eq 'postgres') { '/var/lib/postgresql/data' } else { '/var/lib/kafka/data' }
    $matches = @($Container.mounts | Where-Object { $_.Destination -ceq $destination })
    if ($Container.image -cne $image -or $matches.Count -ne 1 -or $matches[0].Type -cne 'volume' -or
        $matches[0].Name -cne $volume -or !$matches[0].RW) { throw "$Name image or persistent data mount does not match the target." }
    if ($Name -eq 'postgres') {
        $guards = @($Container.mounts | Where-Object { $_.Destination -ceq '/opt/kaipay/kaipay-demo-postgres-guard.sh' })
        $fullGuard = [IO.Path]::GetFullPath($postgresGuard)
        if ($fullGuard -notmatch '^([a-zA-Z]):[\\/](.+)$') { throw 'The PostgreSQL guard must have an absolute local drive path.' }
        $desktopGuard = '/run/desktop/mnt/host/' + $Matches[1].ToLowerInvariant() + '/' + ($Matches[2] -replace '\\', '/')
        if ($guards.Count -ne 1 -or $guards[0].Type -cne 'bind' -or $guards[0].RW -or
            ($guards[0].Source -ine $fullGuard -and $guards[0].Source -cne $desktopGuard)) {
            throw 'PostgreSQL bootstrap guard mount does not match the expected read-only script.'
        }
    }
}

function Read-ProtectedIdentities {
    # One canonical decimal system-id|database-oid pair per line, independent of target.json.
    try { $content = [IO.File]::ReadAllText($protectedPath, [Text.UTF8Encoding]::new($false, $true)) }
    catch { throw 'Protected PostgreSQL identity policy is missing or unreadable.' }
    $lines = @($content -split '\r?\n')
    if ($lines.Count -gt 1 -and $lines[-1] -ceq '') { $lines = @($lines[0..($lines.Count - 2)]) }
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    if (!$lines.Count) { throw 'Protected PostgreSQL identity policy must be nonempty.' }
    foreach ($line in $lines) {
        if ($line -cnotmatch '^[1-9][0-9]{9,24}\|[1-9][0-9]{0,9}$' -or !$seen.Add($line)) {
            throw 'Protected PostgreSQL identity policy is empty, malformed, or ambiguous.'
        }
        $parts = $line.Split('|')
        $system = [uint64]0; $oid = [uint32]0
        if (![uint64]::TryParse($parts[0], [ref]$system) -or ![uint32]::TryParse($parts[1], [ref]$oid)) {
            throw 'Protected PostgreSQL identity policy contains an out-of-range identity.'
        }
    }
    return ,$seen
}

function Assert-UnprotectedIdentity([string]$SystemId, [string]$DatabaseOid, $Policy) {
    # Numeric normalization prevents alternate decimal spellings from evading a pair.
    if ($SystemId -cnotmatch '^\d{10,25}$' -or $DatabaseOid -cnotmatch '^\d{1,10}$') { throw 'Invalid PostgreSQL identity.' }
    $pair = ([System.Numerics.BigInteger]::Parse($SystemId)).ToString() + '|' + ([System.Numerics.BigInteger]::Parse($DatabaseOid)).ToString()
    if ($Policy.Contains($pair)) { throw 'A protected PostgreSQL identity was selected; backend startup is prohibited.' }
}

function Assert-DatabaseIdentity([string]$Identity, $Target, $Policy) {
    if ($Identity -cnotmatch '^kaipay\|(\d{10,25})\|(\d{1,10})$') { throw 'Cannot validate observed PostgreSQL identity.' }
    Assert-UnprotectedIdentity $Matches[1] $Matches[2] $Policy
    if ($Identity -cne "kaipay|$($Target.postgresSystemId)|$($Target.postgresDatabaseOid)") { throw 'PostgreSQL cluster or database identity mismatch.' }
}

function Assert-Targets($Target) {
    $policy = Read-ProtectedIdentities
    Assert-UnprotectedIdentity $Target.postgresSystemId $Target.postgresDatabaseOid $policy
    if ($engine -cne $Target.engine) { throw 'Docker Engine does not match the target.' }
    $script:composeTargetVolume = $Target.kafkaVolume
    $script:composeKafkaClusterId = $Target.kafkaClusterId
    $script:composePostgresSystemId = $Target.postgresSystemId
    $containers = @{}
    foreach ($name in $dependencies.Keys) {
        $container = Get-Container $name
        if (!$container -or $container.state -cne 'running') { throw "The verified $name dependency must already be running; Start never creates or starts dependencies." }
        Assert-ComposeContainer $name $container
        Assert-MountedTarget $name $container $Target
        if (!(Test-DependencyReady $name $container)) { throw "$name is not ready." }
        $containers[$name] = $container
    }
    if ($isolatedInstance) {
        # Names no longer make the default installation's volumes exclusive: an isolated
        # instance must be the only container (running or not) using its data volumes.
        foreach ($name in $containers.Keys) {
            $volume = if ($name -eq 'postgres') { $Target.postgresVolume } else { $Target.kafkaVolume }
            $users = @((Invoke-Docker @('ps', '-aq', '--no-trunc', '--filter', "volume=$volume")) -split '\r?\n' | Where-Object { $_ })
            if ($users.Count -ne 1 -or $users[0] -cne $containers[$name].id) { throw "The $name data volume is also used by another container; an isolated instance must not share it." }
        }
    }
    $catalogSql = "SELECT 'kaipay',system_identifier,(SELECT oid FROM pg_database WHERE datname='kaipay') FROM pg_control_system() WHERE current_database()='postgres'"
    $catalog = Invoke-Docker @('exec', $containers.postgres.id, 'psql', '-X', '-A', '-t', '-F', '|', '-v', 'ON_ERROR_STOP=1', '-U', 'kaipay', '-d', 'postgres', '-c', $catalogSql)
    Assert-DatabaseIdentity $catalog $Target $policy
    $sql = "SELECT current_database(),system_identifier,(SELECT oid FROM pg_database WHERE datname=current_database()) FROM pg_control_system()"
    $database = Invoke-Docker @('exec', $containers.postgres.id, 'psql', '-X', '-A', '-t', '-F', '|', '-v', 'ON_ERROR_STOP=1', '-U', 'kaipay', '-d', 'kaipay', '-c', $sql)
    Assert-DatabaseIdentity $database $Target (Read-ProtectedIdentities)
    $schemaSql = "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind IN ('r','p','v','m','S','f')"
    $objectCount = Invoke-Docker @('exec', $containers.postgres.id, 'psql', '-X', '-A', '-t', '-v', 'ON_ERROR_STOP=1', '-U', 'kaipay', '-d', 'kaipay', '-c', $schemaSql)
    if ($objectCount -cnotmatch '^\d+$') { throw 'Cannot determine PostgreSQL schema state.' }
    if ([long]$objectCount -ne 0) {
        $historySql = "SELECT COALESCE(string_agg(version,',' ORDER BY installed_rank),'') || '|' || COALESCE(bool_and(success)::text,'false') FROM flyway_schema_history WHERE version IS NOT NULL"
        $history = Invoke-Docker @('exec', $containers.postgres.id, 'psql', '-X', '-A', '-t', '-v', 'ON_ERROR_STOP=1', '-U', 'kaipay', '-d', 'kaipay', '-c', $historySql)
        if ($history -cne '1,2,3,4,5,6,7,8,9,10,11|true') { throw 'Database is not empty or fully migrated V1–V11; backend startup is prohibited.' }
        $currencySql = "SELECT (SELECT count(*) FROM payments WHERE currency IS DISTINCT FROM 'MYR') + (SELECT count(*) FROM refunds WHERE currency IS DISTINCT FROM 'MYR') + (SELECT count(*) FROM accounts WHERE currency IS DISTINCT FROM 'MYR') + (SELECT count(*) FROM ledger_entries WHERE currency IS DISTINCT FROM 'MYR')"
        $otherCurrency = Invoke-Docker @('exec', $containers.postgres.id, 'psql', '-X', '-A', '-t', '-v', 'ON_ERROR_STOP=1', '-U', 'kaipay', '-d', 'kaipay', '-c', $currencySql)
        if ($otherCurrency -cne '0') { throw 'Database contains non-MYR financial rows; backend startup is prohibited.' }
    }
    $metadata = Invoke-Docker @('exec', $containers.kafka.id, '/opt/kafka/bin/kafka-metadata-quorum.sh', '--bootstrap-server', 'localhost:9092', 'describe', '--status') 15
    if ($metadata -cnotmatch '(?m)^ClusterId:\s*([A-Za-z0-9_-]+)\s*$' -or $Matches[1] -cne $Target.kafkaClusterId) { throw 'Kafka cluster identity mismatch.' }
    $properties = Invoke-Docker @('exec', $containers.kafka.id, 'sh', '-c', "grep -E '^(log.dirs|metadata.log.dir|listeners|advertised.listeners)=' /opt/kafka/config/server.properties")
    $lines = @($properties -split '\r?\n')
    foreach ($expected in @('log.dirs=/var/lib/kafka/data', 'metadata.log.dir=/var/lib/kafka/data',
        'listeners=INTERNAL://0.0.0.0:9092,EXTERNAL://0.0.0.0:9094,CONTROLLER://0.0.0.0:9093',
        "advertised.listeners=INTERNAL://kafka:9092,EXTERNAL://localhost:$($instance.kafkaPort)")) {
        if (@($lines | Where-Object { $_ -ceq $expected }).Count -ne 1) { throw 'Kafka effective listeners or storage settings do not match the expected configuration.' }
    }
    $diskMeta = Invoke-Docker @('exec', $containers.kafka.id, 'cat', '/var/lib/kafka/data/meta.properties')
    if (@($diskMeta -split '\r?\n' | Where-Object { $_ -ceq "cluster.id=$($Target.kafkaClusterId)" }).Count -ne 1) { throw 'Kafka storage cluster identity mismatch.' }
    return $containers
}

function Test-BackendConnection($Target, $Record, $Container) {
    if (!$Record.ContainsKey('applicationName') -or $Record.applicationName -cnotmatch '^kaipay-demo-[0-9a-f]{32}$') { return $false }
    $sql = "SELECT count(*) FROM pg_stat_activity WHERE application_name='$($Record.applicationName)' AND datname='kaipay' AND usename='kaipay' AND backend_type='client backend'"
    try {
        foreach ($port in @($instance.postgresPort, $instance.kafkaPort)) {
            if (!(Get-NetTCPConnection -OwningProcess ([int]$Record.pid) -RemoteAddress '127.0.0.1' -RemotePort $port -State Established -ErrorAction Stop | Select-Object -First 1)) { return $false }
        }
        return [int](Invoke-Docker @('exec', $Container.id, 'psql', '-X', '-A', '-t', '-v', 'ON_ERROR_STOP=1', '-U', 'kaipay', '-d', 'postgres', '-c', $sql)) -gt 0
    }
    catch { return $false }
}

function Assert-HostPostgresTarget([string]$Java, [string]$Url, [string]$Username, [string]$Password, $Target) {
    # Both stages use the same loopback route, driver and credentials.
    if ($Url -cnotmatch '^jdbc:postgresql://127\.0\.0\.1:([0-9]{1,5})/kaipay\?ApplicationName=kaipay-demo-[0-9a-f]{32}$' -or
        [int]$Matches[1] -lt 1 -or [int]$Matches[1] -gt 65535) { throw 'Invalid host PostgreSQL probe URL.' }
    $maintenanceUrl = $Url.Replace('/kaipay?', '/postgres?')
    $policy = Read-ProtectedIdentities
    Assert-UnprotectedIdentity $Target.postgresSystemId $Target.postgresDatabaseOid $policy
    $probeDir = Join-Path $runtime ('jdbc-probe-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $probeDir | Out-Null
    try {
        $driver = Join-Path $probeDir 'postgresql.jar'
        $archive = [IO.Compression.ZipFile]::OpenRead((Join-Path $backend $jar))
        try {
            $entries = @($archive.Entries | Where-Object { $_.FullName -cmatch '^BOOT-INF/lib/postgresql-[^/]+\.jar$' })
            if ($entries.Count -ne 1) { throw 'Backend artifact has no unique PostgreSQL JDBC driver.' }
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entries[0], $driver)
        } finally { $archive.Dispose() }
        $source = Join-Path $probeDir 'KaiPayDatabaseProbe.java'
        [IO.File]::WriteAllText($source, @'
import java.io.*;
import java.sql.*;
public class KaiPayDatabaseProbe {
  public static void main(String[] args) throws Exception {
    BufferedReader input = new BufferedReader(new InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8));
    String url = input.readLine(), user = input.readLine(), password = input.readLine(), stage = input.readLine();
    if (url == null || user == null || password == null || stage == null) throw new Exception("missing connection input");
    String query;
    if (stage.equals("catalog"))
      query = "SELECT 'kaipay', system_identifier::text, (SELECT oid::text FROM pg_database WHERE datname='kaipay') FROM pg_control_system() WHERE current_database()='postgres'";
    else if (stage.equals("direct"))
      query = "SELECT current_database(), system_identifier::text, (SELECT oid::text FROM pg_database WHERE datname=current_database()) FROM pg_control_system()";
    else throw new Exception("invalid stage");
    try (Connection connection = DriverManager.getConnection(url, user, password);
         Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery(query)) {
      if (!result.next()) throw new Exception("missing identity");
      String name = result.getString(1), system = result.getString(2), oid = result.getString(3);
      if (name == null || system == null || oid == null || result.next()) throw new Exception("missing or ambiguous identity");
      System.out.println(name + "|" + system + "|" + oid);
    }
  }
}
'@)
        foreach ($stage in @('catalog', 'direct')) {
        $policy = Read-ProtectedIdentities
        Assert-UnprotectedIdentity $Target.postgresSystemId $Target.postgresDatabaseOid $policy
        $info = [Diagnostics.ProcessStartInfo]::new($Java)
        $info.UseShellExecute = $false
        $info.CreateNoWindow = $true
        $info.RedirectStandardInput = $true
        $info.RedirectStandardOutput = $true
        $info.RedirectStandardError = $true
        $info.StandardInputEncoding = [Text.UTF8Encoding]::new($false)
        foreach ($key in @($info.Environment.Keys)) {
            if ($key -match '^(SPRING|SERVER|KAIPAY|DB_|KAFKA_|PG|JDK_JAVA_OPTIONS$|JAVA_TOOL_OPTIONS$|_JAVA_OPTIONS$|CLASSPATH$)') { $null = $info.Environment.Remove($key) }
        }
        foreach ($argument in @('--class-path', $driver, $source)) { $info.ArgumentList.Add($argument) }
        $child = [Diagnostics.Process]::Start($info)
        try {
            $stdout = $child.StandardOutput.ReadToEndAsync()
            $stderr = $child.StandardError.ReadToEndAsync()
            $child.StandardInput.WriteLine($(if ($stage -ceq 'catalog') { $maintenanceUrl } else { $Url }))
            $child.StandardInput.WriteLine($Username)
            $child.StandardInput.WriteLine($Password)
            $child.StandardInput.WriteLine($stage)
            $child.StandardInput.Close()
            if (!$child.WaitForExit(30000)) {
                try { $child.Kill($true) } catch [InvalidOperationException] { if (!$child.HasExited) { throw } }
                throw 'Host PostgreSQL identity verification timed out.'
            }
            if ($child.ExitCode -ne 0) {
                throw 'Host PostgreSQL connection or identity verification failed; backend startup is prohibited.'
            }
            Assert-DatabaseIdentity $stdout.Result.Trim() $Target $policy
        } finally { $child.Dispose() }
        }
    } finally {
        foreach ($file in @((Join-Path $probeDir 'postgresql.jar'), (Join-Path $probeDir 'KaiPayDatabaseProbe.java'))) {
            if (Test-Path -LiteralPath $file -PathType Leaf) { Remove-Item -LiteralPath $file -Force }
        }
        Remove-Item -LiteralPath $probeDir -Force
    }
}

function Test-RecordedTarget($Record, $Target) {
    if (!$Record -or !$Record.ContainsKey('target') -or $Record.target -isnot [Collections.IDictionary]) { return $false }
    foreach ($key in $Target.Keys) {
        if (!$Record.target.ContainsKey($key) -or $Record.target[$key] -cne $Target[$key]) { return $false }
    }
    return $Record.target.Count -eq $Target.Count
}

function Get-GenerationTopics($Target, $Container) {
    $base = "kaipay.myr.$($Target.generation)"
    $all = Invoke-Docker @('exec', $Container.id, '/opt/kafka/bin/kafka-topics.sh', '--bootstrap-server', 'localhost:9092', '--list') 15
    return @($all -split '\r?\n' | Where-Object { $_ -ceq $base -or $_.StartsWith("$base-", [StringComparison]::Ordinal) })
}

function Get-GenerationTopicIds($Target, $Container) {
    $ids = @{}
    foreach ($topic in (Get-GenerationTopics $Target $Container)) {
        $description = Invoke-Docker @('exec', $Container.id, '/opt/kafka/bin/kafka-topics.sh', '--bootstrap-server', 'localhost:9092', '--describe', '--topic', $topic) 15
        if ($description -cnotmatch ('(?m)^Topic:\s*' + [regex]::Escape($topic) + '\s+TopicId:\s*([a-zA-Z0-9_-]+)\s')) { throw "Cannot verify Kafka topic ID for $topic." }
        $ids[$topic] = $Matches[1]
    }
    return $ids
}

function Assert-Generation($Target, $Container) {
    $group = "kaipay-myr-$($Target.generation)"
    if (!$state.ContainsKey('generation')) {
        $topics = @(Get-GenerationTopics $Target $Container)
        $groups = Invoke-Docker @('exec', $Container.id, '/opt/kafka/bin/kafka-consumer-groups.sh', '--bootstrap-server', 'localhost:9092', '--list') 15
        $matchingGroups = @($groups -split '\r?\n' | Where-Object { $_ -ceq $group -or $_.StartsWith("$group-", [StringComparison]::Ordinal) })
        if ($topics.Count -or $matchingGroups.Count) { throw 'Kafka topic family or group already exists without an established launcher reservation.' }
        $state.generation = @{ target = $Target; phase = 'pending'; topics = @{} }
        Save-State
        return
    }
    Assert-EstablishedGeneration $Target $Container
}

function Assert-EstablishedGeneration($Target, $Container) {
    $base = "kaipay.myr.$($Target.generation)"
    if (!$state.ContainsKey('generation') -or $state.generation.phase -cne 'established' -or !(Test-RecordedTarget $state.generation $Target)) {
        throw 'Kafka generation is pending or differs from the recorded target; inspect it before retrying.'
    }
    $actual = Get-GenerationTopicIds $Target $Container
    if (!$actual.ContainsKey($base) -or $actual.Count -ne $state.generation.topics.Count) { throw 'Kafka topic family differs from its recorded reservation.' }
    foreach ($name in $actual.Keys) {
        if (!$state.generation.topics.ContainsKey($name) -or $state.generation.topics[$name] -cne $actual[$name]) { throw 'Kafka topic identity differs from its recorded reservation.' }
    }
}

function Complete-Generation($Target, $Container) {
    if ($state.generation.phase -ceq 'established') { return }
    $base = "kaipay.myr.$($Target.generation)"
    $expected = @($base, "$base-retry-1000", "$base-retry-2000", "$base-dlt")
    Wait-Ready 'Kafka topic family' {
        $topics = @(Get-GenerationTopics $Target $Container)
        return $topics.Count -eq $expected.Count -and @($expected | Where-Object { $_ -cnotin $topics }).Count -eq 0
    } 'Inspect topic creation and leave the pending generation intact.'
    $ids = Get-GenerationTopicIds $Target $Container
    if ($ids.Count -ne $expected.Count -or @($expected | Where-Object { !$ids.ContainsKey($_) }).Count) { throw 'Kafka topic family changed while it was recorded.' }
    $state.generation.topics = $ids
    $state.generation.phase = 'established'
    Save-State
}

function Assert-ComposeContainer([string]$Name, $Container) {
    $labels = $Container.labels
    $hash = Invoke-Compose @('config', '--hash', $Name)
    $port = $dependencies[$Name]
    $binding = @()
    $configFiles = "$composePath,$kafkaOverlay,$postgresOverlay"
    if ($isolatedInstance) { $configFiles += ",$instanceOverlay" }
    $acceptedFiles = @($configFiles, "$configFiles,$composeOverride")
    if ($Container.ports) { $binding = @($Container.ports[$port.ContainerPort]) }
    if ($Container.name -cne "/$($instance.name)-$Name" -or !$labels -or
        $labels['com.docker.compose.project'] -cne $instance.name -or $labels['com.docker.compose.service'] -cne $Name -or
        $labels['com.docker.compose.project.working_dir'] -ne $root -or
        $labels['com.docker.compose.project.config_files'] -notin $acceptedFiles -or
        $hash -cne "$Name $($labels['com.docker.compose.config-hash'])" -or
        !$Container.ports -or $Container.ports.Count -ne 1 -or $binding.Count -ne 1 -or !$binding[0] -or
        $binding[0]['HostIp'] -cne '127.0.0.1' -or $binding[0]['HostPort'] -ne "$($port.Port)") {
        throw "Container $($Container.id) conflicts with $($instance.name)-$Name or differs from this checkout's Compose configuration. Inspect it before starting the demo."
    }
}

function Test-ComposeResource([string]$Kind, [string]$Name, [string]$Key) {
    $found = Invoke-Docker @($Kind, 'ls', '--filter', "name=$Name", '--format', '{{.Name}}')
    if ($Name -cnotin ($found -split '\r?\n')) { return $false }
    $labels = Invoke-Docker @($Kind, 'inspect', '--format', '{{json .Labels}}', $Name) | ConvertFrom-Json -AsHashtable
    if (!$labels -or $labels['com.docker.compose.project'] -cne $instance.name -or
        $labels["com.docker.compose.$Kind"] -cne $Key) {
        throw "$Kind '$Name' lacks the expected Compose labels. Inspect its ownership before starting the demo."
    }
    return $true
}

function Test-DependencyReady([string]$Name, $Container) {
    if (!$Container -or $Container.state -cne 'running') { return $false }
    if ($Name -eq 'postgres') { return $Container.health -ceq 'healthy' }
    try {
        $null = Invoke-Docker @('exec', $Container.id, '/opt/kafka/bin/kafka-topics.sh', '--bootstrap-server', 'localhost:9092', '--list') 6
        return $true
    } catch { return $false }
}

function Test-HttpReady([string]$Url, [switch]$Csrf) {
    try {
        $response = Invoke-WebRequest -Uri $Url -NoProxy -TimeoutSec 2 -MaximumRedirection 0
        if (!$Csrf) { return $response.StatusCode -eq 200 }
        $data = ($response.Content | ConvertFrom-Json -AsHashtable).data
        return $response.StatusCode -eq 200 -and $data.headerName -ceq 'X-CSRF-TOKEN' -and [bool]$data.token
    } catch { return $false }
}

function Test-AppReady([string]$Name) {
    if ($Name -eq 'backend') {
        return (Select-String -LiteralPath $state.apps.backend.stdout -SimpleMatch 'Started KaiPayApplication' -Quiet) -and
            (Test-HttpReady "$apiUrl/v1/auth/csrf" -Csrf)
    }
    return (Select-String -LiteralPath $state.apps.frontend.stdout -Pattern "Local:.*http://127\.0\.0\.1:$($instance.frontendPort)/" -Quiet) -and
        (Test-HttpReady "$webUrl/@vite/client") -and (Test-HttpReady "$webUrl/v1/auth/csrf" -Csrf)
}

function Wait-Ready([string]$Name, [scriptblock]$Probe, [string]$Hint) {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        if (& $Probe) { return }
        Start-Sleep -Milliseconds 500
    } while ($timer.Elapsed.TotalSeconds -lt $ReadyTimeoutSeconds)
    throw "$Name did not become ready within ${ReadyTimeoutSeconds}s. $Hint"
}

function Find-Jdk21([string[]]$Candidates) {
    foreach ($candidate in $Candidates | Where-Object { $_ } | Select-Object -Unique) {
        $directory = Get-Item -LiteralPath $candidate -Force -ErrorAction SilentlyContinue
        if ($directory -isnot [IO.DirectoryInfo]) { continue }
        $java = Join-Path $directory.FullName 'bin/java.exe'
        $javac = Join-Path $directory.FullName 'bin/javac.exe'
        if (!(Test-Path -LiteralPath $java -PathType Leaf) -or !(Test-Path -LiteralPath $javac -PathType Leaf)) { continue }
        try {
            if ((Invoke-Native $java @('--version')) -match '^(?:openjdk|java) 21(?:[.\s+\-]|$)' -and
                (Invoke-Native $javac @('-version')) -match '^javac 21(?:[.\s+\-]|$)') { return $directory.FullName }
        } catch { Write-Verbose "Could not probe Java at $($directory.FullName)." }
    }
}

function Get-Jdk {
    if ($JdkHome) {
        $selected = Find-Jdk21 @($JdkHome)
        if (!$selected) { throw "-JdkHome '$JdkHome' must point to a working JDK 21 installation with bin\java.exe and bin\javac.exe." }
        return $selected
    }

    $candidates = @($env:JAVA_HOME)
    $candidates += @(Get-Command java.exe -CommandType Application -All -ErrorAction SilentlyContinue |
        ForEach-Object { Split-Path -Parent (Split-Path -Parent $_.Source) })
    if ($env:ProgramFiles) {
        foreach ($vendor in @('Java', 'Eclipse Adoptium', 'Microsoft', 'Amazon Corretto', 'Zulu')) {
            $candidates += @(Get-ChildItem -LiteralPath (Join-Path $env:ProgramFiles $vendor) -Directory -ErrorAction SilentlyContinue |
                ForEach-Object FullName)
        }
    }
    $selected = Find-Jdk21 $candidates
    if (!$selected) { throw 'JDK 21 was not found. Install JDK 21 or pass -JdkHome with the directory containing bin\java.exe and bin\javac.exe.' }
    return $selected
}

function Invoke-Build([string]$Directory, [string]$Command, [string[]]$Arguments, [string]$Log) {
    Write-Host "Preparing dependencies or build artifacts. Log $Log"
    Push-Location -LiteralPath $Directory
    try {
        & $Command @Arguments *> $Log
        if ($LASTEXITCODE -ne 0) { throw "Build exited $LASTEXITCODE. See $Log" }
    } finally { Pop-Location }
}

function Test-BackendArtifact {
    $path = Join-Path $backend $jar
    if (!(Test-Path -LiteralPath $path -PathType Leaf)) { return $false }
    $stamp = (Get-Item -LiteralPath $path).LastWriteTimeUtc
    $inputs = @('src/main', '.mvn', 'pom.xml', 'mvnw.cmd') | ForEach-Object { Join-Path $backend $_ }
    if (Get-ChildItem -LiteralPath $inputs -Recurse -File -Force | Where-Object LastWriteTimeUtc -gt $stamp | Select-Object -First 1) {
        return $false
    }
    try {
        $archive = [IO.Compression.ZipFile]::OpenRead($path)
        try { return [bool]($archive.GetEntry('org/springframework/boot/loader/launch/JarLauncher.class') -and
            $archive.GetEntry('BOOT-INF/classes/application-local.yaml')) }
        finally { $archive.Dispose() }
    } catch { return $false }
}

function Test-FrontendDependencies {
    $installed = Join-Path $frontend 'node_modules/.package-lock.json'
    if (!(Test-Path -LiteralPath $installed -PathType Leaf) -or
        !(Test-Path -LiteralPath (Join-Path $frontend $vite) -PathType Leaf)) { return $false }
    $stamp = (Get-Item -LiteralPath $installed).LastWriteTimeUtc
    return !(Get-Item -LiteralPath (Join-Path $frontend 'package.json'), (Join-Path $frontend 'package-lock.json') |
        Where-Object LastWriteTimeUtc -gt $stamp)
}

function Start-App([string]$Name, [string]$Executable, [string]$Directory, [string[]]$Arguments, [string]$RunId, [string]$JavaHome, $Target, [string]$ApplicationName) {
    $stdout = Join-Path $runtime "$RunId-$Name.stdout.log"
    $stderr = Join-Path $runtime "$RunId-$Name.stderr.log"
    # Keep a caller's deployment configuration and JVM/Node injection options out of this local run.
    $environment = @{}
    Get-ChildItem Env: | Where-Object Name -match '^(SPRING|SERVER|KAIPAY|DB_|KAFKA_|LOGGING|MANAGEMENT|JDK_JAVA_OPTIONS$|JAVA_TOOL_OPTIONS$|_JAVA_OPTIONS$|NODE_OPTIONS$|PORT$|CLASSPATH$)' |
        ForEach-Object { $environment[$_.Name] = $null }
    if ($JavaHome) { $environment['JAVA_HOME'] = $JavaHome }
    if ($Name -eq 'backend') { $environment['DB_USERNAME'] = $script:databaseUsername; $environment['DB_PASSWORD'] = $script:plainDatabasePassword }
    # The dev-server proxy must reach this instance's backend, never the default one.
    if ($Name -eq 'frontend' -and $isolatedInstance) { $environment['KAIPAY_DEV_API_PORT'] = "$($instance.backendPort)" }
    # On Windows Vite colours its URL unless NO_COLOR is set; readiness matches the plain text.
    if ($Name -eq 'frontend') { $environment['NO_COLOR'] = '1'; $environment['FORCE_COLOR'] = $null }
    $child = Start-Process -FilePath $Executable -ArgumentList $Arguments -WorkingDirectory $Directory `
        -Environment $environment -RedirectStandardOutput $stdout -RedirectStandardError $stderr -WindowStyle Hidden -PassThru
    $recorded = $false
    try {
        # Start-Process can return before Windows makes the executable module visible.
        $image = $null
        for ($attempt = 0; $attempt -lt 20 -and !$child.HasExited; $attempt++) {
            $image = $child.MainModule
            if ($image) { break }
            Start-Sleep -Milliseconds 100
        }
        if (!$image) { throw 'The process exited or its executable could not be identified.' }
        $state.apps[$Name] = @{ pid = $child.Id; started = "$($child.StartTime.ToUniversalTime().Ticks)";
            path = $image.FileName; stdout = $stdout; stderr = $stderr }
        if ($Name -eq 'backend') { $state.apps[$Name].target = $Target; $state.apps[$Name].applicationName = $ApplicationName }
        $launchedApps.Add($Name)
        $recorded = $true
        Save-State
    } catch {
        if (!$recorded -and !$child.HasExited) { $child.Kill() }
        throw "Could not record $name startup. See $stdout and $stderr. $($_.Exception.Message)"
    } finally { $child.Dispose() }
}

function Stop-Runtime([string[]]$AppNames, [string[]]$DependencyNames) {
    $failed = $false
    foreach ($name in $AppNames) {
        if (!$state.apps[$name]) { continue }
        try {
            $process = Get-OwnedProcess $name
            if ($process) {
                try {
                    try { $process.Kill() } catch [InvalidOperationException] { if (!$process.HasExited) { throw } }
                    if (!$process.WaitForExit(10000)) { throw 'Process did not exit within 10 seconds.' }
                    Write-Host "Stopped $name (PID $($process.Id))."
                } finally { $process.Dispose() }
            } else { Write-Host "Cleared stale $name process record." }
            $state.apps.Remove($name)
            Save-State
        } catch { $failed = $true; Write-Warning "Could not stop ${name}. $($_.Exception.Message)" }
    }
    if ($failed) { throw 'Application shutdown incomplete. Retry Stop before stopping dependencies.' }

    $ownedDependencies = @($DependencyNames | Where-Object { $state.containers[$_] })
    if (!$ownedDependencies.Count) { return }
    if (!$engine) { Connect-Docker }
    if ($state.engine -cne $engine) { throw 'Docker Engine identity changed. Restore the original local Docker context and retry Stop.' }
    foreach ($name in $ownedDependencies) {
        try {
            $record = $state.containers[$name]
            $container = Get-Container $name $record.id
            if ($container -and $container.name -ceq "/$($instance.name)-$name" -and $container.started -ceq $record.started -and
                $container.state -in @('running', 'restarting', 'paused')) {
                $null = Invoke-Docker @('stop', '--time', '15', $record.id) 40
                Write-Host "Stopped $name ($($record.id.Substring(0, 12)))."
            } else { Write-Host "Cleared inactive or changed $name container record." }
            $state.containers.Remove($name)
            Save-State
        } catch { $failed = $true; Write-Warning "Could not stop ${name}. $($_.Exception.Message)" }
    }
    if ($failed) { throw 'Docker dependency shutdown incomplete. Retry Stop.' }
}

function Show-Status {
    $verified = $false
    try {
        $target = Read-Target
        Write-Host "Intended target: database=kaipay system=$($target.postgresSystemId) oid=$($target.postgresDatabaseOid); Kafka cluster=$($target.kafkaClusterId) generation=$($target.generation)"
        if (!$engine) { Connect-Docker }
        $observed = Assert-Targets $target
        Write-Host "Observed dependency identities: database=kaipay system=$($target.postgresSystemId) oid=$($target.postgresDatabaseOid); Kafka cluster=$($target.kafkaClusterId) (matched)."
        Assert-EstablishedGeneration $target $observed.kafka
        Write-Host 'Observed Kafka topic identities match the established generation.'
        $verified = $true
    } catch { Write-Warning "Target identities unverified: $($_.Exception.Message)" }
    foreach ($name in $apps.Keys) {
        $process = Get-OwnedProcess $name
        if ($process) {
            try {
                $status = if ($name -eq 'backend' -and (!$verified -or !(Test-RecordedTarget $state.apps.backend $target) -or
                    !(Test-BackendConnection $target $state.apps.backend $observed.postgres))) { 'running, target unverified' }
                    elseif (Test-AppReady $name) { 'ready' } else { 'running, readiness check failed' }
                Write-Host "$name managed, $status (PID $($process.Id)). Logs $($state.apps[$name].stdout) and $($state.apps[$name].stderr)"
            } finally { $process.Dispose() }
        } elseif (Test-PortListening $apps[$name]) { Write-Host "$name port $($apps[$name]) is occupied by an unmanaged listener." }
        else { Write-Host "$name stopped." }
    }
    try {
        if (!$engine) { Connect-Docker }
        foreach ($name in $dependencies.Keys) {
            $container = Get-Container $name
            if (!$container) { Write-Host "$name container absent."; continue }
            try { Assert-ComposeContainer $name $container }
            catch { Write-Warning "${name}: $($_.Exception.Message)"; continue }
            $record = $state.containers[$name]
            $owner = if ($record -and $state.engine -ceq $engine -and $record.id -ceq $container.id -and
                $record.started -ceq $container.started) { 'managed' } else { 'unmanaged' }
            $status = if (Test-DependencyReady $name $container) { 'ready' }
                elseif ($container.state -eq 'running') { 'running, readiness check failed' } else { $container.state }
            Write-Host "$name $owner, $status (port $($dependencies[$name].Port))."
        }
    } catch { Write-Warning "Docker dependency status unknown. $($_.Exception.Message)" }
    Write-Host "Frontend $webUrl"
    Write-Host "API $apiUrl/v1/auth/csrf"
    Write-Host "Admin diagnostics $webUrl/events/outbox and $webUrl/events/dlt"
}

function Start-Runtime {
    $target = Read-Target
    $policy = Read-ProtectedIdentities
    Assert-UnprotectedIdentity $target.postgresSystemId $target.postgresDatabaseOid $policy
    $jdk = Get-Jdk
    $java = Join-Path $jdk 'bin/java.exe'
    Write-Host "Selected JDK 21 at $jdk"
    $node = (Get-Command node.exe -CommandType Application | Select-Object -First 1).Source
    $npm = (Get-Command npm.cmd -CommandType Application | Select-Object -First 1).Source
    $nodeVersion = Invoke-Native $node @('--version')
    if ($nodeVersion -notmatch '^v(\d+)\.' -or [int]$Matches[1] -lt 22) { throw 'Install Node.js 22 or newer for the locked frontend toolchain.' }
    $null = & $npm --version
    if ($LASTEXITCODE -ne 0) { throw 'npm could not run. Check the Node.js installation.' }
    Connect-Docker
    if ($state.containers.Count -and $state.engine -cne $engine) {
        throw 'Docker Engine identity changed. Restore the original local Docker context and run Stop first.'
    }
    $state.engine = $engine

    # Never create or start either dependency.  The operator prepares and verifies them
    # separately; in particular this avoids initializing an unidentified volume.
    $observed = Assert-Targets $target

    $running = @{}
    foreach ($name in $apps.Keys) {
        $process = Get-OwnedProcess $name
        if ($process) {
            $process.Dispose()
            if ($Rebuild) { throw 'Stop the managed demo before rebuilding.' }
            if ($name -eq 'backend' -and (!(Test-RecordedTarget $state.apps.backend $target) -or
                !(Test-BackendConnection $target $state.apps.backend $observed.postgres))) { throw 'Running backend target is missing or differs from the verified target.' }
            $running[$name] = $true
        } else {
            # A recorded PID that still exists but cannot be attributed to our exact
            # executable and start time is ambiguous, even when the port is free.
            $record = $state.apps[$name]
            if ($record -and $record.ContainsKey('pid')) {
                $other = Get-Process -Id ([int]$record.pid) -ErrorAction SilentlyContinue
                if ($other) { $other.Dispose(); throw "Recorded $name PID is active but cannot be attributed to this demo." }
            }
            Assert-PortAvailable $name $apps[$name]
        }
    }
    $applicationName = 'kaipay-demo-' + [guid]::NewGuid().ToString('N')
    $databaseUrl = "jdbc:postgresql://127.0.0.1:$($instance.postgresPort)/kaipay?ApplicationName=$applicationName"
    if (!$DatabasePassword) { $DatabasePassword = Read-Host 'PostgreSQL password for kaipay' -AsSecureString }
    $script:databaseUsername = 'kaipay'
    $script:plainDatabasePassword = [Net.NetworkCredential]::new('', $DatabasePassword).Password
    if (!$script:plainDatabasePassword -or $script:plainDatabasePassword -match '[\r\n]') { throw 'A nonempty single-line PostgreSQL password is required.' }
    Assert-HostPostgresTarget $java $databaseUrl $script:databaseUsername $script:plainDatabasePassword $target

    $runId = Get-Date -Format 'yyyyMMdd-HHmmss-ffff'
    if (!$running['backend'] -and ($Rebuild -or !(Test-BackendArtifact))) {
        $previousJavaHome = $env:JAVA_HOME
        try {
            $env:JAVA_HOME = $jdk
            Invoke-Build $backend (Join-Path $backend 'mvnw.cmd') @('clean', 'package', '-Dmaven.test.skip=true') (Join-Path $runtime "$runId-maven.log")
        } finally { $env:JAVA_HOME = $previousJavaHome }
        if (!(Test-BackendArtifact)) { throw "Maven did not produce a current executable JAR at $backend/$jar." }
    }
    if (!$running['frontend'] -and ($Rebuild -or !(Test-FrontendDependencies))) {
        Invoke-Build $frontend $npm @('ci', '--include=dev', '--no-audit', '--no-fund') (Join-Path $runtime "$runId-npm.log")
        if (!(Test-FrontendDependencies)) { throw 'npm did not produce a current Vite installation.' }
    }

    $observed = Assert-Targets $target
    # A build may replace the JDBC driver, so verify the final artifact as well.
    Assert-HostPostgresTarget $java $databaseUrl $script:databaseUsername $script:plainDatabasePassword $target
    Assert-Generation $target $observed.kafka
    foreach ($name in $apps.Keys) {
        if (!$running[$name]) {
            if ($name -eq 'backend') {
                $topic = "kaipay.myr.$($target.generation)"
                $group = "kaipay-myr-$($target.generation)"
                $backendArguments = @('-jar', $jar, '--spring.profiles.active=local', '--spring.config.location=classpath:/',
                    '--server.address=127.0.0.1', "--server.port=$($instance.backendPort)", '--kaipay.webhooks.mode=disabled', '--kaipay.integrations.enabled=false',
                    "--spring.datasource.url=$databaseUrl",
                    "--spring.kafka.bootstrap-servers=127.0.0.1:$($instance.kafkaPort)", "--spring.kafka.consumer.group-id=$group", "--kaipay.kafka.topics.payment-requests=$topic")
                if ($isolatedInstance) { $backendArguments += "--kaipay.security.allowed-origins=http://localhost:$($instance.frontendPort),$webUrl" }
                Start-App $name $java $backend $backendArguments $runId $jdk $target $applicationName
            } else {
                Start-App $name $node $frontend @($vite, '--host', '127.0.0.1', '--port', "$($instance.frontendPort)", '--strictPort') $runId '' $null ''
            }
        }
        $hint = "See $($state.apps[$name].stdout) and $($state.apps[$name].stderr)."
        Wait-Ready $name {
            $process = Get-OwnedProcess $name
            if (!$process) { throw "$name exited during startup. $hint" }
            try { Test-AppReady $name } finally { $process.Dispose() }
        } $hint
        if ($name -eq 'backend') {
            $observed = Assert-Targets $target
            if (!(Test-BackendConnection $target $state.apps.backend $observed.postgres)) { throw 'Backend database connection could not be attributed to the selected target.' }
            Complete-Generation $target $observed.kafka
        }
    }
    Show-Status
}

if ($Rebuild -and $Action -ne 'Start') { throw '-Rebuild requires -Action Start.' }
if ($JdkHome -and $Action -ne 'Start') { throw '-JdkHome requires -Action Start.' }
if ($DatabasePassword -and $Action -ne 'Start') { throw '-DatabasePassword requires -Action Start.' }
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
try { $lock = [IO.File]::Open((Join-Path $runtime 'operation.lock'), 'OpenOrCreate', 'ReadWrite', 'None') }
catch [IO.IOException] { throw 'Could not lock KaiPay demo state. Check for another invocation or an inaccessible logs directory.' }
try {
    $state = @{ version = 1; engine = $null; apps = @{}; containers = @{} }
    if (Test-Path -LiteralPath $statePath) {
        $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json -AsHashtable
        if ($state.version -ne 1 -or $state.apps -isnot [Collections.IDictionary] -or
            $state.containers -isnot [Collections.IDictionary]) { throw "Invalid demo state at $statePath." }
    }
    # An isolated instance's dependencies are created from this overlay, so it must
    # exist before they do; any action (e.g. Status) writes it.
    if ($isolatedInstance) { Write-InstanceOverlay }
    switch ($Action) {
        'Status' { Show-Status }
        'Stop' { Stop-Runtime @('frontend', 'backend') @('kafka', 'postgres'); Write-Host 'Demo shutdown complete.' }
        'Start' {
            $launchedApps = [Collections.Generic.List[string]]::new()
            $launchedDependencies = [Collections.Generic.List[string]]::new()
            try { Start-Runtime } catch {
                $startupFailure = $_
                $launchedApps.Reverse()
                try { Stop-Runtime $launchedApps.ToArray() $launchedDependencies.ToArray() }
                catch { Write-Warning "Startup rollback incomplete. Retry Stop. $($_.Exception.Message)" }
                throw $startupFailure
            }
        }
    }
} finally { $lock.Dispose() }
