@echo off
rem Builds FRM against the root shared types and runs the FRM driver (FR-09).
rem Usage: failure-recovery\run-driver.bat [--list ^| --show-log SCENARIO [TXID] ^| scenario ...]
setlocal EnableDelayedExpansion
set "FRM_DIR=%~dp0"
set "FRM_DIR=%FRM_DIR:~0,-1%"
for %%i in ("%FRM_DIR%\..") do set "ROOT=%%~fi"
set "OUT=%FRM_DIR%\out"

if exist "%OUT%" rmdir /s /q "%OUT%"
mkdir "%OUT%"
rem javac argument files need quoted paths with forward slashes
(for /r "%ROOT%\shared\src\main\java" %%f in (*.java) do (set "p=%%f" & echo "!p:\=/!")) > "%OUT%\sources.txt"
(for /r "%FRM_DIR%\src\main\java" %%f in (*.java) do (set "p=%%f" & echo "!p:\=/!")) >> "%OUT%\sources.txt"
javac -encoding UTF-8 -d "%OUT%" @"%OUT%\sources.txt"
if errorlevel 1 exit /b 1

java -cp "%OUT%" sqlike.frm.driver.FRMDriver --data-dir "%FRM_DIR%\frm-data" %*
exit /b %errorlevel%
