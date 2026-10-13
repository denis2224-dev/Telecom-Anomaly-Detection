param(
    [Parameter(Mandatory = $true)][string]$Python,
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [Parameter(Mandatory = $true)][string]$ReferenceRoot,
    [string]$EvidenceDirectory = 'docs/evidence/assets/trace-link-day2'
)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
$Python = (Resolve-Path -LiteralPath $Python).Path
$JavaHome = (Resolve-Path -LiteralPath $JavaHome).Path
$ReferenceRoot = (Resolve-Path -LiteralPath $ReferenceRoot).Path
$startedAt = [DateTime]::UtcNow.ToString('o')
$runDir = 'tmp/trace-link-day2/reproduction'
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
if (Test-Path -LiteralPath "$runDir/run.json") { Remove-Item -LiteralPath "$runDir/run.json" }

function Invoke-Checked([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Executable failed with exit code $LASTEXITCODE" }
}

# Includes contracts, all root/ML Python tests, processor verify, legacy/geographic
# parity, fresh Java/Python/JavaScript/PostgreSQL exports and frozen Day 1 pins.
& "$PSScriptRoot/verify-trace-link-day1.ps1" -Python $Python -JavaHome $JavaHome -ReferenceRoot $ReferenceRoot *>&1 |
    Tee-Object -FilePath "$runDir/day1.log"
$pythonSummaries = [regex]::Matches((Get-Content -Raw -LiteralPath "$runDir/day1.log"), 'Ran (\d+) tests? in')
if ($pythonSummaries.Count -ne 2) { throw 'Expected fresh root and ML Python test summaries' }
Invoke-Checked $Python @('-c', "from scripts.trace_link_history_baseline import verify_java_parity; print(verify_java_parity('services/processor/target/trace-link-baseline-java-parity.json'))")
foreach ($build in @(1, 2)) {
    Invoke-Checked $Python @('scripts/trace_link_history_baseline.py', '--reference-root', $ReferenceRoot,
        '--output', "$runDir/baseline-$build.json.gz", '--report', "$runDir/history-$build.json")
}
$first = (Get-FileHash -Algorithm SHA256 -LiteralPath "$runDir/baseline-1.json.gz").Hash
$second = (Get-FileHash -Algorithm SHA256 -LiteralPath "$runDir/baseline-2.json.gz").Hash
$published = (Get-FileHash -Algorithm SHA256 -LiteralPath 'docs/evidence/assets/trace-link-day2/seasonal-baseline.json.gz').Hash
if ($first -ne $second -or $first -ne $published) { throw 'Historical artifact reproduction differs' }
@{ startedAt = $startedAt; finishedAt = [DateTime]::UtcNow.ToString('o'); exitCode = 0;
    referenceRoot = $ReferenceRoot; python = $Python; javaHome = $JavaHome;
    historyBuilds = 2; publishedArtifactSha256 = $first.ToLowerInvariant();
    pythonTests = @{ root = [int]$pythonSummaries[0].Groups[1].Value; ml = [int]$pythonSummaries[1].Groups[1].Value } } |
    ConvertTo-Json | Set-Content -Encoding utf8 -LiteralPath "$runDir/run.json"
Invoke-Checked $Python @('scripts/publish_trace_link_day2.py', '--run', "$runDir/run.json", '--output-dir', $EvidenceDirectory)
Write-Output 'PASS: Day 2 offline reproduction, frozen compatibility and deterministic history'
