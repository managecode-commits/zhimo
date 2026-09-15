# Copyright © 2026 立方田 <managecode@gmail.com>
#requires -Version 5.1
$ErrorActionPreference = 'Stop'
Add-Type @'
using System;
using System.Runtime.InteropServices;
using System.Text;
public static class ZhimoWindowDiagnostic {
    [StructLayout(LayoutKind.Sequential)] public struct Rect { public int l,t,r,b; }
    [StructLayout(LayoutKind.Sequential)] public struct Info {
        public uint size, flags;
        public IntPtr active, focus, capture, menu, move, caret;
        public Rect rect;
    }
    [DllImport("user32.dll")] public static extern bool GetGUIThreadInfo(uint id, ref Info info);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern int GetClassName(IntPtr hwnd, StringBuilder text, int size);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint id);
    [DllImport("user32.dll")] public static extern IntPtr GetParent(IntPtr hwnd);
    [DllImport("kernel32.dll", SetLastError=true)] public static extern bool IsWow64Process(IntPtr process, out bool wow64);
    public static string ClassName(IntPtr hwnd) {
        var text = new StringBuilder(256); GetClassName(hwnd, text, 256); return text.ToString();
    }
}
'@
Write-Host 'Switch to EmEditor and click its document. Waiting up to 60 seconds for focus.'
Write-Host 'Only process name/bitness and window classes are inspected. No document text or titles are read.'
$info = New-Object ZhimoWindowDiagnostic+Info
$info.size = [Runtime.InteropServices.Marshal]::SizeOf($info)
$deadline = [DateTime]::UtcNow.AddSeconds(60)
$process = $null
do {
    if ([ZhimoWindowDiagnostic]::GetGUIThreadInfo(0, [ref]$info)) {
        [uint32]$processId = 0
        [void][ZhimoWindowDiagnostic]::GetWindowThreadProcessId($info.focus, [ref]$processId)
        $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
        if ($process -and $process.ProcessName -eq 'EmEditor') { break }
    }
    Start-Sleep -Milliseconds 250
} while ([DateTime]::UtcNow -lt $deadline)
if (!$process -or $process.ProcessName -ne 'EmEditor') { throw 'EmEditor did not receive focus within 60 seconds.' }
Write-Host "Process: $($process.ProcessName); diagnostic process x64: $([Environment]::Is64BitProcess)"
[bool]$wow64 = $false
if ([ZhimoWindowDiagnostic]::IsWow64Process($process.Handle, [ref]$wow64)) {
    Write-Host "EmEditor architecture: $(if ($wow64 -or ![Environment]::Is64BitOperatingSystem) { 'x86 (32-bit)' } else { 'x64 (64-bit)' })"
} else { Write-Host 'EmEditor architecture could not be determined.' }
Write-Host "Active class: $([ZhimoWindowDiagnostic]::ClassName($info.active))"
Write-Host "Focus class: $([ZhimoWindowDiagnostic]::ClassName($info.focus))"
Write-Host "Caret class: $([ZhimoWindowDiagnostic]::ClassName($info.caret))"
$parent = $info.focus
for ($i = 0; $i -lt 5 -and $parent -ne [IntPtr]::Zero; $i++) {
    Write-Host "Parent level ${i}: $([ZhimoWindowDiagnostic]::ClassName($parent))"
    $parent = [ZhimoWindowDiagnostic]::GetParent($parent)
}
Write-Host 'Also report the EmEditor version and x64/x86 status from its About dialog.'
