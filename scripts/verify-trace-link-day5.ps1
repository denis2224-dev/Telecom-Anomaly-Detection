param([string]$Python='.venv/Scripts/python.exe', [Parameter(Mandatory=$true)][string]$Output)
$ErrorActionPreference='Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
& $Python scripts/verify-trace-link-day5.py --output $Output
if ($LASTEXITCODE -ne 0) { throw 'Day 5 verification incomplete; inspect verification.json and raw logs.' }
