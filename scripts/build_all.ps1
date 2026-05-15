<#
.SYNOPSIS
  Clean, build, test, and generate API docs for Rust, Python, and JVM.

.DESCRIPTION
  Wrapper around scripts/python_scripts/build_all.py (orchestrates rust_build,
  rust_test, python_build, python_test, java_build, java_test, docs_*).

.EXAMPLE
  pwsh -File scripts/build_all.ps1

.EXAMPLE
  pwsh -File scripts/build_all.ps1 -Clean -RustBuildTestWaitSeconds 45

.EXAMPLE
  pwsh -File scripts/build_all.ps1 -SkipDocs

.EXAMPLE
  python scripts/python_scripts/rust_build.py
  python scripts/python_scripts/rust_test.py
#>
[CmdletBinding()]
param(
  [switch]$Clean,
  [switch]$SkipRust,
  [switch]$SkipPython,
  [switch]$SkipJava,
  [switch]$SkipDocs,
  [switch]$RustExpandedOnly,
  [switch]$DocsOnly,
  [int]$WaitSeconds = 10,
  [int]$RustBuildTestWaitSeconds = 30,
  [switch]$Offline
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$py = Join-Path $repoRoot 'scripts/python_scripts/build_all.py'

if (-not (Test-Path $py)) {
  throw "Missing $py"
}

$pyArgs = @(
  '--wait-seconds', $WaitSeconds
  '--rust-build-test-wait-seconds', $RustBuildTestWaitSeconds
)
if ($Clean) { $pyArgs += '--clean' }
if ($SkipRust) { $pyArgs += '--skip-rust' }
if ($SkipPython) { $pyArgs += '--skip-python' }
if ($SkipJava) { $pyArgs += '--skip-java' }
if ($SkipDocs) { $pyArgs += '--skip-docs' }
if ($RustExpandedOnly) { $pyArgs += '--rust-expanded-only' }
if ($DocsOnly) { $pyArgs += '--docs-only' }
if ($Offline) { $pyArgs += '--offline' }

& python $py @pyArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
