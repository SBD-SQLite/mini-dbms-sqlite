@echo off
rem Builds FRM together with the real CCM and runs the FRM-CCM integration checks for PRD
rem scenarios 6, 7, and 9 (CcmIntegrationTests). SM is FRM's MockStorageManager.
rem Exit code 0 only if every check passes.
setlocal EnableDelayedExpansion
set "FRM_DIR=%~dp0"
set "FRM_DIR=%FRM_DIR:~0,-1%"
for %%i in ("%FRM_DIR%\..") do set "ROOT=%%~fi"
set "OUT=%FRM_DIR%\out-integration"

if exist "%OUT%" rmdir /s /q "%OUT%"
mkdir "%OUT%"
rem javac argument files need quoted paths with forward slashes
(for /r "%ROOT%\shared\src\main\java" %%f in (*.java) do (set "p=%%f" & echo "!p:\=/!")) > "%OUT%\sources.txt"
(for /r "%ROOT%\concurrency-control\src\main\java" %%f in (*.java) do (set "p=%%f" & echo "!p:\=/!")) >> "%OUT%\sources.txt"
(for /r "%FRM_DIR%\src\main\java" %%f in (*.java) do (set "p=%%f" & echo "!p:\=/!")) >> "%OUT%\sources.txt"
(for /r "%FRM_DIR%\src\integration\java" %%f in (*.java) do (set "p=%%f" & echo "!p:\=/!")) >> "%OUT%\sources.txt"
javac -encoding UTF-8 --release 21 -d "%OUT%" @"%OUT%\sources.txt"
if errorlevel 1 exit /b 1

java -cp "%OUT%" sqlike.frm.integration.CcmIntegrationTests
exit /b %errorlevel%
