@rem Copyright © 2026 立方田 (managecode@gmail.com)
@echo off
cd /d "%~dp0"
echo Run this file as administrator using the SAME Windows account that will test Zhimo.
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File "%~dp0install.ps1" %*
set "zhimo_result=%errorlevel%"
pause
exit /b %zhimo_result%
