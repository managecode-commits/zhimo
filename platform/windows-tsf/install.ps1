# Copyright © 2026 立方田 <managecode@gmail.com>
#requires -Version 5.1
#requires -RunAsAdministrator
param([switch]$Upgrade)
$ErrorActionPreference = 'Stop'
try {
    if (![Environment]::Is64BitProcess -or $env:PROCESSOR_ARCHITECTURE -ne 'AMD64') {
        throw 'Use 64-bit PowerShell on an x64 Windows computer.'
    }
    $buildId = (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'SHA256SUMS.json') -Algorithm SHA256).Hash.Substring(0, 16).ToLowerInvariant()
    $destination = Join-Path $env:ProgramFiles "Zhimo\Builds\$buildId"
    if (Test-Path $destination) {
        throw "Installation directory already exists: $destination. Uninstall the old test build, reboot, and move that directory aside first."
    }
    $registration = 'Registry::HKEY_CURRENT_USER\Software\Classes\CLSID\{57d47ac2-67f4-4ca3-a824-2c48e68db781}\InprocServer32'
    $previous = $null
    if (Test-Path $registration) {
        if (!$Upgrade) { throw 'Existing registration found. Close editors, then run install.ps1 -Upgrade explicitly.' }
        $previous = [string](Get-Item -LiteralPath $registration).GetValue('')
        $allowedRoot = [IO.Path]::GetFullPath((Join-Path $env:ProgramFiles 'Zhimo')) + '\'
        if (![IO.Path]::GetFullPath($previous).StartsWith($allowedRoot, [StringComparison]::OrdinalIgnoreCase) -or
            [IO.Path]::GetFileName($previous) -ne 'ZhimoTsf.dll' -or !(Test-Path -LiteralPath $previous -PathType Leaf)) {
            throw 'Previous registration is outside the managed Zhimo directory. Inspect it before upgrading.'
        }
    }
    # Compare package contents before executing its probe or copying anything.
    $manifest = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'SHA256SUMS.json') -Raw | ConvertFrom-Json
    foreach ($required in @('ZhimoTsf.dll', 'ime_ffi.dll', 'zhimo-tsf-probe.exe', 'install.ps1', 'uninstall.ps1')) {
        if (!$manifest.PSObject.Properties[$required]) { throw "Missing required manifest entry: $required" }
    }
    foreach ($entry in $manifest.PSObject.Properties) {
        if ($entry.Name -match '(^[\\/]|:|(^|[\\/])\.\.([\\/]|$))') { throw 'Unsafe manifest path.' }
        $file = Join-Path $PSScriptRoot $entry.Name
        if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $entry.Value) {
            throw "Package checksum mismatch: $($entry.Name)"
        }
    }
    & (Join-Path $PSScriptRoot 'zhimo-tsf-probe.exe')
    if ($LASTEXITCODE -ne 0) { throw 'Runtime probe failed. Do not register this build.' }
    New-Item -ItemType Directory -Path $destination | Out-Null
    foreach ($entry in $manifest.PSObject.Properties) {
        $target = Join-Path $destination $entry.Name
        New-Item -ItemType Directory -Force -Path (Split-Path $target) | Out-Null
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $entry.Name) -Destination $target
    }
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'SHA256SUMS.json') -Destination $destination
    $env:PATH = "$destination;$env:PATH"
    $dll = Join-Path $destination 'ZhimoTsf.dll'
    $process = Start-Process -FilePath "$env:SystemRoot\System32\regsvr32.exe" -ArgumentList @('/s', ('"' + $dll + '"')) -Wait -PassThru
    if ($process.ExitCode -ne 0) {
        if ($previous) {
            $env:PATH = "$(Split-Path $previous);$env:PATH"
            $restore = Start-Process -FilePath "$env:SystemRoot\System32\regsvr32.exe" -ArgumentList @('/s', ('"' + $previous + '"')) -Wait -PassThru
            Write-Host "Previous build registration restore exit code: $($restore.ExitCode)"
        }
        throw "Registration failed (exit $($process.ExitCode)). Files retained at $destination. Run uninstall.cmd there to clean partial registration."
    }
    if ($previous) { Write-Host "Previous DLL retained: $previous. Sign out to unload old modules. User vocabulary was not removed." }
    Write-Host 'Registered. Sign out and back in, then select Zhimo with Win+Space.'
    Write-Host 'If absent, open Language options for Chinese (Simplified, China) and add the Zhimo keyboard.'
    Write-Host "Installed at: $destination"
    Write-Host 'Keep Microsoft Pinyin installed. Test first in a non-sensitive classic Win32 editor.'
    exit 0
} catch {
    Write-Error -ErrorAction Continue $_
    exit 1
}
