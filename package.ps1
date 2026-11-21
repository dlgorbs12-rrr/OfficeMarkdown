param(
    [string]$Maven = "mvn",
    [string]$Destination = "$PSScriptRoot\dist",
    [switch]$Installer
)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    [xml]$pom = Get-Content -Raw pom.xml
    $version = $pom.project.version
    $output = [IO.Path]::GetFullPath($ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Destination))
    $jpackage = Join-Path $env:JAVA_HOME 'bin/jpackage.exe'
    if ($Installer) {
        Get-Command candle.exe,light.exe -ErrorAction Stop | Out-Null
    }
    & $Maven -q clean package
    if ($LASTEXITCODE) { throw 'Maven build failed.' }
    Copy-Item -LiteralPath ocr -Destination target/app/ocr -Recurse -ErrorAction Stop
    & $jpackage --type app-image --name OfficeMarkdown --app-version $version --vendor OfficeMarkdown --add-launcher OfficeSearch=search-launcher.properties --add-launcher OfficeIndexMcp=mcp-launcher.properties --input target/app --main-jar "office-markdown-$version.jar" --main-class OfficeMarkdown --dest $output --add-modules java.se,jdk.charsets,jdk.zipfs,jdk.unsupported,jdk.crypto.ec,java.logging,jdk.management
    if ($LASTEXITCODE) { throw 'Application packaging failed. Choose an empty destination.' }
    if ($Installer) {
        & $jpackage --type msi --app-image "$output/OfficeMarkdown" --name OfficeMarkdown --app-version $version --vendor OfficeMarkdown --win-per-user-install --install-dir 'OfficeMarkdownApp' --win-menu --win-menu-group OfficeMarkdown --win-upgrade-uuid 608aa614-7b10-4faa-a185-542af26f15e9 --dest $output
        if ($LASTEXITCODE) { throw 'MSI packaging failed.' }
        $name = "OfficeMarkdown-$version.msi"
        $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath "$output/$name").Hash.ToLowerInvariant()
        [IO.File]::WriteAllText("$output/$name.sha256", "$hash  $name" + [Environment]::NewLine)
    }
} finally { Pop-Location }

