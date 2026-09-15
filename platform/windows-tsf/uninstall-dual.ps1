# Copyright © 2026 立方田 <managecode@gmail.com>
#requires -Version 5.1
#requires -RunAsAdministrator
$ErrorActionPreference = 'Stop'
try {
    if (![Environment]::Is64BitProcess) { throw 'Use 64-bit PowerShell.' }
    . (Join-Path $PSScriptRoot 'dual-common.ps1')
    $dlls = @{ x86 = (Join-Path $PSScriptRoot 'x86\ZhimoTsf.dll'); x64 = (Join-Path $PSScriptRoot 'ZhimoTsf.dll') }
    # Preflight both views before changing either, protecting newer installs.
    foreach ($arch in @('x86', 'x64')) {
        Assert-ZhimoManagedDll $dlls[$arch]
        $registered = Get-ZhimoRegistration $arch
        if ($registered -and $registered -ne $dlls[$arch]) {
            throw "Different $arch build is registered: $registered. Nothing removed."
        }
    }
    foreach ($arch in @('x86', 'x64')) { Invoke-ZhimoRegistration $arch $dlls[$arch] -Remove }
    foreach ($arch in @('x86', 'x64')) {
        if (Get-ZhimoRegistration $arch) { throw "$arch registration still exists. Files retained." }
    }
    Write-Host 'Both architectures unregistered. Sign out and back in.'
    Write-Host "Files retained at $PSScriptRoot; user vocabulary was not deleted."
    exit 0
} catch { Write-Error -ErrorAction Continue $_; exit 1 }
