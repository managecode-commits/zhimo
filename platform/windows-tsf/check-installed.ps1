# Copyright © 2026 立方田 <managecode@gmail.com>
#requires -Version 5.1
$ErrorActionPreference = 'Stop'
$versions = @{
    '90f332ddd29b265747605c18a98c9e6637dd59b24c4c4a76389c92d36d1274a9' = 'OLD: initial test (Pinyin initialization bug)'
    '102bdda80f68a115719b7e63b83d40a519c11bff5f7e4f700e57e5438fa5a7f7' = 'OLD: Pinyin fix, vertical candidates'
    '22921fca4e37b74a6693963f1dea3a39ca0f425e1c0adbeeb169c9f558318396' = 'EXPECTED: EmEditor + compact horizontal candidates'
}
function Describe-Dll([string]$location) {
    Write-Host "DLL path: $location"
    if (!(Test-Path -LiteralPath $location -PathType Leaf)) {
        Write-Host 'DLL missing on disk'
        return
    }
    try {
        $hash = (Get-FileHash -LiteralPath $location -Algorithm SHA256).Hash.ToLowerInvariant()
        $label = $versions[$hash]
        if (!$label) { $label = 'Build not in historical list; inspect package metadata below' }
        $packageRoot = Split-Path $location
        $manifestEntry = 'ZhimoTsf.dll'
        if ((Split-Path $packageRoot -Leaf) -eq 'x86' -and
            (Test-Path -LiteralPath (Join-Path (Split-Path $packageRoot) 'SHA256SUMS.json'))) {
            $packageRoot = Split-Path $packageRoot
            $manifestEntry = 'x86/ZhimoTsf.dll'
        }
        $manifestPath = Join-Path $packageRoot 'SHA256SUMS.json'
        if (Test-Path -LiteralPath $manifestPath) {
            $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
            $expected = $manifest.PSObject.Properties[$manifestEntry].Value
            Write-Host "Matches installed package manifest: $($expected -eq $hash)"
            Write-Host 'Checksums detect file changes; they do not authenticate the publisher.'
        }
        $metadata = Join-Path $packageRoot 'BUILD-INFO.json'
        if (Test-Path -LiteralPath $metadata) { Write-Host (Get-Content -LiteralPath $metadata -Raw) }
        Write-Host "SHA256: $hash"
        Write-Host "Build: $label"
        Write-Host "Disk last write (UTC): $((Get-Item -LiteralPath $location).LastWriteTimeUtc.ToString('o'))"
    } catch { Write-Host "Cannot inspect DLL: $($_.Exception.Message)" }
}
Write-Host 'Zhimo read-only installation diagnostic. No registry writes, registration, or process termination.'
Write-Host 'Close private documents if taking screenshots; paths may contain your Windows user name.'
Write-Host "Diagnostic process x64: $([Environment]::Is64BitProcess)"
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
Write-Host "Running account: $($identity.Name)"
$identity.Dispose()
$subkey = 'Software\Classes\CLSID\{57d47ac2-67f4-4ca3-a824-2c48e68db781}\InprocServer32'
foreach ($hiveName in @('CurrentUser', 'LocalMachine')) {
    foreach ($viewName in @('Registry64', 'Registry32')) {
        $base = $null
        $key = $null
        try {
            $hive = [Enum]::Parse([Microsoft.Win32.RegistryHive], $hiveName)
            $view = [Enum]::Parse([Microsoft.Win32.RegistryView], $viewName)
            $base = [Microsoft.Win32.RegistryKey]::OpenBaseKey($hive, $view)
            $key = $base.OpenSubKey($subkey, $false)
            Write-Host "Registration [$hiveName / $viewName]"
            if ($null -eq $key) { Write-Host 'Not registered in this view' }
            else {
                $registeredPath = [string]$key.GetValue('')
                if ($registeredPath) { Describe-Dll $registeredPath }
                else { Write-Host 'Empty DLL registration' }
            }
        } catch { Write-Host "Cannot read registration: $($_.Exception.Message)" }
        finally {
            if ($null -ne $key) { $key.Dispose() }
            if ($null -ne $base) { $base.Dispose() }
        }
    }
}
$programRoot = [Environment]::GetEnvironmentVariable('ProgramW6432')
if (!$programRoot) { $programRoot = $env:ProgramFiles }
Write-Host 'Default installation on disk:'
Describe-Dll (Join-Path $programRoot 'Zhimo\Test-x64\ZhimoTsf.dll')
Write-Host 'Running EmEditor processes (test typing with Zhimo before running this check):'
$editors = @(Get-Process -Name EmEditor -ErrorAction SilentlyContinue)
if ($editors.Count -eq 0) { Write-Host 'No EmEditor process found' }
foreach ($editor in $editors) {
    Write-Host "EmEditor PID: $($editor.Id)"
    try {
        Write-Host "Process start (UTC): $($editor.StartTime.ToUniversalTime().ToString('o'))"
        $modules = @($editor.Modules | Where-Object { $_.ModuleName -match '^(lib)?ZhimoTsf\.dll$' })
        if ($modules.Count -eq 0) { Write-Host 'No Zhimo TSF module was observed; not proof that TSF is unsupported.' }
        foreach ($module in $modules) {
            Write-Host 'Observed loaded-module path; hash below is the FILE ON DISK, not the in-memory image:'
            Describe-Dll $module.FileName
        }
    } catch { Write-Host "Cannot enumerate modules: $($_.Exception.Message). Do not interpret this as no module loaded." }
}
Write-Host 'Compare the running account with the account used to install and test Zhimo.'
Write-Host 'A matching on-disk hash does not prove an already-running app loaded those same bytes.'
Write-Host 'No changes made. Copy this output, then use diagnose-emeditor.cmd to inspect the focused window.'
