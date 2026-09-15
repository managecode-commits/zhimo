@rem Copyright © 2026 立方田 (managecode@gmail.com)
@echo off
cd /d "%~dp0"
echo Run this file as administrator using the SAME Windows account that will test Zhimo.
set "ZHIMO_PACKAGE_ROOT=%~dp0"
echo This preview uses unsigned scripts. Verify the ZIP checksum and source before continuing.
powershell.exe -NoProfile -Command "$ErrorActionPreference='Stop'; $policies=Get-ExecutionPolicy -List; if (@('AllSigned','Restricted') -contains [string]$policies.MachinePolicy -or @('AllSigned','Restricted') -contains [string]$policies.UserPolicy) { Write-Error 'Organization policy requires administrator approval or signed scripts.'; exit 2 }; $files=@(Get-ChildItem -LiteralPath $env:ZHIMO_PACKAGE_ROOT -Filter '*.ps1' -File | Where-Object { Get-Item -LiteralPath $_.FullName -Stream Zone.Identifier -ErrorAction SilentlyContinue }); if ($files.Count -gt 0) { Write-Host 'Downloaded scripts are blocked. Only scripts in this package directory will be unblocked; system policy is unchanged.'; if ((Read-Host 'After verifying this package, type UNBLOCK to continue') -cne 'UNBLOCK') { exit 3 }; $files | Unblock-File }"
if errorlevel 1 goto finished
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File "%~dp0install.ps1" %*
:finished
set "zhimo_result=%errorlevel%"
set "ZHIMO_PACKAGE_ROOT="
pause
exit /b %zhimo_result%
