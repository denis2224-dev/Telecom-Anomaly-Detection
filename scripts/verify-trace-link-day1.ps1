param(
    [Parameter(Mandatory = $true)][string]$Python,
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [string]$ReferenceRoot,
    [switch]$Focused
)

# Run from the repository root. Dependencies must already be installed.
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repoRoot
$Python = (Resolve-Path -LiteralPath $Python).Path
$env:JAVA_HOME = (Resolve-Path -LiteralPath $JavaHome).Path
if (-not $ReferenceRoot) {
    $ReferenceRoot = (Get-Content -Raw docs/evidence/assets/trace-link-day1/input-manifest.json | ConvertFrom-Json).referenceRoot
}
$containerName = 'codex-trace-link-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
$outputDir = Join-Path $repoRoot 'tmp/trace-link-day1-reproduction'
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null

function Invoke-Checked([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Executable failed with exit code $LASTEXITCODE" }
}

Invoke-Checked $Python @('scripts/verify-trace-link-pins.py', '--reference-root', $ReferenceRoot)
Invoke-Checked $Python @('scripts/check-contracts.py')
Invoke-Checked $Python @('-m', 'unittest', 'discover', '-s', 'tests', '-v')
$javaExport = Join-Path $repoRoot 'services/processor/target/trace-link-java.json'
$javaTestReport = Join-Path $repoRoot 'services/processor/target/surefire-reports/TEST-md.utm.telecom.processing.kpi.TraceLinkArithmeticTest.xml'
# Exact generated files only: stale exports must never satisfy a fresh run.
foreach ($generatedFile in @($javaExport, $javaTestReport)) {
    if (Test-Path -LiteralPath $generatedFile) { Remove-Item -LiteralPath $generatedFile -Force }
}
if ($Focused) {
    Invoke-Checked $Python @('-m', 'unittest', 'discover', '-s', 'services/ml-service/tests', '-p', 'test_trace_link_reference.py', '-v')
    Invoke-Checked '.\mvnw.cmd' @('-pl', 'services/processor', '-am', '-Dtest=TraceLinkArithmeticTest', '-Dsurefire.failIfNoSpecifiedTests=false', '--batch-mode', '--no-transfer-progress', 'test')
} else {
    Invoke-Checked $Python @('-m', 'unittest', 'discover', '-s', 'services/ml-service/tests', '-v')
    Invoke-Checked '.\mvnw.cmd' @('-pl', 'services/processor', '-am', '--batch-mode', '--no-transfer-progress', 'verify')
    Invoke-Checked $Python @('scripts/check-voice-parity.py')
    Invoke-Checked $Python @('scripts/check-sms-parity.py', '--java-output', 'services/processor/target/sms-parity-java.json')
    Invoke-Checked $Python @('scripts/check-voice-parity.py', '--geographic', '--java-output', 'services/processor/target/geographic-voice-parity-java.json')
    Invoke-Checked $Python @('scripts/check-sms-parity.py', '--geographic', '--java-output', 'services/processor/target/geographic-sms-parity-java.json')
}
if (-not (Test-Path -LiteralPath $javaExport) -or -not (Test-Path -LiteralPath $javaTestReport)) {
    throw 'Java did not freshly produce its export and focused test report'
}
[xml]$javaReport = Get-Content -Raw -LiteralPath $javaTestReport
if ([int]$javaReport.testsuite.tests -lt 1 -or [int]$javaReport.testsuite.failures -ne 0 -or [int]$javaReport.testsuite.errors -ne 0) {
    throw 'Java arithmetic tests did not run successfully'
}
Invoke-Checked $Python @('services/ml-service/app/features/trace_link_reference.py', '--fixtures', 'contracts/fixtures/trace-link/arithmetic-cases.json', '--output', "$outputDir/python.json")
Invoke-Checked 'node' @('scripts/trace-link-reference.cjs', '--fixtures', 'contracts/fixtures/trace-link/arithmetic-cases.json', '--output', "$outputDir/javascript.json", '--reference-root', $ReferenceRoot)
try {
    # No ports, application database access, or demo-schema import.
    Invoke-Checked 'docker' @('run', '--rm', '-d', '--network', 'none', '--label', 'codex.trace-link-parity=true', '--name', $containerName, '-e', 'POSTGRES_HOST_AUTH_METHOD=trust', 'postgres:16.4-alpine')
    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        & docker exec $containerName pg_isready -U postgres 2>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'Disposable PostgreSQL did not become ready' }
    Invoke-Checked $Python @('scripts/check-trace-link-parity.py', '--export-sql', '--container', $containerName, '--output', "$outputDir/sql.json")
    Invoke-Checked $Python @('scripts/check-trace-link-parity.py', '--java-output', 'services/processor/target/trace-link-java.json', '--python-output', "$outputDir/python.json", '--sql-output', "$outputDir/sql.json", '--javascript-output', "$outputDir/javascript.json")
} finally {
    & docker stop $containerName 2>&1 | Out-Null
}
Invoke-Checked $Python @('scripts/verify-trace-link-pins.py', '--reference-root', $ReferenceRoot)
Write-Output "PASS: fresh Day 1 reproduction; exports in $outputDir"
