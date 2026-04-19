$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$ui = Join-Path $root "ui"

if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    Write-Host "Missing node. Install Node.js LTS from https://nodejs.org/ and reopen PowerShell." -ForegroundColor Red
    exit 1
}

if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
    Write-Host "Missing npm. Install Node.js LTS from https://nodejs.org/ and reopen PowerShell." -ForegroundColor Red
    exit 1
}

Push-Location $ui
try {
    if (-not (Test-Path "node_modules")) {
        npm install
    }
    npm run build
} finally {
    Pop-Location
}

Write-Host "UI built into src/main/resources/static." -ForegroundColor Green
