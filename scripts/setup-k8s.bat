@echo off
setlocal

set CLUSTER_NAME=elastic-autograder
set SCRIPT_DIR=%~dp0
set REPO_ROOT=%SCRIPT_DIR%..
set KIND_CONFIG=%REPO_ROOT%\k8s\kind-config.yaml
set IMAGE_BUILD_ROOT=%REPO_ROOT%\backend\grading\image-build

echo Checking for kind cluster "%CLUSTER_NAME%"...
kind get clusters | findstr /x /c:"%CLUSTER_NAME%" >nul
if errorlevel 1 (
    echo Cluster not found. Creating kind cluster from config...
    kind create cluster --name %CLUSTER_NAME% --config "%KIND_CONFIG%"
    if errorlevel 1 (
        echo Failed to create cluster from %KIND_CONFIG%
        exit /b 1
    )
) else (
    echo Cluster "%CLUSTER_NAME%" already exists.
)

echo Moving to image build directory...
cd /d "%IMAGE_BUILD_ROOT%"
if errorlevel 1 (
    echo Failed to change directory to %IMAGE_BUILD_ROOT%
    exit /b 1
)

echo Building Fibonacci grader image...
docker build --no-cache -f runtime/Dockerfile -t ea-grader-fibbonaci:v1 --build-arg GRADER_NAME=fibbonaci .
if errorlevel 1 (
    echo Failed to build Fibonacci grader image.
    exit /b 1
)

echo Building Two Sum grader image...
docker build --no-cache -f runtime/Dockerfile -t ea-grader-twosum:v1 --build-arg GRADER_NAME=twosum .
if errorlevel 1 (
    echo Failed to build Two Sum grader image.
    exit /b 1
)

echo Loading grader images into kind cluster...
kind load docker-image ea-grader-fibbonaci:v1 --name %CLUSTER_NAME%
if errorlevel 1 (
    echo Failed to load Fibonacci grader image into kind.
    exit /b 1
)

kind load docker-image ea-grader-twosum:v1 --name %CLUSTER_NAME%
if errorlevel 1 (
    echo Failed to load Two Sum grader image into kind.
    exit /b 1
)

echo Setup complete.
echo Cluster "%CLUSTER_NAME%" is ready and grader images are loaded.

endlocal