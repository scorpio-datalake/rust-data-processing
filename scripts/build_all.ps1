<#
.SYNOPSIS
  Clean Rust and Python-wrapper artifacts, then run the full CI-style pipeline (Python orchestrator).

.NOTES
  Run from any directory:

    pwsh -File scripts/build_all.ps1
    pwsh -File scripts/build_all.ps1 --offline
    pwsh -File scripts/build_all.ps1 --skip-java --skip-docs

  Extra arguments are forwarded to `scripts/python_scripts/build_all.py` (see that file for flags).
  This script does not pass `--clean` to the orchestrator: `cargo clean` and `python_clean.py`
  already ran; use `--clean` explicitly if you also need Gradle clean inside the Java steps.
#>
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$ExtraArgs
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

Write-Host "== Rust: cargo clean =="
& cargo clean
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "== Python wrapper: python_clean.py =="
$pyClean = Join-Path $repoRoot 'scripts\python_scripts\python_clean.py'
& python $pyClean
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "== Full build / test / docs: build_all.py =="
$buildAll = Join-Path $repoRoot 'scripts\python_scripts\build_all.py'
& python $buildAll @ExtraArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
