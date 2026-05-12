@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
set "PS1=%SCRIPT_DIR%deploy.ps1"

rem Prefer Windows PowerShell 5.1 (always present on Windows); fall back to
rem PowerShell 7 (pwsh) if it's the only one installed.
set "PS=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
if exist "%PS%" goto run

where pwsh >nul 2>nul && set "PS=pwsh" && goto run

echo PowerShell was not found. Install Windows PowerShell or PowerShell 7.
exit /b 1

:run
rem `exit /b %ERRORLEVEL%` must stay OUT of a parenthesised ( ... ) block:
rem cmd expands %ERRORLEVEL% once, when it parses the block -- i.e. before
rem powershell runs -- so inside a block it would always report the stale
rem pre-powershell exit code instead of the script's real one.
"%PS%" -NoProfile -ExecutionPolicy Bypass -File "%PS1%" %*
exit /b %ERRORLEVEL%
