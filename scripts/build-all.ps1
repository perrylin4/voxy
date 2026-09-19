$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$dist = Join-Path $repo 'dist'
New-Item -ItemType Directory -Force -Path $dist | Out-Null
Get-ChildItem -LiteralPath $dist -Filter '*.jar' -File -ErrorAction SilentlyContinue |
    Remove-Item -Force

$editions = @(
    'neoforge-1.21.1',
    'client-1.20.1',
    'client-26.1.2'
)

foreach ($edition in $editions) {
    Write-Host "`n=== Building $edition ===" -ForegroundColor Cyan
    & (Join-Path $PSScriptRoot 'build.ps1') $edition
    if ($LASTEXITCODE -ne 0) {
        throw "Build failed for $edition."
    }
}

Write-Host "`n=== Release artifacts ===" -ForegroundColor Green
Get-ChildItem -LiteralPath $dist -Filter '*.jar' -File |
    Sort-Object Name |
    Select-Object Name, Length, LastWriteTime
