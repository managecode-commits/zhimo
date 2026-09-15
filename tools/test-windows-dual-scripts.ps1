# Copyright © 2026 立方田 <managecode@gmail.com>
# Parser and mocked process-routing tests; NEVER registers a DLL or writes registry.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$source = Join-Path $root 'platform/windows-tsf'
$bootstrap = Get-Content -LiteralPath (Join-Path $source 'install.cmd') | Where-Object { $_ -like '* -Command *' }
if (!$bootstrap -or $bootstrap -notmatch '-Command "(.*)"$') { throw 'Installer bootstrap missing' }
$tokens = $null
$errors = $null
[void][System.Management.Automation.Language.Parser]::ParseInput($Matches[1], [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw "Installer bootstrap parse errors: $errors" }
foreach ($name in @('install-dual.ps1', 'uninstall-dual.ps1', 'dual-common.ps1', 'diagnose-emeditor.ps1', 'check-installed.ps1')) {
    $tokens = $null
    $errors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile((Join-Path $source $name), [ref]$tokens, [ref]$errors)
    if ($errors.Count -ne 0) { throw "$name syntax errors: $errors" }
}
. (Join-Path $source 'dual-common.ps1')
$script:failProcess = $false
$script:observed = $null
function Start-Process([string]$FilePath, [string[]]$ArgumentList, [switch]$Wait, [switch]$PassThru) {
    $script:observed = @{ file = $FilePath; args = $ArgumentList; path = $env:PATH }
    return [PSCustomObject]@{ ExitCode = $(if ($script:failProcess) { 3 } else { 0 }) }
}
$oldRoot = $env:SystemRoot
$oldPath = $env:PATH
try {
    $env:SystemRoot = [IO.Path]::GetTempPath()
    foreach ($arch in @('x86', 'x64')) {
        $dll = Join-Path ([IO.Path]::GetTempPath()) "$arch/ZhimoTsf.dll"
        Invoke-ZhimoRegistration $arch $dll
        $system = if ($arch -eq 'x86') { 'SysWOW64' } else { 'System32' }
        if ($script:observed.file -notmatch $system -or $script:observed.args -contains '/u') { throw "$arch route wrong" }
        if ($script:observed.args[-1] -ne ('"' + $dll + '"')) { throw 'DLL path not quoted' }
        if ($env:PATH -ne $oldPath) { throw 'PATH not restored after registration' }
        Invoke-ZhimoRegistration $arch $dll -Remove
        if ($script:observed.args -notcontains '/u') { throw 'Unregister flag missing' }
        $script:failProcess = $true
        $failed = $false
        try { Invoke-ZhimoRegistration $arch $dll } catch { $failed = $true }
        if (!$failed -or $env:PATH -ne $oldPath) { throw 'Failure or PATH cleanup was lost' }
        $script:failProcess = $false
    }
} finally { $env:SystemRoot = $oldRoot; $env:PATH = $oldPath }
Write-Host 'PASS: 5 scripts parsed; x86/x64 register/unregister routing, quoting, failure handling and PATH cleanup (mocked).'
