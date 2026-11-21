param([string]$Library)
$ErrorActionPreference = 'Stop'
$buildId = (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'local/current.txt') -Raw).Trim()
$server = Join-Path $PSScriptRoot "local/apps/$buildId/OfficeMarkdown/OfficeIndexMcp.exe"
if ($Library) {
    & $server --library $Library
} else {
    & $server
}
exit $LASTEXITCODE
