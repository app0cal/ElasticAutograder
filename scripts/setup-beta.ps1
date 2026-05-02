# setup-beta.ps1
# Elastic Autograder beta setup for Windows

$ErrorActionPreference = "Stop"

function Write-Step {
    param([string]$Message)
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Write-Ok {
    param([string]$Message)
    Write-Host "[OK] $Message" -ForegroundColor Green
}

function Write-WarnMsg {
    param([string]$Message)
    Write-Host "[WARN] $Message" -ForegroundColor Yellow
}

function Write-ErrMsg {
    param([string]$Message)
    Write-Host "[ERROR] $Message" -ForegroundColor Red
}

function Command-Exists {
    param([string]$CommandName)
    return [bool](Get-Command $CommandName -ErrorAction SilentlyContinue)
}

function Require-Winget {
    if (-not (Command-Exists "winget")) {
        Write-ErrMsg "winget was not found. Install/update App Installer, then rerun this script."
        exit 1
    }
    Write-Ok "winget exists"
}

function Ensure-Command {
    param(
        [string]$DisplayName,
        [string]$CommandName,
        [string]$WingetId,
        [string]$VersionArgs = "--version"
    )

    Write-Step "Checking $DisplayName"

    if (Command-Exists $CommandName) {
        Write-Ok "$DisplayName exists"
        try {
            & $CommandName $VersionArgs.Split(" ")
        } catch {
            Write-WarnMsg "$DisplayName was found, but version check failed."
        }
        return
    }

    Write-WarnMsg "$DisplayName not found. Installing..."
    try {
        winget install --id $WingetId --exact --accept-package-agreements --accept-source-agreements
    } catch {
        Write-ErrMsg "Failed to install $DisplayName with winget."
        throw
    }

    if (Command-Exists $CommandName) {
        Write-Ok "$DisplayName installed successfully"
        try {
            & $CommandName $VersionArgs.Split(" ")
        } catch {
            Write-WarnMsg "$DisplayName installed, but version check failed."
        }
    } else {
        Write-WarnMsg "$DisplayName install finished, but command still was not found in this session."
        Write-WarnMsg "You may need to restart PowerShell."
    }
}

function Check-DockerDesktop {
    Write-Step "Checking Docker Desktop"

    if (Command-Exists "docker") {
        Write-Ok "Docker CLI exists"
        try {
            docker --version
        } catch {
            Write-WarnMsg "Docker CLI exists, but version check failed."
        }

        try {
            docker info | Out-Null
            Write-Ok "Docker appears to be running"
        } catch {
            Write-WarnMsg "Docker is installed, but Docker Desktop may not be running yet."
        }
    } else {
        Write-WarnMsg "Docker Desktop / Docker CLI not found."
        Write-WarnMsg "Install Docker Desktop manually, launch it, and make sure Docker is running before continuing."
    }
}

function Show-NextSteps {
    Write-Host ""
    Write-Host "================ NEXT STEPS ================" -ForegroundColor Magenta
    Write-Host "1. Make sure Docker Desktop is installed and running."
    Write-Host "2. Restart PowerShell if a newly installed command is not recognized."
    Write-Host "3. Run your project-specific setup commands:"
    Write-Host "   - create kind cluster"
    Write-Host "   - load grader images into kind"
    Write-Host "   - start backend"
    Write-Host "   - start frontend"
    Write-Host "4. Verify the app by uploading a sample submission."
    Write-Host "==========================================="
}

Write-Host "Elastic Autograder Beta Setup" -ForegroundColor Magenta
Write-Host "Checking and installing supported prerequisites..." -ForegroundColor Magenta

Require-Winget

# Manual prerequisite
Check-DockerDesktop

# Auto-install candidates
Ensure-Command `
    -DisplayName "Node.js" `
    -CommandName "node" `
    -WingetId "OpenJS.NodeJS.LTS" `
    -VersionArgs "--version"

Ensure-Command `
    -DisplayName "npm" `
    -CommandName "npm" `
    -WingetId "OpenJS.NodeJS.LTS" `
    -VersionArgs "--version"

Ensure-Command `
    -DisplayName "Python 3.12" `
    -CommandName "python" `
    -WingetId "Python.Python.3.12" `
    -VersionArgs "--version"

Ensure-Command `
    -DisplayName "Java 21" `
    -CommandName "java" `
    -WingetId "EclipseAdoptium.Temurin.21.JDK" `
    -VersionArgs "-version"

Ensure-Command `
    -DisplayName "kubectl" `
    -CommandName "kubectl" `
    -WingetId "Kubernetes.kubectl" `
    -VersionArgs "version --client"

Ensure-Command `
    -DisplayName "kind" `
    -CommandName "kind" `
    -WingetId "Kubernetes.kind" `
    -VersionArgs "--version"

Show-NextSteps