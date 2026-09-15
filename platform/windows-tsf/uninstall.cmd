@rem Copyright © 2026 立方田 (managecode@gmail.com)
@echo off
cd /d "%~dp0"
echo Switch to Microsoft Pinyin before uninstalling. Run as administrator using the SAME account.
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File "%~dp0uninstall.ps1"
set "zhimo_result=%errorlevel%"
pause
exit /b %zhimo_result%
