param(
    [string]$Python = '.venv/Scripts/python.exe',
    [string]$ReferenceRoot = '../Trace_Link_Integration_Pack',
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repoRoot
$Python = (Resolve-Path -LiteralPath $Python).Path
$ReferenceRoot = (Resolve-Path -LiteralPath $ReferenceRoot).Path
$env:JAVA_HOME = (Resolve-Path -LiteralPath $JavaHome).Path
$wrapper = Join-Path $repoRoot $(if ($env:OS -eq 'Windows_NT') { 'mvnw.cmd' } else { 'mvnw' })

function Invoke-Checked([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Executable failed with exit code $LASTEXITCODE" }
}

# Run one Maven process per checkout. Docker must be available for real DB tests.
Invoke-Checked $wrapper @('-pl','services/processor','-am','verify','--batch-mode','--no-transfer-progress')
Invoke-Checked $Python @('-m','unittest','discover','-s','tests','-v')
Invoke-Checked $Python @('-m','unittest','discover','-s','services/ml-service/tests','-v')
Invoke-Checked $Python @('scripts/check-contracts.py')
Invoke-Checked $Python @('scripts/check-trace-link-ml-parity.py')
Invoke-Checked $Python @('scripts/check-voice-parity.py','--geographic','--java-output','services/processor/target/geographic-voice-parity-java.json')
Invoke-Checked $Python @('scripts/check-sms-parity.py','--geographic','--java-output','services/processor/target/geographic-sms-parity-java.json')
Invoke-Checked $Python @('scripts/verify-trace-link-pins.py','--reference-root',$ReferenceRoot)
Invoke-Checked $Python @('scripts/verify-trace-link-ml-http.py')
Write-Output 'PASS: Day 4 local gates. Existing opt-in archive/live checks retain their reported prerequisites.'
