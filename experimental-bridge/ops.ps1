#Requires -Version 7.4
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('preflight', 'status', 'start', 'stop')][string]$Action,
    [ValidateRange(1024, 65535)][int]$Port = 28082,
    [string]$JavaHome = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
$moduleRoot = $PSScriptRoot
$checkout = Split-Path -Parent $moduleRoot
$runtime = Join-Path $checkout '.local/supervised-ops'
$jar = Join-Path $moduleRoot 'target/notification-simulator-0.1.0-SNAPSHOT.jar'
$manifest = Join-Path $runtime 'owner.json'

# Refuse redirected state directories before creating or opening anything in them.
foreach ($directory in @((Join-Path $checkout '.local'), $runtime)) {
    if (Test-Path -LiteralPath $directory) {
        if ((Get-Item -LiteralPath $directory).Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw 'Operations state directory must not be a reparse point.'
        }
    } else { $null = New-Item -ItemType Directory -Path $directory }
}
foreach ($file in @($manifest, (Join-Path $runtime 'command.lock'), (Join-Path $runtime 'stdout.log'),
        (Join-Path $runtime 'stderr.log'), (Join-Path $runtime 'state.json'), (Join-Path $runtime 'state.json.lock'),
        (Join-Path $runtime 'state.json.tmp'))) {
    if ((Test-Path -LiteralPath $file) -and ((Get-Item -LiteralPath $file).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'Operations files must not be reparse points.'
    }
}
$lock = [IO.File]::Open((Join-Path $runtime 'command.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
try {
    function Get-Owner {
        if (!(Test-Path -LiteralPath $manifest)) { return $null }
        if ((Get-Item -LiteralPath $manifest).Length -gt 8192) { throw 'Invalid ownership record size.' }
        $owner = Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json
        if ($owner.marker -notmatch '^[0-9a-f-]{36}$' -or $owner.jar -cne $jar -or
                $owner.java -notmatch '\\bin\\java.exe$' -or $owner.port -lt 1024 -or $owner.port -gt 65535 -or
                $owner.pid -notmatch '^[1-9][0-9]{0,9}$' -or $owner.startedTicks -notmatch '^[0-9]{1,19}$') {
            throw 'Invalid ownership record; no process action performed.'
        }
        return $owner
    }
    function Get-OwnedProcess($owner) {
        if ($null -eq $owner) { return $null }
        $process = Get-Process -Id $owner.pid -ErrorAction SilentlyContinue
        if ($null -eq $process) { return $null }
        # Retain this Process handle through Kill, preventing a reused PID from being targeted.
        $null = $process.Handle
        if ($process.StartTime.ToUniversalTime().Ticks.ToString() -cne $owner.startedTicks -or $process.Path -cne $owner.java) {
            throw 'PID identity changed; refusing to touch the process.'
        }
        $details = Get-CimInstance Win32_Process -Filter "ProcessId = $($process.Id)"
        if ($null -eq $details -or $details.CommandLine -notmatch ('(?:^|\s)-Dkaipay.ops.owner=' + [regex]::Escape($owner.marker) + '(?:\s|$)') -or
                !$details.CommandLine.Contains($jar)) {
            throw 'Process ownership could not be verified; no process action performed.'
        }
        return $process
    }
    function Test-OwnedListener($process, [int]$probePort) {
        if ($null -eq $process -or $process.HasExited) { return $false }
        $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $probePort -ErrorAction SilentlyContinue)
        return $listeners.Count -eq 1 -and $listeners[0].OwningProcess -eq $process.Id -and $listeners[0].LocalAddress -ceq '127.0.0.1'
    }
    function Get-CleanEnvironment {
        # Remove inherited application/JVM/config/logging switches, retaining only OS paths.
        $allowed = @('SYSTEMROOT', 'WINDIR', 'TEMP', 'TMP', 'USERPROFILE', 'LOCALAPPDATA', 'APPDATA', 'PROGRAMDATA')
        $clean = @{}
        foreach ($entry in Get-ChildItem Env:) {
            if ($entry.Name.ToUpperInvariant() -notin $allowed) { $clean[$entry.Name] = $null }
        }
        return $clean
    }
    function Get-Probe([int]$probePort) {
        if ($env:BRIDGE_RECEIVER_TOKEN -notmatch '^[A-Za-z0-9_-]{32,256}$') { return 'UNKNOWN: receiver credential unavailable' }
        try {
            $response = Invoke-WebRequest -Uri "http://127.0.0.1:$probePort/api/health" -Headers @{
                Authorization = "Bearer $env:BRIDGE_RECEIVER_TOKEN"
            } -TimeoutSec 2 -MaximumRedirection 0 -SkipHttpErrorCheck -NoProxy
            if ($response.StatusCode -ne 200) { return "UNAVAILABLE: HTTP $($response.StatusCode)" }
            $health = $response.Content | ConvertFrom-Json
            $sample = [DateTimeOffset]::Parse($health.sampledAt)
            $age = ([DateTimeOffset]::UtcNow - $sample).TotalSeconds
            if ($health.backendProcess -cne 'RUNNING' -or $age -lt 0 -or $age -gt 15) { return 'UNKNOWN: invalid or stale response' }
            return 'REACHABLE: receiver-authorized probe now; Android listener/queue/worker UNKNOWN'
        } catch { return 'UNAVAILABLE: transport or invalid health response' }
    }
    function Test-Preflight {
        if (!$JavaHome) { throw 'Set JAVA_HOME to JDK 21 or pass -JavaHome.' }
        $script:java = Join-Path ([IO.Path]::GetFullPath($JavaHome)) 'bin/java.exe'
        if (!(Test-Path -LiteralPath $java)) { throw 'JDK java.exe is missing.' }
        $versionInfo = [Diagnostics.ProcessStartInfo]::new($java)
        $versionInfo.ArgumentList.Add('-version')
        $versionInfo.UseShellExecute = $false; $versionInfo.CreateNoWindow = $true
        $versionInfo.RedirectStandardOutput = $true; $versionInfo.RedirectStandardError = $true
        foreach ($name in (Get-CleanEnvironment).Keys) { $null = $versionInfo.Environment.Remove($name) }
        $versionProcess = [Diagnostics.Process]::Start($versionInfo)
        try {
            $stdout = $versionProcess.StandardOutput.ReadToEndAsync(); $stderr = $versionProcess.StandardError.ReadToEndAsync()
            if (!$versionProcess.WaitForExit(5000)) {
                $versionProcess.Kill(); $null = $versionProcess.WaitForExit(5000)
                throw 'JDK version check timed out.'
            }
            $version = $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()
            if ($versionProcess.ExitCode -ne 0 -or $version -notmatch 'version "21\.') { throw 'JDK 21 is required.' }
        } finally { $versionProcess.Dispose() }
        if (!(Test-Path -LiteralPath $jar)) { throw 'Build the experimental module with mvn -o verify first.' }
        if ($env:BRIDGE_RECEIVER_TOKEN -notmatch '^[A-Za-z0-9_-]{32,256}$' -or
                $env:BRIDGE_DEVICE_TOKEN -notmatch '^[A-Za-z0-9_-]{32,256}$' -or
                $env:BRIDGE_RECEIVER_TOKEN -ceq $env:BRIDGE_DEVICE_TOKEN) {
            throw 'Distinct receiver and simulation credentials (32–256 URL-safe characters) are required in the environment.'
        }
        if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) {
            throw 'Requested port is already occupied; no existing listener will be stopped.'
        }
    }
    $owner = Get-Owner
    $ownedProcess = Get-OwnedProcess $owner
    switch ($Action) {
        'status' {
            $processState = if ($null -ne $ownedProcess) { 'RUNNING (exact owned process)' } else { 'STOPPED (no owned process)' }
            $probePort = if ($null -ne $owner) { [int]$owner.port } else { $Port }
            $backend = if (Test-OwnedListener $ownedProcess $probePort) { Get-Probe $probePort } else { 'UNKNOWN: no verified owned loopback listener; no probe sent' }
            [pscustomobject]@{ sampledAt = [DateTimeOffset]::UtcNow.ToString('o'); process = $processState
                backend = $backend; androidListener = 'UNKNOWN: inspect phone callback state'
                financialState = 'Separate receiver verification and explicit confirmation required' } | ConvertTo-Json
        }
        'preflight' { Test-Preflight; 'PREFLIGHT PASS: private synthetic receiver only; no gateway or Android activation' }
        'stop' {
            if ($null -ne $ownedProcess) {
                $ownedProcess.Kill()
                if (!$ownedProcess.WaitForExit(10000)) { throw 'Owned process did not stop within 10 seconds; ownership record retained.' }
            }
            if (Test-Path -LiteralPath $manifest) { Remove-Item -LiteralPath $manifest }
            'STOPPED: owned receiver only; durable synthetic store retained; payer grants end with process'
        }
        'start' {
            if ($null -ne $ownedProcess) { throw 'Owned receiver is already running; use status.' }
            Test-Preflight
            $marker = [guid]::NewGuid().ToString()
            $arguments = @("-Dkaipay.ops.owner=$marker", '-jar', ('"' + $jar + '"'),
                '--spring.config.location=classpath:/application.properties', '--server.address=127.0.0.1', "--server.port=$Port",
                '--server.forward-headers-strategy=NONE', '--bridge.enabled=true', '--bridge.live-device-token=',
                '--bridge.live-device-id=', '--bridge.qr-directory=', ('"--bridge.store=' + (Join-Path $runtime 'state.json') + '"'))
            $environment = Get-CleanEnvironment
            $environment['BRIDGE_DEVICE_TOKEN'] = $env:BRIDGE_DEVICE_TOKEN
            $environment['BRIDGE_RECEIVER_TOKEN'] = $env:BRIDGE_RECEIVER_TOKEN
            $process = Start-Process -FilePath $java -ArgumentList ($arguments -join ' ') -WorkingDirectory $moduleRoot -PassThru -WindowStyle Hidden -Environment $environment `
                -RedirectStandardOutput (Join-Path $runtime 'stdout.log') -RedirectStandardError (Join-Path $runtime 'stderr.log')
            try {
                $null = $process.Handle
                $newOwner = [pscustomobject]@{ pid = $process.Id; startedTicks = $process.StartTime.ToUniversalTime().Ticks.ToString()
                    marker = $marker; java = $java; jar = $jar; port = $Port }
                $newOwner | ConvertTo-Json | Set-Content -LiteralPath $manifest -Encoding utf8
                $startupWatch = [Diagnostics.Stopwatch]::StartNew()
                do {
                    if ($process.HasExited) { throw 'Receiver exited before readiness; inspect local logs.' }
                    if (Test-OwnedListener $process $Port) {
                        $probe = Get-Probe $Port
                        if ($probe.StartsWith('REACHABLE:')) {
                            'STARTED: private synthetic receiver ready; Android listening UNKNOWN'; return
                        }
                    }
                    Start-Sleep -Milliseconds 250
                } while ($startupWatch.Elapsed.TotalSeconds -lt 30)
                throw 'Receiver did not pass authorized readiness within 30 seconds.'
            } catch {
                # This live handle belongs to the exact child we just created.
                if (!$process.HasExited) { $process.Kill(); $null = $process.WaitForExit(10000) }
                if ($process.HasExited -and (Test-Path -LiteralPath $manifest)) { Remove-Item -LiteralPath $manifest }
                throw
            }
        }
    }
} finally { $lock.Dispose() }
