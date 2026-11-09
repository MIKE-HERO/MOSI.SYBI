@echo off
setlocal
title Instalador MOSI
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0instalar-mosi-device-owner.ps1" -ApkPath "%~1"
set "MOSI_EXIT=%ERRORLEVEL%"
echo.
pause
exit /b %MOSI_EXIT%

