param(
    [Parameter(Mandatory = $true)][string]$Python,
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [Parameter(Mandatory = $true)][string]$ReferenceRoot,
    [string]$EvidenceDirectory = 'tmp/trace-link-day3-verification',
    [string]$HardwareManifest,
    [switch]$MeasureRealtime
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repoRoot
$Python = (Resolve-Path -LiteralPath $Python).Path
$env:JAVA_HOME = (Resolve-Path -LiteralPath $JavaHome).Path
$ReferenceRoot = (Resolve-Path -LiteralPath $ReferenceRoot).Path
if ($MeasureRealtime) {
    if (-not $HardwareManifest) { throw '-MeasureRealtime requires -HardwareManifest with actual host description, cpuCount and physical memoryBytes' }
    $HardwareManifest = (Resolve-Path -LiteralPath $HardwareManifest).Path
}
New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null
$runDir = (Resolve-Path -LiteralPath $EvidenceDirectory).Path
$wrapper = Join-Path $repoRoot $(if ($env:OS -eq 'Windows_NT') { 'mvnw.cmd' } else { 'mvnw' })
$startedAt = [DateTime]::UtcNow
$records = [System.Collections.Generic.List[object]]::new()
$taskTemp = Join-Path $runDir 'python-temp'
New-Item -ItemType Directory -Force -Path $taskTemp | Out-Null
$env:TMP = $taskTemp
$env:TEMP = $taskTemp

function Invoke-Gate([string]$Name, [string]$Executable, [string[]]$Arguments) {
    $start = [DateTime]::UtcNow
    $log = Join-Path $runDir ($Name + '.log')
    $exitCode = 1
    try {
        & $Executable @Arguments *> $log
        $exitCode = $LASTEXITCODE
    } catch { $_ | Out-String | Add-Content -LiteralPath $log }
    $records.Add(@{ name = $Name; executable = $Executable; arguments = $Arguments;
        workingDirectory = $repoRoot; startedAt = $start.ToString('o');
        finishedAt = [DateTime]::UtcNow.ToString('o'); exitCode = $exitCode; log = $log })
    Write-Host "$Name exit=$exitCode"
    return ($exitCode -eq 0)
}

try {
    $dockerReady = Invoke-Gate 'docker-preflight' 'docker' @('info', '--format', '{{.ServerVersion}}')
    $null = Invoke-Gate 'frozen-pins' $Python @('scripts/verify-trace-link-pins.py', '--reference-root', $ReferenceRoot)
    $null = Invoke-Gate 'contracts' $Python @('scripts/check-contracts.py')
    $null = Invoke-Gate 'root-python' $Python @('-u', '-m', 'unittest', 'discover', '-s', 'tests', '-v')
    $null = Invoke-Gate 'ml-python' $Python @('-u', '-m', 'unittest', 'discover', '-s', 'services/ml-service/tests', '-v')
    if ($dockerReady) {
        # Prevent a previous producer export from satisfying the receiver boundary.
        $producerExport = Join-Path $repoRoot 'services/processor/target/trace-link-day3-produced.json'
        if (Test-Path -LiteralPath $producerExport) { Remove-Item -LiteralPath $producerExport -Force }
        $processorPassed = Invoke-Gate 'reactor' $wrapper @('--batch-mode', '--no-transfer-progress', 'verify')
        if ($processorPassed -and (Test-Path -LiteralPath $producerExport) -and
                (Get-Item -LiteralPath $producerExport).LastWriteTimeUtc -ge $startedAt) {
            $null = Invoke-Gate 'incident-service' $wrapper @('-f', 'services/incident-service/pom.xml',
                '--batch-mode', '--no-transfer-progress', 'verify', "-DtraceLink.producedFile=$producerExport")
        } else {
            $records.Add(@{ name = 'incident-service'; status = 'BLOCKED'; reason = 'Fresh successful producer output required' })
        }
        if ($MeasureRealtime) {
            $measureSource = (& git rev-parse HEAD).Trim()
            $profile = Join-Path $runDir 'measured-profile.json'
            @{ warmupSeconds = 120; steadySeconds = 900; burstSeconds = 60; burstMultiplier = 2;
                drainTimeoutSeconds = 300; steadyRatePerSecond = 4; rateUnit = 'persisted_windows';
                configuredConcurrency = 4; queueCapacityBound = 1000;
                declaredAt = [DateTime]::UtcNow.ToString('o') } |
                ConvertTo-Json | Set-Content -Encoding utf8 -LiteralPath $profile
            $measured = Invoke-Gate 'realtime' $wrapper @('-pl', 'services/processor', '-am',
                '--batch-mode', '--no-transfer-progress', 'test', '-Dtest=TraceLinkRealtimeHarness',
                '-Dsurefire.failIfNoSpecifiedTests=false', '-DtraceLink.measure=true',
                '-DtraceLink.measure.rate=4', "-DtraceLink.measure.sourceCommit=$measureSource")
            if ($measured) {
                $raw = Join-Path $repoRoot 'services/processor/target/trace-link-realtime-raw.json'
                $received = Invoke-Gate 'measured-receiving-replay' $wrapper @('-f', 'services/incident-service/pom.xml',
                    '--batch-mode', '--no-transfer-progress', 'verify', '-Dtest=TraceLinkDetectionMessageTest',
                    '-Dit.test=TraceLinkKafkaReceiverIT', "-DtraceLink.producedFile=$raw")
                if ($received) {
                    $receipt = Join-Path $repoRoot 'services/incident-service/target/trace-link-day3-received.json'
                    $inputFile = Join-Path $runDir 'capacity-input.json'
                    $converted = Invoke-Gate 'capacity-input' $Python @('scripts/trace_link_day3_capacity_input.py',
                        '--raw', $raw, '--receipts', $receipt, '--hardware', $HardwareManifest, '--profile', $profile,
                        '--source-commit', $measureSource, '--mode', 'real-time', '--output', $inputFile)
                    if ($converted) {
                        $null = Invoke-Gate 'capacity-report' $Python @('services/ml-service/training/benchmark.py',
                            '--input', $inputFile, '--output', (Join-Path $runDir 'capacity-report.json'))
                    }
                }
            }
        }
    } else {
        $records.Add(@{ name = 'reactor-incident-realtime'; status = 'BLOCKED'; reason = 'Docker engine unavailable; no integration pass claimed' })
    }
    $null = Invoke-Gate 'compose' 'docker' @('compose', 'config', '--quiet')
} finally {
    $source = (& git rev-parse HEAD).Trim()
    $trackedDirty = [bool](& git status --porcelain --untracked-files=no)
    $failed = @($records | Where-Object { $_.ContainsKey('exitCode') -and $_.exitCode -ne 0 })
    $blocked = @($records | Where-Object { $_.ContainsKey('status') -and $_.status -eq 'BLOCKED' })
    @{ schemaVersion = 'trace-link-day3-verification-v1'; sourceCommit = $source; trackedWorkingTreeDirty = $trackedDirty;
        startedAt = $startedAt.ToString('o'); finishedAt = [DateTime]::UtcNow.ToString('o');
        status = $(if ($failed.Count -or $blocked.Count) { 'INCOMPLETE' } else { 'PASS' });
        realtimeRequested = [bool]$MeasureRealtime; commands = $records.ToArray() } |
        ConvertTo-Json -Depth 12 | Set-Content -Encoding utf8 -LiteralPath (Join-Path $runDir 'execution.json')
}
if ($failed.Count -or $blocked.Count) { exit 1 }
