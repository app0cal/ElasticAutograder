@echo off
setlocal

set SCRIPT_DIR=%~dp0
set REPO_ROOT=%SCRIPT_DIR%..

python "%REPO_ROOT%\scripts\setup-graders.py" %*
if errorlevel 1 (
    echo Grader setup failed.
    exit /b 1
)

endlocal
