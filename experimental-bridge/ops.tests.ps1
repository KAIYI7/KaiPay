#Requires -Version 7.4
# Actual private synthetic receiver/process checks. No Android or public ingress.
[CmdletBinding()]
param([string]$JavaHome = 'C:\Program Files\Java\jdk-21')
$ErrorActionPreference = 'Stop'
$ops = Join-Path $PSScriptRoot 'ops.ps1'
$runtime = Join-Path (Split-Path -Parent $PSScriptRoot) '.local/supervised-ops'
$manifest = Join-Path $runtime 'owner.json'
if (Test-Path -LiteralPath $runtime) { throw 'Test requires absent supervised-ops directory; preserve existing operator state.' }
$previousReceiver = $env:BRIDGE_RECEIVER_TOKEN
$previousDevice = $env:BRIDGE_DEVICE_TOKEN
$previousJvm = $env:JAVA_TOOL_OPTIONS
$previousImport = $env:SPRING_CONFIG_IMPORT
$checks = 0
function Check([bool]$condition, [string]$message) {
    if (!$condition) { throw $message }
    $script:checks++
}
function Must-Fail([scriptblock]$operation, [string]$expected) {
    $failed = $false
    try { & $operation | Out-Null } catch { $failed = $_.Exception.Message.Contains($expected) }
    Check $failed "Expected controlled failure: $expected"
}
$portLease = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$portLease.Start()
$port = $portLease.LocalEndpoint.Port
$portLease.Stop()
$originalListeners = @(Get-NetTCPConnection -State Listen | ForEach-Object { "$($_.LocalAddress):$($_.LocalPort):$($_.OwningProcess)" })
try {
    $env:BRIDGE_RECEIVER_TOKEN = $null; $env:BRIDGE_DEVICE_TOKEN = $null
    Must-Fail { & $ops preflight -JavaHome $JavaHome -Port $port } 'Distinct receiver'
    $env:BRIDGE_RECEIVER_TOKEN = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
    $env:BRIDGE_DEVICE_TOKEN = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
    Must-Fail { & $ops preflight -JavaHome (Join-Path $runtime 'missing-jdk') -Port $port } 'java.exe is missing'
    $unrelated = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $port)
    $unrelated.Start()
    try {
        $unownedStatus = & $ops status -Port $port | ConvertFrom-Json
        Check ($unownedStatus.backend -like 'UNKNOWN: no verified*') 'Unowned backend was probed'
        Check (!$unrelated.Pending()) 'Status connected to unrelated listener'
        Must-Fail { & $ops start -JavaHome $JavaHome -Port $port } 'already occupied'
        Check (!$unrelated.Pending()) 'Start sent credentials to occupied unrelated listener'
    } finally { $unrelated.Stop() }
    # Poison inherited switches: both preflight JVM and child must discard them.
    $env:JAVA_TOOL_OPTIONS = '-javaagent:missing-synthetic-agent.jar'
    $env:SPRING_CONFIG_IMPORT = 'file:missing-synthetic-config.properties'
    Check ((& $ops preflight -JavaHome $JavaHome -Port $port) -like 'PREFLIGHT PASS:*') 'Preflight failed'
    Check ((& $ops start -JavaHome $JavaHome -Port $port) -like 'STARTED:*') 'Start failed'
    $savedOwner = Get-Content -LiteralPath $manifest -Raw
    $firstOwner = $savedOwner | ConvertFrom-Json
    $status = & $ops status | ConvertFrom-Json
    Check ($status.process -like 'RUNNING*' -and $status.backend -like 'REACHABLE:*') 'Running/readiness status mismatch'
    Check ($status.androidListener -like 'UNKNOWN:*') 'Backend fabricated listener health'
    Must-Fail { & $ops start -JavaHome $JavaHome -Port $port } 'already running'
    $receiver = $env:BRIDGE_RECEIVER_TOKEN
    $env:BRIDGE_RECEIVER_TOKEN = 'X' * 40
    Check ((& $ops status | ConvertFrom-Json).backend -eq 'UNAVAILABLE: HTTP 401') 'Wrong credential unexpectedly authorized'
    $env:BRIDGE_RECEIVER_TOKEN = $receiver
    # A stale/reused identity must refuse stop and leave the real process alive.
    $altered = $savedOwner | ConvertFrom-Json
    $altered.startedTicks = '1'
    try {
        $altered | ConvertTo-Json | Set-Content -LiteralPath $manifest
        Must-Fail { & $ops stop } 'PID identity changed'
        Check ($null -ne (Get-Process -Id $firstOwner.pid -ErrorAction SilentlyContinue)) 'Mismatch refusal killed process'
    } finally { Set-Content -LiteralPath $manifest -Value $savedOwner }
    $headers = @{ Authorization = "Bearer $receiver" }
    $request = Invoke-RestMethod "http://127.0.0.1:$port/api/requests" -Method Post -Headers $headers -ContentType application/json -Body '{"amountMinor":1234}'
    Check ($request.status -eq 'AWAITING_PAYMENT') 'Synthetic fixture request failed'
    Check ((& $ops stop) -like 'STOPPED:*') 'Stop failed'
    Check (!(Test-Path -LiteralPath $manifest)) 'Ownership record remained after stop'
    Check ($null -eq (Get-Process -Id $firstOwner.pid -ErrorAction SilentlyContinue)) 'Owned child remained'
    Check ((& $ops status | ConvertFrom-Json).process -like 'STOPPED*') 'Stopped status mismatch'
    Check ((& $ops start -JavaHome $JavaHome -Port $port) -like 'STARTED:*') 'Restart failed'
    $savedOwner = Get-Content -LiteralPath $manifest -Raw
    $secondOwner = $savedOwner | ConvertFrom-Json
    Check ($secondOwner.marker -cne $firstOwner.marker) 'Restart reused process ownership marker'
    $state = Invoke-RestMethod "http://127.0.0.1:$port/api/state" -Headers $headers
    Check ($state.requests.Count -eq 1 -and $state.requests[0].id -eq $request.id) 'Restart lost durable synthetic request'
    & $ops stop | Out-Null
    Check (!(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)) 'Task listener remained after stop'
    $privateContents = (Get-ChildItem -LiteralPath $runtime -File | Where-Object Name -ne 'command.lock' | Get-Content -Raw) -join ''
    Check (!$privateContents.Contains($receiver) -and !$privateContents.Contains($env:BRIDGE_DEVICE_TOKEN)) 'Credentials persisted in operational files'
    # Check this task never removed any baseline process that still exists.
    $currentListeners = @(Get-NetTCPConnection -State Listen | ForEach-Object { "$($_.LocalAddress):$($_.LocalPort):$($_.OwningProcess)" })
    $lost = @($originalListeners | Where-Object { $_ -notin $currentListeners })
    Check ($lost.Count -eq 0) 'A baseline listener changed during test; investigate before delivery'
    "PASS: $checks actual private operations assertions"
} finally {
    if (Test-Path -LiteralPath $manifest) { & $ops stop | Out-Null }
    $env:BRIDGE_RECEIVER_TOKEN = $previousReceiver
    $env:BRIDGE_DEVICE_TOKEN = $previousDevice
    $env:JAVA_TOOL_OPTIONS = $previousJvm
    $env:SPRING_CONFIG_IMPORT = $previousImport
    # Exact known files only. Retire the disposable test request and logs after child exit.
    if (!(Test-Path -LiteralPath $manifest)) {
        foreach ($name in @('command.lock', 'stdout.log', 'stderr.log', 'state.json', 'state.json.lock')) {
            $path = Join-Path $runtime $name
            if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path }
        }
        if ((Test-Path -LiteralPath $runtime) -and !(Get-ChildItem -LiteralPath $runtime -Force)) { Remove-Item -LiteralPath $runtime }
    }
}
