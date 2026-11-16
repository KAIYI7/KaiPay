# Run with PowerShell 7.4+: pwsh -NoProfile -File tests/demo-launcher-policy.Tests.ps1
# No Docker, network, database, service, or real installation identifiers are used.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$launcher = Join-Path $root 'scripts/KaiPay-Demo.ps1'
$source = [IO.File]::ReadAllText($launcher)
$tokens = $null; $errors = $null
$ast = [Management.Automation.Language.Parser]::ParseInput($source, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw "Launcher parse failed: $errors" }
function Import-Function([string]$Name) {
    $matches = @($ast.FindAll({ param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $Name }, $true))
    if ($matches.Count -ne 1) { throw "Expected exactly one function: $Name" }
    # Define in script scope, without executing the launcher's top-level entrypoint.
    $definition = $matches[0].Extent.Text -replace '^function\s+', 'function script:'
    Invoke-Expression $definition
}
function Assert-True([bool]$Condition, [string]$Message) { if (!$Condition) { throw $Message } }
function Assert-Throws([scriptblock]$Block, [string]$Label, [string]$Expected = '') {
    $threw = $false
    try { & $Block | Out-Null } catch {
        $threw = $true
        if ($Expected -and $_.Exception.Message -notmatch $Expected) { throw "Unexpected error for ${Label}: $($_.Exception.Message)" }
    }
    if (!$threw) { throw "Expected refusal: $Label" }
}
foreach ($name in @('Read-ProtectedIdentities', 'Assert-UnprotectedIdentity', 'Assert-DatabaseIdentity', 'Assert-Targets',
    'Assert-Generation', 'Assert-EstablishedGeneration', 'Get-GenerationTopics',
    'Get-GenerationTopicIds', 'Test-RecordedTarget', 'Stop-Runtime', 'Show-Status', 'Start-Runtime')) {
    Import-Function $name
}
$instance = [ordered]@{ name = 'kaipay'; postgresPort = 25432; kafkaPort = 29092; backendPort = 28080; frontendPort = 28081 }
$isolatedInstance = $false
$tmp = Join-Path ([IO.Path]::GetTempPath()) ('kaipay-policy-test-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $tmp
$protectedPath = Join-Path $tmp 'protected-identities.txt'
try {
    Assert-Throws { Read-ProtectedIdentities } 'missing policy' 'policy is missing or unreadable'
    $protectedPath = $tmp
    Assert-Throws { Read-ProtectedIdentities } 'unreadable policy path' 'policy is missing or unreadable'
    $protectedPath = Join-Path $tmp 'protected-identities.txt'
    foreach ($invalid in @('', "`n", 'broken', '123456789012|', '123456789012|01',
        "123456789012|42`n123456789012|42", "123456789012|42`n`n123456789012|43", '123456789012|42|extra',
        '18446744073709551616|42', '123456789012|4294967296')) {
        [IO.File]::WriteAllText($protectedPath, $invalid)
        Assert-Throws { Read-ProtectedIdentities } "invalid policy: $invalid" 'identity policy'
    }
    [IO.File]::WriteAllBytes($protectedPath, [byte[]](0xff, 0xff))
    Assert-Throws { Read-ProtectedIdentities } 'invalid UTF-8' 'policy is missing or unreadable'
    [IO.File]::WriteAllText($protectedPath, '123456789012|42')
    $policy = Read-ProtectedIdentities
    Assert-Throws { Assert-UnprotectedIdentity '123456789012' '42' $policy } 'protected identity' 'protected PostgreSQL identity'
    Assert-UnprotectedIdentity '123456789012' '43' $policy
    Assert-Throws { Assert-UnprotectedIdentity '123456789012' '00042' $policy } 'alternate numeric spelling' 'protected PostgreSQL identity'
    Write-Host 'PASS policy refusal and same-cluster permitted database'

    # Exercise actual Start entrypoint with fakes that fail if execution reaches any
    # tool, build, reservation or process launch before policy denial.
    $script:sideEffects = 0
    function Read-Target { return @{ postgresSystemId = '123456789012'; postgresDatabaseOid = '42' } }
    function Get-Jdk { $script:sideEffects++; throw 'unexpected JDK access' }
    function Connect-Docker { $script:sideEffects++; throw 'unexpected Docker access' }
    function Invoke-Build { $script:sideEffects++; throw 'unexpected build' }
    function Start-App { $script:sideEffects++; throw 'unexpected start' }
    try { Start-Runtime; throw 'Start unexpectedly succeeded' }
    catch { Assert-True ($_.Exception.Message -match 'protected PostgreSQL identity') 'Start failed for a reason other than the protected identity.' }
    Assert-True ($script:sideEffects -eq 0) 'Protected Start reached a side effect.'
    Remove-Item -LiteralPath $protectedPath
    try { Start-Runtime; throw 'Start unexpectedly succeeded' }
    catch { Assert-True ($_.Exception.Message -match 'policy is missing or unreadable') 'Start failed for a reason other than missing policy.' }
    Assert-True ($script:sideEffects -eq 0) 'Missing-policy Start reached a side effect.'
    Write-Host 'PASS early Start refusal'

    # The observed ID is obtained independently from the expected manifest. Fake
    # only container metadata and the psql output, and assert refusal happens
    # before a second query can inspect application tables.
    [IO.File]::WriteAllText($protectedPath, '123456789012|42')
    $engine = 'fixture-engine'
    $dependencies = [ordered]@{ postgres = @{}; kafka = @{} }
    $observedTarget = @{ engine = $engine; postgresSystemId = '123456789012'; postgresDatabaseOid = '43';
        kafkaVolume = 'fixture-volume'; kafkaClusterId = 'fixture-cluster' }
    function Get-Container([string]$Name) { return @{ state = 'running'; id = $Name } }
    function Assert-ComposeContainer { }
    function Assert-MountedTarget { }
    function Test-DependencyReady { return $true }
    $script:queries = 0
    $script:firstQuery = $null
    $script:databaseResult = 'kaipay|123456789012|42'
    function Invoke-Docker([string[]]$Arguments, [int]$Timeout = 10) {
        $script:queries++
        if ($script:queries -gt 1) { throw 'Unexpected query after observed mismatch' }
        $script:firstQuery = $Arguments
        return $script:databaseResult
    }
    # The first container-side query must use the maintenance database, never kaipay.
    function Assert-MaintenanceFirst([string]$Label) {
        $arguments = @($script:firstQuery)
        $databaseIndex = [Array]::IndexOf($arguments, '-d')
        Assert-True ($databaseIndex -ge 0 -and $arguments[$databaseIndex + 1] -ceq 'postgres' -and
            @($arguments | Where-Object { $_ -ceq '-d' }).Count -eq 1 -and $arguments[-1] -like "*current_database()='postgres'*") "$Label did not query the maintenance database first."
    }
    try { Assert-Targets $observedTarget; throw 'Expected protected observed identity refusal' }
    catch { Assert-True ($_.Exception.Message -match 'protected PostgreSQL identity') 'Unexpected error for protected observed identity.' }
    Assert-True ($script:queries -eq 1) 'Protected observed identity did not stop before schema query.'
    Assert-MaintenanceFirst 'Protected observed identity'
    $script:queries = 0; $script:databaseResult = 'kaipay|123456789012|44'
    try { Assert-Targets $observedTarget; throw 'Expected mismatched observed identity refusal' }
    catch { Assert-True ($_.Exception.Message -match 'identity mismatch') 'Unexpected error for observed mismatch.' }
    Assert-True ($script:queries -eq 1) 'Observed mismatch did not stop before schema query.'
    Assert-MaintenanceFirst 'Observed mismatch'
    Write-Host 'PASS independently observed protected identity and mismatch refusal, maintenance database first'

    # Exercise real mount validation against a synthetic container; no filesystem
    # or container operations are needed for Kafka volume/image comparison.
    Import-Function 'Assert-MountedTarget'
    $mountTarget = @{ kafkaVolume = 'wanted'; kafkaImage = 'sha256:fake' }
    $syntheticContainer = @{ image = 'sha256:fake'; mounts = @(@{ Destination = '/var/lib/kafka/data'; Type = 'volume'; Name = 'other'; RW = $true }) }
    Assert-Throws { Assert-MountedTarget 'kafka' $syntheticContainer $mountTarget } 'wrong Kafka volume' 'persistent data mount'
    $syntheticContainer.mounts[0].Name = 'wanted'
    Assert-MountedTarget 'kafka' $syntheticContainer $mountTarget
    $syntheticContainer.image = 'sha256:other'
    Assert-Throws { Assert-MountedTarget 'kafka' $syntheticContainer $mountTarget } 'wrong Kafka image' 'persistent data mount'
    # Stub mount checking again to reach the independent broker cluster check.
    function Assert-MountedTarget { }
    $script:queries = 0
    function Invoke-Docker([string[]]$Arguments, [int]$Timeout = 10) {
        $script:queries++
        if ($Arguments[-1] -like '*pg_control_system*') { return 'kaipay|123456789012|43' }
        if ($Arguments[-1] -like '*pg_class*') { return '0' }
        if ($Arguments[2] -like '*kafka-metadata-quorum.sh') { return 'ClusterId: unexpectedcluster' }
        throw 'Unexpected query after Kafka cluster mismatch'
    }
    try { Assert-Targets $observedTarget; throw 'Expected Kafka cluster mismatch refusal' }
    catch { Assert-True ($_.Exception.Message -match 'Kafka cluster identity mismatch') 'Unexpected error for Kafka cluster mismatch.' }
    Assert-True ($script:queries -eq 4) 'Kafka mismatch did not stop at metadata.'
    Write-Host 'PASS synthetic Kafka volume, image and cluster checks'

    Remove-Item -LiteralPath $protectedPath
    $apps = [ordered]@{}; $dependencies = [ordered]@{}
    $state = @{ apps = @{}; containers = @{}; engine = $null }
    $engine = $null
    # Status reports unverified even when policy is missing. In the real entrypoint
    # Docker discovery happens first; fake it here, never touching Docker.
    function Connect-Docker { $script:engine = 'fixture-engine' }
    function Read-Target { return $observedTarget }
    $webUrl = 'fixture'; $apiUrl = 'fixture'
    $statusOutput = (Show-Status 3>&1 | Out-String)
    Assert-True ($statusOutput -match 'Target identities unverified') 'Status did not report an unverified target.'
    Write-Host 'PASS Status reports unverified without policy'

    # Generation tests use fake broker responses and in-memory state only.
    $target = @{ generation = 'fixture'; engine = 'fixture' }
    $container = @{ id = 'fake-broker' }
    $state = @{}
    $script:saveCount = 0
    $script:fakeTopics = ''
    $script:fakeGroups = ''
    $script:fakeTopicId = 'synthetic-topic-id'
    function Save-State { $script:saveCount++ }
    function Invoke-Docker([string[]]$Arguments, [int]$Timeout = 10) {
        if ($Arguments -contains 'kafka-topics.sh') { throw 'Unexpected executable spelling' }
        if ($Arguments[2] -like '*kafka-consumer-groups.sh') { return $script:fakeGroups }
        if ($Arguments[2] -like '*kafka-topics.sh') {
            if ($Arguments -contains '--list') { return $script:fakeTopics }
            if ($Arguments -contains '--describe') { return "Topic: $($Arguments[-1]) TopicId: $script:fakeTopicId PartitionCount: 1" }
        }
        throw 'Unexpected Docker call in isolated test'
    }
    $fakeTopics = 'kaipay.myr.fixture'
    Assert-Throws { Assert-Generation $target $container } 'existing topic without reservation' 'already exists'
    Assert-True (!$state.ContainsKey('generation')) 'Collision created reservation.'
    $fakeTopics = ''; $fakeGroups = 'kaipay-myr-fixture'
    Assert-Throws { Assert-Generation $target $container } 'existing group without reservation' 'already exists'
    $fakeGroups = ''
    Assert-Generation $target $container
    Assert-True ($state.generation.phase -ceq 'pending' -and $saveCount -eq 1) 'Reservation was not durably marked pending.'
    Assert-Throws { Assert-Generation $target $container } 'pending reservation retry' 'pending or differs'
    Assert-True ($state.generation.phase -ceq 'pending') 'Pending state changed after refusal.'
    $state.generation.phase = 'established'
    $state.generation.topics = @{ 'kaipay.myr.fixture' = 'original-id' }
    $fakeTopics = 'kaipay.myr.fixture'
    Assert-Throws { Assert-Generation $target $container } 'changed topic identity' 'topic identity differs'
    Assert-True ($state.generation.phase -ceq 'established') 'Failure changed established state.'
    Write-Host 'PASS Kafka collision, pending and changed-topic refusal'

    # Stop does not load policy and only stops tracked, matching owned resources.
    $state = @{ apps = @{ frontend = @{ pid = 1 }; backend = @{ pid = 2 } }; containers = @{}; engine = $null }
    $script:killed = $false; $script:disposed = $false
    $owned = [pscustomobject]@{ Id = 1; HasExited = $false }
    $owned | Add-Member ScriptMethod Kill { $script:killed = $true }
    $owned | Add-Member ScriptMethod WaitForExit { param($timeout) return $true }
    $owned | Add-Member ScriptMethod Dispose { $script:disposed = $true }
    function Get-OwnedProcess([string]$Name) { if ($Name -ceq 'frontend') { return $owned }; return $null }
    Stop-Runtime @('frontend', 'backend') @('kafka', 'postgres')
    Assert-True ($killed -and $disposed -and $state.apps.Count -eq 0) 'Stop failed owned process or stale-record handling.'
    $engine = 'fixture-engine'; $state.engine = $engine
    $state.containers = @{ kafka = @{ id = ('a' * 64); started = 'same' }; postgres = @{ id = ('b' * 64); started = 'old' } }
    $script:dockerStops = [Collections.Generic.List[string]]::new()
    function Get-Container([string]$Name, [string]$Id) {
        return @{ id = $Id; name = "/kaipay-$Name"; state = 'running'; started = $(if ($Name -ceq 'kafka') { 'same' } else { 'different' }) }
    }
    function Invoke-Docker([string[]]$Arguments, [int]$Timeout = 10) {
        if ($Arguments[0] -cne 'stop') { throw 'Unexpected Docker action during Stop' }
        $script:dockerStops.Add($Arguments[-1])
    }
    Stop-Runtime @() @('kafka', 'postgres')
    Assert-True ($dockerStops.Count -eq 1 -and $dockerStops[0] -ceq ('a' * 64)) 'Stop did not restrict container shutdown to matching ownership.'
    Write-Host 'PASS Stop ownership without policy; changed/unowned resources untouched'

    # Instance seam: no instance file must reproduce the historical literal defaults
    # exactly; an isolated instance must be complete, distinct and non-default.
    foreach ($name in @('Read-Instance', 'Write-InstanceOverlay', 'Invoke-Compose', 'Assert-ComposeContainer')) { Import-Function $name }
    $instancePath = Join-Path $tmp 'instance.json'
    $instanceOverlay = Join-Path $tmp 'instance.compose.yml'
    $instance = Read-Instance
    Assert-True ((ConvertTo-Json $instance -Compress) -ceq '{"name":"kaipay","postgresPort":25432,"kafkaPort":29092,"backendPort":28080,"frontendPort":28081}') 'Default instance changed.'
    $topLevel = @($ast.FindAll({ param($n) $n -is [Management.Automation.Language.AssignmentStatementAst] -and
        $n.Parent -is [Management.Automation.Language.NamedBlockAst] -and $n.Parent.Parent -eq $ast -and
        $n.Left.Extent.Text -in @('$apiUrl', '$webUrl', '$dependencies', '$apps', '$isolatedInstance') }, $false))
    Assert-True ($topLevel.Count -eq 5) 'Expected the five derived top-level instance assignments.'
    foreach ($assignment in $topLevel) { Invoke-Expression $assignment.Extent.Text }
    Assert-True (!$isolatedInstance -and $apiUrl -ceq 'http://127.0.0.1:28080' -and $webUrl -ceq 'http://127.0.0.1:28081' -and
        $dependencies.postgres.Port -eq 25432 -and $dependencies.kafka.Port -eq 29092 -and $dependencies.postgres.ContainerPort -ceq '5432/tcp' -and
        $dependencies.kafka.ContainerPort -ceq '9094/tcp' -and $apps.backend -eq 28080 -and $apps.frontend -eq 28081 -and
        (@($dependencies.Keys) -join ',') -ceq 'postgres,kafka' -and (@($apps.Keys) -join ',') -ceq 'backend,frontend') 'Default derived values differ from the historical literals.'
    $valid = '{"name":"kpe2etest","postgresPort":45432,"kafkaPort":49092,"backendPort":48080,"frontendPort":48081}'
    foreach ($invalid in @('not json', '[]', '{"name":"kaipay","postgresPort":45432,"kafkaPort":49092,"backendPort":48080,"frontendPort":48081}',
        '{"name":"KPE2E","postgresPort":45432,"kafkaPort":49092,"backendPort":48080,"frontendPort":48081}',
        '{"name":"kp-e2e","postgresPort":45432,"kafkaPort":49092,"backendPort":48080,"frontendPort":48081}',
        '{"name":"kpe2etest","postgresPort":25432,"kafkaPort":49092,"backendPort":48080,"frontendPort":48081}',
        '{"name":"kpe2etest","postgresPort":45432,"kafkaPort":45432,"backendPort":48080,"frontendPort":48081}',
        '{"name":"kpe2etest","postgresPort":45432,"kafkaPort":28048,"backendPort":48080,"frontendPort":48081}',
        '{"name":"kpe2etest","postgresPort":"45432","kafkaPort":49092,"backendPort":48080,"frontendPort":48081}',
        '{"name":"kpe2etest","postgresPort":80,"kafkaPort":49092,"backendPort":48080,"frontendPort":48081}',
        '{"name":"kpe2etest","postgresPort":45432,"kafkaPort":49092,"backendPort":48080}',
        '{"name":"kpe2etest","postgresPort":45432,"kafkaPort":49092,"backendPort":48080,"frontendPort":48081,"extra":1}',
        '{"name":"kaipay\n","postgresPort":45432,"kafkaPort":49092,"backendPort":48080,"frontendPort":48081}',
        '{"name":"abc\n","postgresPort":45432,"kafkaPort":49092,"backendPort":48080,"frontendPort":48081}',
        '[{"name":"kpe2etest","postgresPort":45432,"kafkaPort":49092,"backendPort":48080,"frontendPort":48081}]',
        '{"name":"kpe2etest","postgresPort":45432.0,"kafkaPort":49092,"backendPort":48080,"frontendPort":48081}',
        '{"name":"kpe2etest","postgresPort":-45432,"kafkaPort":49092,"backendPort":48080,"frontendPort":48081}')) {
        [IO.File]::WriteAllText($instancePath, $invalid)
        Assert-Throws { Read-Instance } "invalid instance: $invalid" 'Instance file'
    }
    [IO.File]::WriteAllText($instancePath, $valid)
    $isolated = Read-Instance
    Assert-True ((ConvertTo-Json $isolated -Compress) -ceq $valid) 'Valid isolated instance was not read exactly.'

    # Compose: the default path adds no overlay and keeps project kaipay; an isolated
    # instance adds exactly its generated overlay and uses its own project name.
    $composePath = Join-Path $tmp 'docker-compose.yml'; $composeOverride = Join-Path $tmp 'dependencies.compose.yml'
    $kafkaOverlay = Join-Path $root 'scripts/kaipay-demo-kafka.compose.yml'
    $postgresOverlay = Join-Path $root 'scripts/kaipay-demo-postgres-guard.compose.yml'
    $postgresGuard = Join-Path $root 'scripts/kaipay-demo-postgres-guard.sh'
    $composeTargetVolume = 'fixture-volume'; $composeKafkaClusterId = 'fixture-cluster'; $composePostgresSystemId = '123456789012'
    $script:composeCalls = [Collections.Generic.List[object]]::new()
    function Invoke-Docker([string[]]$Arguments, [int]$Timeout = 10) { $script:composeCalls.Add($Arguments); return "postgres fixturehash" }
    Remove-Item -LiteralPath $instancePath
    $instance = Read-Instance; $isolatedInstance = $false
    $null = Invoke-Compose @('config', '--hash', 'postgres')
    $defaultCall = @($composeCalls[-1])
    Assert-True (($defaultCall -join ' ') -ceq "compose --project-name kaipay --project-directory $root --file $composePath --file $kafkaOverlay --file $postgresOverlay config --hash postgres") 'Default Compose invocation changed.'
    Assert-True (!(Test-Path -LiteralPath $instanceOverlay)) 'Default mode generated an instance overlay.'
    $defaultContainer = @{ id = 'fixture'; name = '/kaipay-postgres'; ports = @{ '5432/tcp' = @(@{ HostIp = '127.0.0.1'; HostPort = '25432' }) }
        labels = @{ 'com.docker.compose.project' = 'kaipay'; 'com.docker.compose.service' = 'postgres'; 'com.docker.compose.project.working_dir' = $root
            'com.docker.compose.project.config_files' = "$composePath,$kafkaOverlay,$postgresOverlay,$composeOverride"; 'com.docker.compose.config-hash' = 'fixturehash' } }
    Assert-ComposeContainer 'postgres' $defaultContainer
    [IO.File]::WriteAllText($instancePath, $valid)
    $instance = Read-Instance; $isolatedInstance = $true
    $dependencies = [ordered]@{ postgres = @{ Port = $instance.postgresPort; ContainerPort = '5432/tcp' }; kafka = @{ Port = $instance.kafkaPort; ContainerPort = '9094/tcp' } }
    Assert-Throws { Assert-ComposeContainer 'postgres' $defaultContainer } 'default container under isolated instance' 'conflicts with kpe2etest-postgres'
    $null = Invoke-Compose @('config', '--hash', 'postgres')
    Assert-True ((@($composeCalls[-1]) -join ' ') -ceq "compose --project-name kpe2etest --project-directory $root --file $composePath --file $kafkaOverlay --file $postgresOverlay --file $instanceOverlay config --hash postgres") 'Isolated Compose invocation is not exactly scoped.'
    $overlayText = [IO.File]::ReadAllText($instanceOverlay)
    foreach ($expected in @('container_name: kpe2etest-postgres', '"127.0.0.1:45432:5432"', 'container_name: kpe2etest-kafka', '"127.0.0.1:49092:9094"',
        'EXTERNAL://localhost:49092', 'container_name: kpe2etest-kafka-ui', 'ports: !reset []')) {
        Assert-True ($overlayText.Contains($expected)) "Instance overlay lacks $expected."
    }
    Assert-True ($overlayText -notmatch '25432|29092|28080|28081|28048') 'Instance overlay references a default port.'
    $isolatedContainer = @{ id = 'fixture'; name = '/kpe2etest-postgres'; ports = @{ '5432/tcp' = @(@{ HostIp = '127.0.0.1'; HostPort = '45432' }) }
        labels = @{ 'com.docker.compose.project' = 'kpe2etest'; 'com.docker.compose.service' = 'postgres'; 'com.docker.compose.project.working_dir' = $root
            'com.docker.compose.project.config_files' = "$composePath,$kafkaOverlay,$postgresOverlay,$instanceOverlay"; 'com.docker.compose.config-hash' = 'fixturehash' } }
    Assert-ComposeContainer 'postgres' $isolatedContainer
    $isolatedContainer.labels['com.docker.compose.project.config_files'] = "$composePath,$kafkaOverlay,$postgresOverlay"
    Assert-Throws { Assert-ComposeContainer 'postgres' $isolatedContainer } 'isolated container without its overlay' 'conflicts with kpe2etest-postgres'
    $viteConfig = [IO.File]::ReadAllText((Join-Path $root 'frontend/vite.config.ts'))
    Assert-True ($viteConfig -match "KAIPAY_DEV_API_PORT \?\? '28080'" -and $viteConfig -match 'target: `http://localhost:\$\{apiPort\}`') 'Frontend proxy default or override changed.'
    $launcherText = $source
    Assert-True ($launcherText -match "if \(\`$Name -eq 'frontend' -and \`$isolatedInstance\) \{ \`$environment\['KAIPAY_DEV_API_PORT'\]") 'Frontend proxy override is not limited to isolated instances.'
    Assert-True ($launcherText -match "if \(\`$Name -eq 'frontend'\) \{ \`$environment\['NO_COLOR'\] = '1'; \`$environment\['FORCE_COLOR'\] = \`$null \}") 'Frontend is not started without colour; readiness would miss the coloured URL.'
    # No runtime path may keep a default name or port literal; all derive from $instance.
    $readInstance = @($ast.FindAll({ param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Read-Instance' }, $true))[0].Extent
    $outside = $source.Remove($readInstance.StartOffset, $readInstance.EndOffset - $readInstance.StartOffset)
    $literals = @([regex]::Matches($outside, '25432|29092|28080|28081|28048|kaipay-(?!demo\b|myr-)|kaipay_') | ForEach-Object Value)
    Assert-True ($literals.Count -eq 0) "Default name or port literal outside Read-Instance: $($literals -join ',')"
    # Isolated mode: a data volume shared with any other container is refused.
    $isolatedInstance = $true
    [IO.File]::WriteAllText($protectedPath, '123456789012|42')
    $observedTarget.postgresVolume = 'isolated-pg'
    function Get-Container([string]$Name) { return @{ state = 'running'; id = "id-$Name" } }
    function Assert-ComposeContainer { }
    function Assert-MountedTarget { }
    $script:volumeUsers = @('id-postgres', 'id-live')
    function Invoke-Docker([string[]]$Arguments, [int]$Timeout = 10) {
        if ($Arguments[0] -ceq 'ps' -and $Arguments -contains 'volume=isolated-pg') { return $script:volumeUsers }
        if ($Arguments[0] -ceq 'ps' -and $Arguments -contains 'volume=fixture-volume') { return 'id-kafka' }
        throw 'Unexpected query before volume exclusivity check'
    }
    Assert-Throws { Assert-Targets $observedTarget } 'shared isolated data volume' 'also used by another container'
    $script:volumeUsers = @('id-live')
    Assert-Throws { Assert-Targets $observedTarget } 'volume used only by another container' 'also used by another container'
    # Invoke-Native returns one trimmed string; several IDs arrive newline-joined.
    $script:volumeUsers = "id-postgres`nid-live"
    Assert-Throws { Assert-Targets $observedTarget } 'newline-joined shared volume users' 'also used by another container'
    # Exclusive use passes the volume check and proceeds to the catalog query.
    $script:volumeUsers = 'id-postgres'
    $script:catalogReached = $false
    function Invoke-Docker([string[]]$Arguments, [int]$Timeout = 10) {
        if ($Arguments[0] -ceq 'ps' -and $Arguments -contains 'volume=isolated-pg') { return $script:volumeUsers }
        if ($Arguments[0] -ceq 'ps' -and $Arguments -contains 'volume=fixture-volume') { return 'id-kafka' }
        if ($Arguments[-1] -like "*current_database()='postgres'*") { $script:catalogReached = $true; throw 'stop after catalog' }
        throw 'Unexpected query'
    }
    Assert-Throws { Assert-Targets $observedTarget } 'exclusive volumes reach catalog' 'stop after catalog'
    Assert-True $script:catalogReached 'Exclusive isolated volumes did not reach the catalog query.'
    $isolatedInstance = $false
    Write-Host 'PASS instance seam defaults, validation, Compose scope, overlay, no default literals, volume exclusivity'

    # Static checks identify ordering and guard regressions; they do not simulate
    # a real broker or PostgreSQL and should be supplemented with integration evidence.
    $start = @($ast.FindAll({ param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Start-Runtime' }, $true))[0].Extent.Text
    $hostCheck = $start.IndexOf('Assert-HostPostgresTarget')
    $reservation = $start.IndexOf('Assert-Generation')
    $appStart = $start.IndexOf('Start-App')
    $build = $start.IndexOf('Invoke-Build')
    Assert-True ($hostCheck -ge 0 -and $build -gt $hostCheck -and $reservation -gt $hostCheck -and $appStart -gt $reservation) 'Host JDBC verification must precede build, reservation and backend startup.'
    $pgGuard = [IO.File]::ReadAllText((Join-Path $root 'scripts/kaipay-demo-postgres-guard.sh'))
    Assert-True ($pgGuard -match 'pg_controldata' -and $pgGuard -match 'PG_VERSION' -and $pgGuard -match 'exec su-exec postgres postgres -D' -and $pgGuard -notmatch '(?m)^\s*initdb\b') 'PostgreSQL guard lost existing-cluster-only safeguards.'
    $kafkaOverlay = [IO.File]::ReadAllText((Join-Path $root 'scripts/kaipay-demo-kafka.compose.yml'))
    Assert-True ($kafkaOverlay -match 'external:\s*true' -and $kafkaOverlay -match 'KAIPAY_DEMO_KAFKA_VOLUME:\?' -and $kafkaOverlay -match 'KAIPAY_DEMO_KAFKA_CLUSTER_ID:\?') 'Kafka overlay lost required volume or cluster identity.'
    Write-Host 'PASS host verification order and static PostgreSQL/Kafka safeguards'
} finally {
    Remove-Item -LiteralPath $tmp -Recurse -Force
}
