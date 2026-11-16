# Isolated full-launcher startup rehearsal. It runs the launcher's real
# Start, Status and Stop from a temporary copy of the committed sources, using an
# isolated instance (distinct Compose project, container names and loopback ports)
# and disposable, uniquely labelled PostgreSQL 16 and Kafka 3.8 fixtures. It never
# uses an existing volume, container, port or the default instance, and removes only
# resources it created after rechecking their labels.
#   pwsh -NoProfile -File tests/demo-launcher-isolated-startup.e2e.ps1 [-RepositoryRoot <checkout>]
# RepositoryRoot supplies committed sources, the existing backend artifact and the
# installed frontend dependencies; none of them is modified.
param(
    [string]$RepositoryRoot,
    [int]$PostgresPort = 45432,
    [int]$KafkaPort = 49092,
    [int]$BackendPort = 48080,
    [int]$FrontendPort = 48081,
    [int]$ReadyTimeoutSeconds = 240,
    # Optional directory for server connection logs (no credentials are logged).
    [string]$EvidenceDirectory
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$candidate = Split-Path -Parent $PSScriptRoot
if (!$RepositoryRoot) { $RepositoryRoot = $candidate }
if (!(Test-Path -LiteralPath (Join-Path $RepositoryRoot 'backend/pom.xml') -PathType Leaf)) { throw 'Cannot locate the KaiPay checkout; pass -RepositoryRoot.' }
$RepositoryRoot = [IO.Path]::GetFullPath($RepositoryRoot)
$docker = (Get-Command docker.exe -CommandType Application).Source
# Windows bsdtar accepts drive-letter paths; a GNU tar earlier on PATH would not.
$tar = Join-Path $env:SystemRoot 'System32/tar.exe'
$tag = [guid]::NewGuid().ToString('N')
$name = 'kpe2e' + $tag.Substring(0, 12)
$work = Join-Path ([IO.Path]::GetTempPath()) "kaipay-e2e-$tag"
$copy = Join-Path $work 'root'
$runtimeDir = Join-Path $copy 'logs/kaipay-demo'
$launcher = Join-Path $copy 'scripts/KaiPay-Demo.ps1'
$fixtureLabel = "kaipay.e2e.fixture=$tag"
$pgVolume = "${name}_postgres_data"
$kafkaVolume = "${name}_kafka_fixture"
$generation = 'e2e' + $tag.Substring(12, 8)
$secret = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
$previousPassword = $env:POSTGRES_PASSWORD
$defaultPorts = @(25432, 29092, 28080, 28081)
$created = @{ init = $null; volumes = [Collections.Generic.List[string]]::new() }

function Invoke-DockerTest([string[]]$Arguments, [switch]$AllowFailure) {
    $output = & $docker @Arguments 2>&1
    if ($LASTEXITCODE -ne 0 -and !$AllowFailure) { throw "Docker command failed: $($Arguments[0..1] -join ' '): $($output | Select-Object -Last 3)" }
    return @($output | ForEach-Object { "$_" })
}
function Assert-True([bool]$Condition, [string]$Message) { if (!$Condition) { throw $Message } }
function Invoke-Psql([string]$Container, [string]$Database, [string]$Sql) {
    return ((Invoke-DockerTest @('exec', $Container, 'psql', '-X', '-q', '-A', '-t', '-v', 'ON_ERROR_STOP=1', '-U', 'kaipay', '-d', $Database, '-c', $Sql)) -join "`n").Trim()
}
function Get-Snapshot {
    $ids = @(Invoke-DockerTest @('ps', '-aq', '--no-trunc'))
    $rows = @{}
    foreach ($id in $ids) {
        $rows[$id] = ((Invoke-DockerTest @('inspect', '--type', 'container', '--format', '{{.Name}} {{.State.Status}} {{.State.StartedAt}}', $id)) -join '').Trim()
    }
    $listeners = @{}
    foreach ($port in $defaultPorts) {
        $listeners[$port] = (@(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | ForEach-Object OwningProcess | Sort-Object -Unique) -join ',')
    }
    return @{ containers = $rows; listeners = $listeners }
}
function Invoke-Launcher([string]$Action, [switch]$ExpectFailure, [string]$ExpectedError = '') {
    $arguments = @{ Action = $Action }
    if ($Action -eq 'Start') {
        $arguments.DatabasePassword = ConvertTo-SecureString -String $secret -AsPlainText -Force
        $arguments.ReadyTimeoutSeconds = $ReadyTimeoutSeconds
    }
    $lines = [Collections.Generic.List[string]]::new()
    $failure = $null
    try { & $launcher @arguments *>&1 | ForEach-Object { $lines.Add("$_"); Write-Host "    [launcher $Action] $_" } }
    catch { $failure = $_.Exception.Message; Write-Host "    [launcher $Action] refused: $failure" }
    if ($ExpectFailure) {
        Assert-True ([bool]$failure) "Launcher $Action unexpectedly succeeded."
        if ($ExpectedError) { Assert-True ($failure -match $ExpectedError) "Launcher $Action failed for an unexpected reason: $failure" }
    } elseif ($failure) { throw "Launcher $Action failed: $failure" }
    return ($lines -join "`n")
}
function Read-CopyState { return (Get-Content -LiteralPath (Join-Path $runtimeDir 'state.json') -Raw | ConvertFrom-Json -AsHashtable) }
# Any server line attributed to database kaipay (prefix %d), including failed
# authentication, except the Compose healthcheck's pg_isready pings. PostgreSQL 16
# logs %a as [unknown] at authorization, so the client is read from the message.
function Get-LauncherKaipayLines([string]$Container) {
    return @(Invoke-DockerTest @('logs', $Container) | Where-Object { $_ -cmatch '\sdb=kaipay\s' -and $_ -cnotmatch 'application_name=pg_isready\b' })
}
function Find-Line([string[]]$Lines, [string]$Pattern) {
    for ($i = 0; $i -lt $Lines.Count; $i++) { if ($Lines[$i] -cmatch $Pattern) { return $i } }
    return -1
}

$before = $null
try {
    # Preflight: local engine, distinct non-default free ports, required local images.
    $context = ((Invoke-DockerTest @('context', 'show')) -join '').Trim()
    $endpoint = ((Invoke-DockerTest @('context', 'inspect', $context, '--format', '{{.Endpoints.docker.Host}}')) -join '').Trim()
    if ($endpoint -notmatch '^npipe:/{4}\./pipe/(dockerDesktopLinuxEngine|docker_engine)$') { throw 'Refusing a nonlocal Docker context.' }
    $engineId = ((Invoke-DockerTest @('info', '--format', '{{.OSType}} {{.ID}}')) -join '').Trim()
    if ($engineId -notmatch '^linux (\S+)$') { throw 'Docker must be in Linux container mode.' }
    $engine = "$endpoint $($Matches[1])"
    $ports = @($PostgresPort, $KafkaPort, $BackendPort, $FrontendPort)
    Assert-True (@($ports | Sort-Object -Unique).Count -eq 4 -and !@($ports | Where-Object { $_ -in $defaultPorts -or $_ -lt 1024 -or $_ -gt 65535 }).Count) 'Ports must be distinct and non-default.'
    foreach ($port in $ports) {
        $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $port)
        try { $listener.ExclusiveAddressUse = $true; $listener.Start() } catch { throw "Port $port is not available." } finally { $listener.Stop() }
    }
    $pgImage = ((Invoke-DockerTest @('image', 'inspect', 'postgres:16-alpine', '--format', '{{.Id}}')) -join '').Trim()
    $kafkaImage = ((Invoke-DockerTest @('image', 'inspect', 'apache/kafka:3.8.0', '--format', '{{.Id}}')) -join '').Trim()
    Assert-True ($pgImage -cmatch '^sha256:[a-f0-9]{64}$' -and $kafkaImage -cmatch '^sha256:[a-f0-9]{64}$') 'Required local images are unavailable.'
    Assert-True (@(Invoke-DockerTest @('ps', '-aq', '--filter', "name=^/$name-")).Count -eq 0) 'Instance container names already exist.'
    $before = Get-Snapshot
    Write-Host "Instance $name; ports $($ports -join ',')"

    # Temporary copy: committed compose/backend/frontend sources plus the candidate.
    $null = New-Item -ItemType Directory -Path $copy, $runtimeDir -Force
    $archive = Join-Path $work 'head.tar'
    $null = & git -C $RepositoryRoot archive --format=tar -o $archive HEAD docker-compose.yml backend frontend
    if ($LASTEXITCODE -ne 0) { throw 'git archive failed.' }
    $null = & $tar -xf $archive -C $copy
    if ($LASTEXITCODE -ne 0) { throw 'Archive extraction failed.' }
    Copy-Item -Recurse -LiteralPath (Join-Path $candidate 'scripts') -Destination $copy
    Copy-Item -LiteralPath (Join-Path $candidate 'frontend/vite.config.ts') -Destination (Join-Path $copy 'frontend/vite.config.ts') -Force
    $null = New-Item -ItemType Directory -Path (Join-Path $copy 'backend/target') -Force
    $jarCopy = Join-Path $copy 'backend/target/KaiPay-0.0.1-SNAPSHOT.jar'
    Copy-Item -LiteralPath (Join-Path $RepositoryRoot 'backend/target/KaiPay-0.0.1-SNAPSHOT.jar') -Destination $jarCopy
    (Get-Item -LiteralPath $jarCopy).LastWriteTimeUtc = [DateTime]::UtcNow
    $null = & robocopy (Join-Path $RepositoryRoot 'frontend/node_modules') (Join-Path $copy 'frontend/node_modules') /E /NFL /NDL /NJH /NJS /NP /MT:16
    if ($LASTEXITCODE -ge 8) { throw 'Copying frontend dependencies failed.' }
    (Get-Item -LiteralPath (Join-Path $copy 'frontend/node_modules/.package-lock.json')).LastWriteTimeUtc = [DateTime]::UtcNow
    [IO.File]::WriteAllText((Join-Path $runtimeDir 'instance.json'),
        (ConvertTo-Json -Compress ([ordered]@{ name = $name; postgresPort = $PostgresPort; kafkaPort = $KafkaPort; backendPort = $BackendPort; frontendPort = $FrontendPort })))

    # Disposable PostgreSQL cluster, initialized once by the stock image in an
    # unnetworked container, then reopened only through the candidate's guard.
    $null = Invoke-DockerTest @('volume', 'create', '--label', "com.docker.compose.project=$name", '--label', 'com.docker.compose.volume=postgres_data', '--label', $fixtureLabel, $pgVolume)
    $created.volumes.Add($pgVolume)
    $null = Invoke-DockerTest @('volume', 'create', '--label', $fixtureLabel, $kafkaVolume)
    $created.volumes.Add($kafkaVolume)
    $env:POSTGRES_PASSWORD = $secret
    $created.init = ((Invoke-DockerTest @('run', '-d', '--pull', 'never', '--name', "$name-init", '--label', $fixtureLabel, '--network', 'none',
        '--volume', "${pgVolume}:/var/lib/postgresql/data", '--env', 'POSTGRES_USER=kaipay', '--env', 'POSTGRES_DB=kaipay', '--env', 'POSTGRES_PASSWORD', $pgImage)) -join '').Trim()
    $env:POSTGRES_PASSWORD = $previousPassword
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    do {
        Start-Sleep -Milliseconds 500
        $initDone = [bool](@(Invoke-DockerTest @('logs', $created.init)) -match 'PostgreSQL init process complete').Count
        & $docker exec $created.init pg_isready -U kaipay -d postgres *> $null
        $ready = $initDone -and $LASTEXITCODE -eq 0
    } while (!$ready -and [DateTime]::UtcNow -lt $deadline)
    Assert-True $ready 'Disposable PostgreSQL initialization did not complete.'
    $null = Invoke-Psql $created.init 'postgres' 'ALTER SYSTEM SET log_connections = on'
    $null = Invoke-Psql $created.init 'postgres' "ALTER SYSTEM SET log_line_prefix = '%m [%p] db=%d user=%u app=%a '"
    $null = Invoke-Psql $created.init 'postgres' 'CREATE DATABASE retained_fixture'
    $identity = (Invoke-Psql $created.init 'postgres' "SELECT system_identifier::text || '|' || (SELECT oid::text FROM pg_database WHERE datname='kaipay') || '|' || (SELECT oid::text FROM pg_database WHERE datname='retained_fixture') FROM pg_control_system()").Split('|')
    Assert-True ($identity.Count -eq 3 -and $identity[1] -cne $identity[2]) 'Unexpected disposable identities.'
    $null = Invoke-DockerTest @('stop', '--time', '20', $created.init)
    $null = Invoke-DockerTest @('rm', $created.init)
    $created.init = $null

    # Synthetic private inputs in the copy: protected pair is the retained fixture.
    $clusterId = [Convert]::ToBase64String([guid]::NewGuid().ToByteArray()).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    [IO.File]::WriteAllText((Join-Path $runtimeDir 'protected-identities.txt'), "$($identity[0])|$($identity[2])`n")
    [IO.File]::WriteAllText((Join-Path $runtimeDir 'target.json'), (ConvertTo-Json ([ordered]@{
        engine = $engine; postgresSystemId = $identity[0]; postgresDatabaseOid = $identity[1]; postgresVolume = $pgVolume; postgresImage = $pgImage
        kafkaImage = $kafkaImage; kafkaVolume = $kafkaVolume; kafkaClusterId = $clusterId; generation = $generation })))

    # Create the borrowed dependencies exactly as the launcher's Compose files resolve
    # them, with the overlay the launcher writes on any action (supported operator
    # path). Status before the dependencies exist must report them unverified.
    $instanceOverlay = Join-Path $runtimeDir 'instance.compose.yml'
    $preStatus = Invoke-Launcher 'Status'
    Assert-True ((Test-Path -LiteralPath $instanceOverlay -PathType Leaf) -and $preStatus -match 'Target identities unverified') 'Status did not write the instance overlay or did not report an unverified target.'
    Assert-True ([IO.File]::ReadAllText($instanceOverlay).Contains("container_name: $name-postgres")) 'Generated overlay does not name this instance.'
    $composeFiles = @('--file', (Join-Path $copy 'docker-compose.yml'), '--file', (Join-Path $copy 'scripts/kaipay-demo-kafka.compose.yml'),
        '--file', (Join-Path $copy 'scripts/kaipay-demo-postgres-guard.compose.yml'), '--file', $instanceOverlay)
    $saved = @{}
    $composeEnvironment = @{ COMPOSE_REMOVE_ORPHANS = 'false'; KAIPAY_DEMO_KAFKA_VOLUME = $kafkaVolume; KAIPAY_DEMO_KAFKA_CLUSTER_ID = $clusterId
        KAIPAY_EXPECTED_PG_SYSTEM_ID = $identity[0]; KAIPAY_DEMO_PG_GUARD_PATH = (Join-Path $copy 'scripts/kaipay-demo-postgres-guard.sh') }
    foreach ($key in $composeEnvironment.Keys) { $saved[$key] = [Environment]::GetEnvironmentVariable($key); [Environment]::SetEnvironmentVariable($key, $composeEnvironment[$key]) }
    try { $null = Invoke-DockerTest (@('compose', '--project-name', $name, '--project-directory', $copy) + $composeFiles + @('up', '-d', '--no-deps', '--pull', 'never', 'postgres', 'kafka')) }
    finally { foreach ($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key, $saved[$key]) } }
    $pg = "$name-postgres"; $kafka = "$name-kafka"
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    do {
        Start-Sleep -Seconds 1
        $pgHealthy = ((Invoke-DockerTest @('inspect', '--format', '{{.State.Health.Status}}', $pg)) -join '').Trim() -ceq 'healthy'
        & $docker exec $kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list *> $null
        $kafkaReady = $LASTEXITCODE -eq 0
    } while (!($pgHealthy -and $kafkaReady) -and [DateTime]::UtcNow -lt $deadline)
    Assert-True ($pgHealthy -and $kafkaReady) 'Disposable dependencies did not become ready.'
    Write-Host 'PASS disposable dependencies created through the guarded Compose files'

    # Positive: real Start of the isolated instance.
    $start = Invoke-Launcher 'Start'
    $state = Read-CopyState
    Assert-True ($state.apps.ContainsKey('backend') -and $state.apps.ContainsKey('frontend') -and $state.generation.phase -ceq 'established') 'Start did not record both apps and an established generation.'
    Assert-True ($start -match 'Observed dependency identities: .*\(matched\)' -and $start -match 'backend managed, ready' -and $start -match 'frontend managed, ready') 'Start did not report verified, ready apps.'
    $history = Invoke-Psql $pg 'kaipay' "SELECT string_agg(version, ',' ORDER BY installed_rank) || '|' || bool_and(success)::text FROM flyway_schema_history WHERE version IS NOT NULL"
    Assert-True ($history -ceq '1,2,3,4,5,6,7,8,9,10,11|true') "Unexpected Flyway history: $history"
    $topics = @(Invoke-DockerTest @('exec', $kafka, '/opt/kafka/bin/kafka-topics.sh', '--bootstrap-server', 'localhost:9092', '--list'))
    foreach ($topic in @("kaipay.myr.$generation", "kaipay.myr.$generation-retry-1000", "kaipay.myr.$generation-retry-2000", "kaipay.myr.$generation-dlt")) {
        Assert-True ($topic -cin $topics) "Missing generation topic $topic."
    }
    $attributed = Invoke-Psql $pg 'postgres' "SELECT count(*) FROM pg_stat_activity WHERE application_name='$($state.apps.backend.applicationName)' AND datname='kaipay'"
    Assert-True ([int]$attributed -gt 0) 'Isolated backend connection not attributed in the isolated cluster.'
    $logs = @(Invoke-DockerTest @('logs', $pg))
    if ($EvidenceDirectory) { [IO.File]::WriteAllLines((Join-Path $EvidenceDirectory 'postgres-after-start.log'), [string[]]$logs) }
    # Log-based assertions are meaningful only if the attribution prefix is active.
    Assert-True (@($logs | Where-Object { $_ -cmatch '\sdb=postgres user=kaipay .*connection authorized: user=kaipay database=postgres application_name=psql$' }).Count -gt 0) 'Fixture log_line_prefix/log_connections are not in effect.'
    Assert-True (!@($logs | Where-Object { $_ -match '\sdb=retained_fixture\s|database=retained_fixture' }).Count) 'The protected fixture database received a connection.'
    # Container-side psql: first maintenance connection precedes first kaipay connection.
    $firstCatalog = Find-Line $logs 'connection authorized: user=kaipay database=postgres application_name=psql$'
    $firstDirect = Find-Line $logs 'connection authorized: user=kaipay database=kaipay application_name=psql$'
    Assert-True ($firstCatalog -ge 0 -and $firstDirect -gt $firstCatalog) 'Container-side catalog stage did not precede the direct kaipay connection.'
    # Host JDBC (TCP; the driver's authorization line carries no application_name).
    $firstJdbcCatalog = Find-Line $logs 'connection authorized: user=kaipay database=postgres$'
    $firstJdbcDirect = Find-Line $logs 'connection authorized: user=kaipay database=kaipay$'
    Assert-True ($firstJdbcCatalog -ge 0 -and $firstJdbcDirect -gt $firstJdbcCatalog) 'JDBC catalog stage did not precede the direct kaipay connection.'
    Write-Host 'PASS isolated Start: identity stages ordered, Flyway V1-V11, generation established, backend attributed'

    # The launcher-spawned processes must not hold connections to any default port.
    foreach ($app in @('backend', 'frontend')) {
        $remote = @(Get-NetTCPConnection -OwningProcess ([int]$state.apps[$app].pid) -State Established -ErrorAction SilentlyContinue | ForEach-Object RemotePort | Sort-Object -Unique)
        Assert-True (!@($remote | Where-Object { $_ -in $defaultPorts }).Count) "Isolated $app is connected to a default port: $($remote -join ',')"
        if ($app -eq 'backend') { Assert-True ($PostgresPort -in $remote -and $KafkaPort -in $remote) "Isolated backend lacks connections to its own dependencies: $($remote -join ',')" }
    }
    Write-Host 'PASS isolated processes connect only to isolated ports'

    $status = Invoke-Launcher 'Status'
    Assert-True ($status -match '\(matched\)' -and $status -match 'backend managed, ready' -and $status -match "Frontend http://127\.0\.0\.1:$FrontendPort") 'Status did not verify the isolated instance.'
    Write-Host 'PASS isolated Status'

    # The dev-server proxy must reach the isolated backend: stop that backend while
    # the default backend keeps listening; the proxied call must then fail.
    $record = $state.apps.backend
    $backendProcess = Get-Process -Id ([int]$record.pid)
    Assert-True ("$($backendProcess.StartTime.ToUniversalTime().Ticks)" -eq $record.started -and $backendProcess.MainModule.FileName -eq $record.path) 'Recorded isolated backend does not match its process.'
    $backendProcess.Kill(); $null = $backendProcess.WaitForExit(15000); $backendProcess.Dispose()
    $defaultBackendListening = [bool](Get-NetTCPConnection -LocalPort 28080 -State Listen -ErrorAction SilentlyContinue)
    # Without a default backend, a proxy aimed at it would also fail: inconclusive.
    Assert-True $defaultBackendListening 'Proxy isolation is inconclusive: nothing listens on the default backend port.'
    $proxied = try { (Invoke-WebRequest -Uri "http://127.0.0.1:$FrontendPort/v1/auth/csrf" -NoProxy -TimeoutSec 5 -SkipHttpErrorCheck).StatusCode } catch { 0 }
    Assert-True ($proxied -ne 200) "Frontend proxy still answered 200 after the isolated backend stopped (default backend listening: $defaultBackendListening)."
    Write-Host "PASS frontend proxy targets the isolated backend (proxied status $proxied; default backend listening: $defaultBackendListening)"

    $stop = Invoke-Launcher 'Stop'
    $state = Read-CopyState
    Assert-True ($state.apps.Count -eq 0 -and $stop -match 'Cleared stale backend' -and $stop -match 'Stopped frontend') 'Stop did not clear the isolated apps.'
    Assert-True (((Invoke-DockerTest @('inspect', '--format', '{{.State.Status}}', $pg)) -join '').Trim() -ceq 'running') 'Stop stopped a borrowed dependency.'
    Write-Host 'PASS isolated Stop; borrowed dependencies left running'

    # Negative: the name kaipay now resolves to the protected database. Start must
    # refuse at the catalog stage, before any launcher client names kaipay.
    $renamed = $false
    for ($attempt = 0; $attempt -lt 20 -and !$renamed; $attempt++) {
        & $docker exec $pg psql -X -q -v ON_ERROR_STOP=1 -U kaipay -d postgres -c 'ALTER DATABASE kaipay RENAME TO kaipay_hold' *> $null
        $renamed = $LASTEXITCODE -eq 0
        if (!$renamed) { Start-Sleep -Milliseconds 300 }
    }
    Assert-True $renamed 'Could not rename the disposable active database.'
    $null = Invoke-Psql $pg 'postgres' 'ALTER DATABASE retained_fixture RENAME TO kaipay'
    $kaipayLinesBefore = @(Get-LauncherKaipayLines $pg).Count
    # Non-vacuity: the positive run's psql/JDBC/backend kaipay connections are counted.
    Assert-True ($kaipayLinesBefore -gt 0) 'The kaipay connection counter matched nothing; the refusal assertion would be vacuous.'
    $generationBefore = ConvertTo-Json -Compress (Read-CopyState).generation
    $catalogPattern = 'connection authorized: user=kaipay database=postgres application_name=psql$'
    $catalogBefore = @(Invoke-DockerTest @('logs', $pg) | Where-Object { $_ -cmatch $catalogPattern }).Count
    $null = Invoke-Launcher 'Start' -ExpectFailure -ExpectedError 'protected PostgreSQL identity'
    $catalogAfter = @(Invoke-DockerTest @('logs', $pg) | Where-Object { $_ -cmatch $catalogPattern }).Count
    # The refusal must come from the observed catalog identity, not the intended-target precheck.
    Assert-True ($catalogAfter -eq $catalogBefore + 1) "Refused Start made $($catalogAfter - $catalogBefore) catalog connections; expected exactly one."
    if ($EvidenceDirectory) { [IO.File]::WriteAllLines((Join-Path $EvidenceDirectory 'postgres-after-refusal.log'), [string[]]@(Invoke-DockerTest @('logs', $pg))) }
    $state = Read-CopyState
    Assert-True (@(Get-LauncherKaipayLines $pg).Count -eq $kaipayLinesBefore) 'A launcher client named the protected kaipay database before refusal.'
    Assert-True ($state.apps.Count -eq 0 -and (ConvertTo-Json -Compress $state.generation) -ceq $generationBefore) 'Refused Start changed app or generation state.'
    Write-Host 'PASS protected database renamed to kaipay refused at the catalog stage with no launcher connection to it'
} finally {
    $env:POSTGRES_PASSWORD = $previousPassword
    # Stop any isolated app still recorded in the copy, by exact recorded identity.
    try {
        if (Test-Path -LiteralPath (Join-Path $runtimeDir 'state.json')) {
            $leftover = Read-CopyState
            foreach ($app in @($leftover.apps.Values)) {
                $process = Get-Process -Id ([int]$app.pid) -ErrorAction SilentlyContinue
                if ($process -and "$($process.StartTime.ToUniversalTime().Ticks)" -eq $app.started) { $process.Kill($true) }
            }
        }
    } catch { Write-Warning "Could not clean up isolated apps. $($_.Exception.Message)" }
    foreach ($id in @(Invoke-DockerTest @('ps', '-aq', '--no-trunc', '--filter', "label=com.docker.compose.project=$name") -AllowFailure)) {
        try {
            $labels = ((Invoke-DockerTest @('inspect', '--format', '{{json .Config.Labels}}', $id)) -join '') | ConvertFrom-Json -AsHashtable
            if ($labels['com.docker.compose.project'] -cne $name -or $labels['com.docker.compose.project.working_dir'] -ne $copy) { throw 'labels do not match this run' }
            $null = Invoke-DockerTest @('rm', '-f', $id)
        } catch { Write-Warning "Refusing or failing to remove container $id. $($_.Exception.Message)" }
    }
    if ($created.init) {
        try {
            $label = ((Invoke-DockerTest @('inspect', '--format', '{{index .Config.Labels "kaipay.e2e.fixture"}}', $created.init)) -join '').Trim()
            if ($label -cne $tag) { throw 'label mismatch' }
            $null = Invoke-DockerTest @('rm', '-f', $created.init)
        } catch { Write-Warning "Could not remove init container. $($_.Exception.Message)" }
    }
    foreach ($network in @(Invoke-DockerTest @('network', 'ls', '-q', '--filter', "label=com.docker.compose.project=$name") -AllowFailure)) {
        try {
            $project = ((Invoke-DockerTest @('network', 'inspect', '--format', '{{index .Labels "com.docker.compose.project"}}', $network)) -join '').Trim()
            if ($project -cne $name) { throw 'label mismatch' }
            $null = Invoke-DockerTest @('network', 'rm', $network)
        } catch { Write-Warning "Could not remove network $network. $($_.Exception.Message)" }
    }
    foreach ($volume in $created.volumes) {
        try {
            $label = ((Invoke-DockerTest @('volume', 'inspect', '--format', '{{index .Labels "kaipay.e2e.fixture"}}', $volume)) -join '').Trim()
            if ($label -cne $tag) { throw 'label mismatch' }
            $null = Invoke-DockerTest @('volume', 'rm', $volume)
        } catch { Write-Warning "Could not remove volume $volume. $($_.Exception.Message)" }
    }
    if ($before) {
        $after = Get-Snapshot
        $changed = @($before.containers.Keys | Where-Object { $after.containers[$_] -cne $before.containers[$_] })
        $added = @($after.containers.Keys | Where-Object { !$before.containers.ContainsKey($_) })
        $listenerChanges = @($defaultPorts | Where-Object { $after.listeners[$_] -cne $before.listeners[$_] })
        if ($changed.Count -or $added.Count -or $listenerChanges.Count) {
            Write-Warning "Pre-existing state differs: changed=$($changed -join ',') added=$($added -join ',') listeners=$($listenerChanges -join ',')"
            $global:LASTEXITCODE = 1
            throw 'Pre-existing containers or default-port listeners changed during the rehearsal.'
        }
        Write-Host 'PASS pre-existing containers and default-port listeners unchanged; fixtures removed'
    }
    if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue }
}
