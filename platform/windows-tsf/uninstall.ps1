# Copyright © 2026 立方田 <managecode@gmail.com>
#requires -Version 5.1
#requires -RunAsAdministrator
$ErrorActionPreference = 'Stop'
try {
    if (![Environment]::Is64BitProcess) { throw 'Use 64-bit PowerShell.' }
    $directory = $PSScriptRoot
    $allowedRoot = [IO.Path]::GetFullPath((Join-Path $env:ProgramFiles 'Zhimo')) + '\'
    if (![IO.Path]::GetFullPath($directory).StartsWith($allowedRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Run uninstall.cmd from the installed Program Files\Zhimo build directory, not the downloaded archive.'
    }
    $dll = Join-Path $directory 'ZhimoTsf.dll'
    if (!(Test-Path -LiteralPath $dll)) { throw "No installed test DLL at $dll" }
    $key = 'Registry::HKEY_CURRENT_USER\Software\Classes\CLSID\{57d47ac2-67f4-4ca3-a824-2c48e68db781}\InprocServer32'
    if (Test-Path $key) {
        $registered = (Get-Item -LiteralPath $key).GetValue('')
        if ($registered -ne $dll) { throw "A different Zhimo build is registered: $registered. Refusing to remove it." }
    }
    $env:PATH = "$directory;$env:PATH"
    $process = Start-Process -FilePath "$env:SystemRoot\System32\regsvr32.exe" -ArgumentList @('/s', '/u', ('"' + $dll + '"')) -Wait -PassThru
    if ($process.ExitCode -ne 0 -or (Test-Path $key)) { throw 'Unregistration failed; retain the files and collect regsvr32 diagnostics.' }
    Write-Host 'Unregister request completed. Verify the Zhimo keyboard is absent after signing out/rebooting.'
    Write-Host "Files retained at $directory because applications may still have the DLL loaded."
    Write-Host 'After reboot, you may delete that exact directory manually. Personal vocabulary was not deleted.'
    exit 0
} catch {
    Write-Error -ErrorAction Continue $_
    exit 1
}
