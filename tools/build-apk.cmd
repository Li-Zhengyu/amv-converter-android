@echo off
rem Launcher: this machine's PowerShell execution policy blocks .ps1 files.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-apk.ps1" %*
exit /b %ERRORLEVEL%
