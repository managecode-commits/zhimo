# Copyright © 2026 立方田 <managecode@gmail.com>
#requires -Version 5.1
#requires -RunAsAdministrator
param([switch]$Upgrade)
$ErrorActionPreference = 'Stop'
try {
    if (![Environment]::Is64BitProcess -or $env:PROCESSOR_ARCHITECTURE -ne 'AMD64') {
        throw 'Use 64-bit PowerShell on x64 Windows, under the account that uses Zhimo.'
    }
    # Validate every payload before loading helper code or executing probes.
    $manifestPath = Join-Path $PSScriptRoot 'SHA256SUMS.json'
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    foreach ($required in @('ZhimoTsf.dll', 'ime_ffi.dll', 'zhimo-tsf-probe.exe',
        'x86/ZhimoTsf.dll', 'x86/ime_ffi.dll', 'x86/zhimo-tsf-probe.exe',
        'install.ps1', 'uninstall.ps1', 'dual-common.ps1')) {
        if (!$manifest.PSObject.Properties[$required]) { throw "Missing manifest entry: $required" }
    }
    foreach ($entry in $manifest.PSObject.Properties) {
        if ($entry.Name -match '(^[\\/]|:|(^|[\\/])\.\.([\\/]|$))') { throw 'Unsafe manifest path.' }
        if ((Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $entry.Name) -Algorithm SHA256).Hash -ne $entry.Value) {
            throw "Package checksum mismatch: $($entry.Name)"
        }
    }
    . (Join-Path $PSScriptRoot 'dual-common.ps1')
    $buildId = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash.Substring(0,16).ToLowerInvariant()
    $destination = Join-Path $env:ProgramFiles "Zhimo\Builds\$buildId"
    if (Test-Path -LiteralPath $destination) { throw "Build directory already exists: $destination. No files replaced." }
    $previous = @{}
    foreach ($arch in @('x86', 'x64')) {
        $previous[$arch] = Get-ZhimoRegistration $arch
        if ($previous[$arch]) {
            if (!$Upgrade) { throw 'Existing Zhimo found. Close editors and run install.cmd -Upgrade.' }
            Assert-ZhimoManagedDll $previous[$arch]
        }
    }
    foreach ($probe in @('zhimo-tsf-probe.exe', 'x86\zhimo-tsf-probe.exe')) {
        & (Join-Path $PSScriptRoot $probe)
        if ($LASTEXITCODE -ne 0) { throw "Probe failed: $probe. Nothing registered." }
    }
    New-Item -ItemType Directory -Path $destination | Out-Null
    foreach ($entry in $manifest.PSObject.Properties) {
        $target = Join-Path $destination $entry.Name
        New-Item -ItemType Directory -Force -Path (Split-Path $target) | Out-Null
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $entry.Name) -Destination $target
        if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne $entry.Value) { throw "Installed copy mismatch: $target" }
    }
    Copy-Item -LiteralPath $manifestPath -Destination $destination
    $dlls = @{ x86 = (Join-Path $destination 'x86\ZhimoTsf.dll'); x64 = (Join-Path $destination 'ZhimoTsf.dll') }
    try {
        foreach ($arch in @('x86', 'x64')) {
            Invoke-ZhimoRegistration $arch $dlls[$arch]
            if ((Get-ZhimoRegistration $arch) -ne $dlls[$arch]) { throw "$arch registration path did not update." }
        }
        foreach ($arch in @('x86', 'x64')) {
            if ((Get-ZhimoRegistration $arch) -ne $dlls[$arch]) { throw "$arch registration changed during the second registration." }
        }
    } catch {
        $failure = $_
        # Remove partial new registrations before restoring all previous views:
        # TSF profile/category metadata may be shared across architectures.
        foreach ($arch in @('x86', 'x64')) {
            try { Invoke-ZhimoRegistration $arch $dlls[$arch] -Remove } catch { Write-Warning "Cleanup $arch failed: $_" }
        }
        foreach ($arch in @('x86', 'x64')) {
            if ($previous[$arch]) {
                try {
                    Invoke-ZhimoRegistration $arch $previous[$arch]
                    if ((Get-ZhimoRegistration $arch) -ne $previous[$arch]) { throw 'Restore path mismatch.' }
                } catch { Write-Warning "Restore $arch failed: $_" }
            }
        }
        throw "Registration failed: $failure. Files and old builds retained. See rollback warnings above."
    }
    Write-Host "Registered BOTH x86 and x64: $destination"
    Write-Host 'Sign out and sign in again. Test EmEditor 32-bit and a 64-bit editor.'
    Write-Host 'User vocabulary and old build directories were not deleted.'
    exit 0
} catch { Write-Error -ErrorAction Continue $_; exit 1 }
