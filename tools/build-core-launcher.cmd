@echo off
rem Launcher: the machine's PowerShell execution policy blocks .ps1 files, so run with Bypass.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-core.ps1" %*
exit /b %ERRORLEVEL%
