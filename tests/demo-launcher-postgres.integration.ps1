# Disposable PostgreSQL integration test. This test creates and deletes only its
# uniquely labelled temporary containers; it never uses an existing volume.
# Run only against a locally controlled Docker engine.
#   pwsh -NoProfile -File tests/demo-launcher-postgres.integration.ps1 [-RepositoryRoot <checkout>] [-JdkHome <jdk21>]
# RepositoryRoot supplies the existing backend artifact (for its JDBC driver).
param(
    [string]$RepositoryRoot,
    [string]$JdkHome
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$root = Split-Path -Parent $PSScriptRoot
$source = [IO.File]::ReadAllText((Join-Path $root 'scripts/KaiPay-Demo.ps1'))
$tokens = $null; $errors = $null
$ast = [Management.Automation.Language.Parser]::ParseInput($source, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw 'Candidate launcher does not parse.' }
foreach ($name in @('Read-ProtectedIdentities', 'Assert-UnprotectedIdentity', 'Assert-DatabaseIdentity', 'Assert-HostPostgresTarget')) {
    $found = @($ast.FindAll({ param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $name }, $true))
    if ($found.Count -ne 1) { throw "Missing unique function: $name" }
    Invoke-Expression ($found[0].Extent.Text -replace '^function\s+', 'function script:')
}
$docker = (Get-Command docker.exe -CommandType Application).Source
$jdkCandidates = if ($JdkHome) { @($JdkHome) } else {
    @(Get-ChildItem -LiteralPath (Join-Path $env:ProgramFiles 'Java'), (Join-Path $env:ProgramFiles 'Eclipse Adoptium') -Directory -ErrorAction SilentlyContinue |
        Where-Object Name -match '^jdk-?21' | ForEach-Object FullName)
}
$java = @($jdkCandidates | ForEach-Object { Join-Path $_ 'bin/java.exe' } | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
    Where-Object { (& $_ --version 2>&1 | Select-Object -First 1) -match '^(?:openjdk|java) 21(?:[.\s+\-]|$)' }) | Select-Object -First 1
if (!$java) { throw 'JDK 21 is required for the disposable test; pass -JdkHome.' }
if (!$RepositoryRoot) { $RepositoryRoot = $root }
if (!(Test-Path -LiteralPath (Join-Path $RepositoryRoot 'backend/pom.xml') -PathType Leaf)) { throw 'Cannot locate the KaiPay checkout; pass -RepositoryRoot.' }
$backend = Join-Path $RepositoryRoot 'backend'
$jar = 'target/KaiPay-0.0.1-SNAPSHOT.jar'
if (!(Test-Path -LiteralPath (Join-Path $backend $jar))) { throw 'The existing executable application artifact is required for its JDBC driver.' }
$runtime = Join-Path ([IO.Path]::GetTempPath()) ('kaipay-jdbc-test-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $runtime
$protectedPath = Join-Path $runtime 'policy.txt'
$tag = [guid]::NewGuid().ToString('N')
$containers = [Collections.Generic.List[string]]::new()
$networkId = $null
$previousPassword = $env:POSTGRES_PASSWORD
$secret = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
$env:POSTGRES_PASSWORD = $secret
function Invoke-DockerTest([string[]]$Arguments) {
    $output = & $docker @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Disposable Docker command failed: $($Arguments[0])" }
    return @($output | ForEach-Object { "$_" })
}
function Invoke-Sql([string]$Id, [string]$Sql) {
    return ((Invoke-DockerTest @('exec', $Id, 'psql', '-X', '-q', '-A', '-t', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'postgres', '-c', $Sql)) -join "`n").Trim()
}
function Get-AppConnections([string]$Id) {
    $lines = @(Invoke-DockerTest @('logs', $Id))
    return @($lines | Where-Object { $_ -match 'connection authorized:.*database=kaipay(?:\s|$)' }).Count
}
function Get-MaintenanceConnections([string]$Id) {
    $lines = @(Invoke-DockerTest @('logs', $Id))
    return @($lines | Where-Object { $_ -match 'connection authorized:.*database=postgres(?:\s|$)' }).Count
}
# Any server log line attributed to database kaipay, including failed authentication
# after the startup packet named it. The fixture sets log_line_prefix with %d.
function Get-AppAttempts([string]$Id) {
    $lines = @(Invoke-DockerTest @('logs', $Id))
    return @($lines | Where-Object { $_ -cmatch '\sdb=kaipay\s' }).Count
}
function Assert-True([bool]$Condition, [string]$Message) { if (!$Condition) { throw $Message } }
function Invoke-Probe([string]$Id, [int]$Port, $Target, [bool]$ShouldPass, [string]$Label, [string]$Password = $secret, [bool]$ExpectCatalog = $true, [string]$ExpectedError = '') {
    $before = Get-AppConnections $Id
    $attemptsBefore = Get-AppAttempts $Id
    $maintenanceBefore = Get-MaintenanceConnections $Id
    $ok = $false
    try {
        Assert-HostPostgresTarget $java "jdbc:postgresql://127.0.0.1:$Port/kaipay?ApplicationName=kaipay-demo-00000000000000000000000000000001" 'postgres' $Password $Target
        $ok = $true
    } catch {
        if ($ShouldPass) { throw "Expected success in $Label`: $($_.Exception.Message)" }
        if ($ExpectedError -and $_.Exception.Message -notmatch $ExpectedError) { throw "Unexpected refusal reason in $Label`: $($_.Exception.Message)" }
    }
    Assert-True ($ok -eq $ShouldPass) "Unexpected result in $Label"
    $after = Get-AppConnections $Id
    $maintenanceAfter = Get-MaintenanceConnections $Id
    if ($ExpectCatalog) { Assert-True ($maintenanceAfter -eq ($maintenanceBefore + 1)) "Catalog stage was not reached in $Label" }
    else { Assert-True ($maintenanceAfter -eq $maintenanceBefore) "Unexpected authenticated catalog connection in $Label" }
    $attemptsAfter = Get-AppAttempts $Id
    if ($ShouldPass) { Assert-True ($after -eq ($before + 1) -and $attemptsAfter -gt $attemptsBefore) "Expected one application-database connection in $Label" }
    else { Assert-True ($after -eq $before -and $attemptsAfter -eq $attemptsBefore) "Unexpected application-database connection or attempt in $Label" }
    Write-Host "PASS $Label"
}
try {
    $context = ((Invoke-DockerTest @('context', 'show')) -join '').Trim()
    $endpoint = ((Invoke-DockerTest @('context', 'inspect', $context, '--format', '{{.Endpoints.docker.Host}}')) -join '').Trim()
    if ($endpoint -notmatch '^npipe:/{4}\./pipe/(dockerDesktopLinuxEngine|docker_engine)$') { throw 'Refusing a nonlocal Docker context.' }
    $image = ((Invoke-DockerTest @('image', 'inspect', 'postgres:16-alpine', '--format', '{{.Id}}')) -join '').Trim()
    if ($image -cnotmatch '^sha256:[a-f0-9]{64}$') { throw 'The local PostgreSQL 16 image is unavailable.' }
    $networkId = ((Invoke-DockerTest @('network', 'create', '--label', "kaipay.jdbc.fixture=$tag", "kaipay-jdbc-fixture-$tag")) -join '').Trim()
    if ($networkId -cnotmatch '^[a-f0-9]{64}$') { throw 'Unexpected disposable network ID.' }
    foreach ($n in @(1, 2)) {
        $name = "kaipay-jdbc-fixture-$tag-$n"
        $id = ((Invoke-DockerTest @('run', '-d', '--pull', 'never', '--name', $name, '--label', "kaipay.jdbc.fixture=$tag", '--network', $networkId,
            '--publish', '127.0.0.1::5432', '--tmpfs', '/var/lib/postgresql/data:rw,nosuid,nodev',
            '--env', 'POSTGRES_PASSWORD', $image, '-c', 'log_connections=on', '-c', 'log_line_prefix=%m [%p] db=%d user=%u ')) -join '').Trim()
        if ($id -cnotmatch '^[a-f0-9]{64}$') { throw 'Unexpected disposable container ID.' }
        $containers.Add($id)
        $mounts = ((Invoke-DockerTest @('inspect', '--type', 'container', '--format', '{{json .Mounts}}', $id)) -join '') | ConvertFrom-Json
        if (@($mounts | Where-Object { $_.Type -in @('bind', 'volume') }).Count) { throw 'Disposable fixture unexpectedly has a persistent volume or bind mount.' }
        $tmpfs = ((Invoke-DockerTest @('inspect', '--type', 'container', '--format', '{{json .HostConfig.Tmpfs}}', $id)) -join '') | ConvertFrom-Json -AsHashtable
        if (!$tmpfs.ContainsKey('/var/lib/postgresql/data')) { throw 'Disposable fixture database is not mounted as tmpfs.' }
        $deadline = [DateTime]::UtcNow.AddSeconds(60)
        do {
            # TCP, not the socket: the image's temporary initialization server listens only on
            # the socket and restarts afterwards, so a socket check can pass just before that restart.
            & $docker exec $id pg_isready -h 127.0.0.1 -U postgres -d postgres *> $null
            if ($LASTEXITCODE -eq 0) { break }
            Start-Sleep -Milliseconds 250
        } while ([DateTime]::UtcNow -lt $deadline)
        if ($LASTEXITCODE -ne 0) { throw 'Disposable PostgreSQL failed to become ready.' }
        $null = Invoke-Sql $id 'CREATE DATABASE kaipay'
    }
    $one = $containers[0]; $two = $containers[1]
    $members = ((Invoke-DockerTest @('network', 'inspect', '--format', '{{json .Containers}}', $networkId)) -join '') | ConvertFrom-Json -AsHashtable
    if ($members.Count -ne 2 -or !$members.ContainsKey($one) -or !$members.ContainsKey($two)) { throw 'Disposable network has unexpected members.' }
    $null = Invoke-Sql $one 'CREATE DATABASE retained_fixture'
    $identity1 = (Invoke-Sql $one "SELECT system_identifier::text || '|' || (SELECT oid::text FROM pg_database WHERE datname='kaipay') FROM pg_control_system()").Split('|')
    $identity2 = (Invoke-Sql $two "SELECT system_identifier::text || '|' || (SELECT oid::text FROM pg_database WHERE datname='kaipay') FROM pg_control_system()").Split('|')
    $old = (Invoke-Sql $one "SELECT oid FROM pg_database WHERE datname='retained_fixture'").Trim()
    $target = @{ postgresSystemId = $identity1[0]; postgresDatabaseOid = $identity1[1] }
    $port1 = ((Invoke-DockerTest @('port', $one, '5432/tcp')) -join '').Trim()
    $port2 = ((Invoke-DockerTest @('port', $two, '5432/tcp')) -join '').Trim()
    if ($port1 -cnotmatch '^127\.0\.0\.1:(\d+)$') { throw 'Unexpected first fixture binding.' }; $port1 = [int]$Matches[1]
    if ($port2 -cnotmatch '^127\.0\.0\.1:(\d+)$') { throw 'Unexpected second fixture binding.' }; $port2 = [int]$Matches[1]
    Assert-True ($identity1[0] -cne $identity2[0] -and $old -cne $identity1[1]) 'Fixture identities are not distinct.'
    [IO.File]::WriteAllText($protectedPath, "$($identity1[0])|$old`n$($identity2[0])|$($identity2[1])`n")
    Invoke-Probe $one $port1 $target $true 'matching active target and differently named protected database'
    $badOid = @{ postgresSystemId = $identity1[0]; postgresDatabaseOid = '4294967294' }
    Assert-True ($badOid.postgresDatabaseOid -cne $old -and $badOid.postgresDatabaseOid -cne $identity1[1]) 'Wrong OID must be distinct from fixture identities.'
    Invoke-Probe $one $port1 $badOid $false 'wrong target OID' $secret $true 'identity mismatch'
    $badSystem = @{ postgresSystemId = $identity2[0]; postgresDatabaseOid = $identity1[1] }
    [IO.File]::WriteAllText($protectedPath, "$($identity1[0])|$old`n")
    Invoke-Probe $one $port1 $badSystem $false 'wrong cluster system ID' $secret $true 'identity mismatch'
    [IO.File]::WriteAllText($protectedPath, "$($identity1[0])|$old`n$($identity2[0])|$($identity2[1])`n")
    $null = Invoke-Sql $one 'ALTER DATABASE kaipay RENAME TO fixture_missing'
    Invoke-Probe $one $port1 $target $false 'missing target database' $secret $true 'connection or identity verification failed'
    $null = Invoke-Sql $one 'ALTER DATABASE fixture_missing RENAME TO kaipay'
    [IO.File]::WriteAllText($protectedPath, "$($identity1[0])|$($identity1[1])`n")
    Invoke-Probe $one $port1 $target $false 'protected identity' $secret $false 'protected PostgreSQL identity'
    [IO.File]::WriteAllText($protectedPath, "$($identity1[0])|$old`n$($identity2[0])|$($identity2[1])`n")
    Invoke-Probe $two $port2 $target $false 'protected database named kaipay on unexpected cluster' $secret $true 'protected PostgreSQL identity'
    Invoke-Probe $one $port1 $target $false 'maintenance-stage credential refusal' 'synthetic-wrong-password' $false 'connection or identity verification failed'
    $missingPort = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $missingPort.Start(); $closedPort = ([Net.IPEndPoint]$missingPort.LocalEndpoint).Port; $missingPort.Stop()
    Invoke-Probe $one $closedPort $target $false 'maintenance-stage connection failure' $secret $false 'connection or identity verification failed'
    $lines = @(Invoke-DockerTest @('logs', $one))
    Assert-True (@($lines | Where-Object { $_ -match 'connection authorized:.*database=retained_fixture(?:\s|$)' }).Count -eq 0) 'The differently named protected fixture received a connection.'
    Write-Host 'PASS disposable PostgreSQL identity gates; no protected or unexpected application database was connected.'
} finally {
    $env:POSTGRES_PASSWORD = $previousPassword
    foreach ($id in $containers) {
        try {
            $label = ((Invoke-DockerTest @('inspect', '--type', 'container', '--format', '{{index .Config.Labels "kaipay.jdbc.fixture"}}', $id)) -join '').Trim()
            if ($label -cne $tag) { throw 'Disposable container label does not match; refusing cleanup.' }
            $null = Invoke-DockerTest @('rm', '-f', $id)
        } catch { Write-Warning "Could not clean up the specifically labelled fixture container $id. $($_.Exception.Message)" }
    }
    if ($networkId) {
        try {
            $label = ((Invoke-DockerTest @('network', 'inspect', '--format', '{{index .Labels "kaipay.jdbc.fixture"}}', $networkId)) -join '').Trim()
            if ($label -cne $tag) { throw 'Disposable network label does not match; refusing cleanup.' }
            $null = Invoke-DockerTest @('network', 'rm', $networkId)
        } catch { Write-Warning "Could not clean up the specifically labelled fixture network $networkId. $($_.Exception.Message)" }
    }
    if (Test-Path -LiteralPath $runtime -PathType Container) { Remove-Item -LiteralPath $runtime -Recurse -Force }
}
