# Copyright © 2026 立方田 <managecode@gmail.com>
# Shared by the dual-architecture installer and uninstaller. No action on import.
$script:ZhimoClsidKey = 'Software\Classes\CLSID\{57d47ac2-67f4-4ca3-a824-2c48e68db781}\InprocServer32'
function Get-ZhimoRegistration([string]$Arch) {
    $view = if ($Arch -eq 'x86') { [Microsoft.Win32.RegistryView]::Registry32 } else { [Microsoft.Win32.RegistryView]::Registry64 }
    $base = [Microsoft.Win32.RegistryKey]::OpenBaseKey([Microsoft.Win32.RegistryHive]::CurrentUser, $view)
    try {
        $key = $base.OpenSubKey($script:ZhimoClsidKey)
        if ($null -eq $key) { return $null }
        try { return [string]$key.GetValue('') } finally { $key.Dispose() }
    } finally { $base.Dispose() }
}
function Invoke-ZhimoRegistration([string]$Arch, [string]$Dll, [switch]$Remove) {
    $system = if ($Arch -eq 'x86') { 'SysWOW64' } else { 'System32' }
    $savedPath = $env:PATH
    try {
        $env:PATH = "$(Split-Path $Dll);$savedPath"
        $arguments = @('/s')
        if ($Remove) { $arguments += '/u' }
        $arguments += ('"' + $Dll + '"')
        $result = Start-Process -FilePath (Join-Path $env:SystemRoot "$system\regsvr32.exe") -ArgumentList $arguments -Wait -PassThru
        if ($result.ExitCode -ne 0) { throw "$Arch regsvr32 failed: $($result.ExitCode)" }
    } finally { $env:PATH = $savedPath }
}
function Assert-ZhimoManagedDll([string]$Dll) {
    $allowed = [IO.Path]::GetFullPath((Join-Path $env:ProgramFiles 'Zhimo')) + '\'
    if (![IO.Path]::GetFullPath($Dll).StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($Dll) -ne 'ZhimoTsf.dll' -or !(Test-Path -LiteralPath $Dll -PathType Leaf)) {
        throw "Registration is outside the managed Zhimo directory or its DLL is missing: $Dll"
    }
}
