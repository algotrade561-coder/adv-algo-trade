param(
    [switch]$SkipUiBuild,
    [switch]$SkipNpmInstall
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$ui = Join-Path $root "ui"

function Require-Command($Name, $InstallHint) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        Write-Host ""
        Write-Host "Missing required command: $Name" -ForegroundColor Red
        Write-Host $InstallHint
        exit 1
    }
}

Require-Command "node" "Install Node.js LTS from https://nodejs.org/ and reopen PowerShell."
Require-Command "npm" "Install Node.js LTS from https://nodejs.org/ and reopen PowerShell."
Require-Command "mvn" "Install Maven 3.9+ and make sure mvn is available on PATH."

if (-not $SkipUiBuild) {
    Write-Host "Building Angular UI into Spring Boot static resources..." -ForegroundColor Cyan
    Push-Location $ui
    try {
        if (-not $SkipNpmInstall) {
            if (-not (Test-Path "node_modules")) {
                Write-Host "Installing UI dependencies..."
                npm install
            } else {
                Write-Host "UI dependencies already installed. Skipping npm install."
            }
        }
        npm run build
    } finally {
        Pop-Location
    }
} else {
    Write-Host "Skipping UI build. Existing Spring Boot static files will be used." -ForegroundColor Yellow
}

Write-Host ""
Write-Host "Starting one local application..." -ForegroundColor Cyan
Write-Host "Open http://localhost:8080 after startup completes." -ForegroundColor Green
Write-Host ""

Push-Location $root
try {
    mvn spring-boot:run
} finally {
    Pop-Location
}
