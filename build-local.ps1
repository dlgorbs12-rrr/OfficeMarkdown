param([string]$JdkHome, [switch]$DesktopShortcut)

$ErrorActionPreference = 'Stop'
$localRoot = Join-Path $PSScriptRoot 'local'
$toolsRoot = Join-Path $localRoot 'tools'
$originalJavaHome = $env:JAVA_HOME

if (-not $JdkHome) { $JdkHome = $originalJavaHome }
if (-not $JdkHome) {
    $jpackage = Get-Command jpackage.exe -CommandType Application -ErrorAction SilentlyContinue
    if ($jpackage) { $JdkHome = Split-Path (Split-Path $jpackage.Source) }
}

[xml]$pom = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'pom.xml') -Raw
$javaRelease = [int]$pom.project.properties.'maven.compiler.release'
if (-not $JdkHome -or -not (Test-Path -LiteralPath (Join-Path $JdkHome 'bin/jpackage.exe') -PathType Leaf)) {
    throw "Supply a JDK $javaRelease or newer with -JdkHome or JAVA_HOME."
}
$JdkHome = (Resolve-Path -LiteralPath $JdkHome).ProviderPath
$jdkRelease = Get-Content -LiteralPath (Join-Path $JdkHome 'release') -Raw
if ($jdkRelease -notmatch '(?m)^JAVA_VERSION="(\d+)' -or [int]$Matches[1] -lt $javaRelease) {
    throw "JDK $javaRelease or newer is required. Found: $JdkHome"
}

New-Item -Path $toolsRoot -ItemType Directory -Force | Out-Null
# Local builds share the Maven output directory in package.ps1.
$buildLock = [IO.File]::Open((Join-Path $localRoot 'build.lock'), 'OpenOrCreate', 'Write', 'None')
try {
    $mavenName = 'apache-maven-3.9.16'
    $mavenRoot = Join-Path $toolsRoot $mavenName
    $maven = Join-Path $mavenRoot 'bin/mvn.cmd'
    $verified = Join-Path $mavenRoot '.verified'
    if (-not (Test-Path -LiteralPath $verified -PathType Leaf) -or -not (Test-Path -LiteralPath $maven -PathType Leaf)) {
        $archive = Join-Path $toolsRoot "$mavenName-bin.zip"
        $source = "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/$mavenName-bin.zip"
        $sha512 = 'ed41650d42485cfc243fad22158caf9cbb5dc408ce7a09ddb94dd42a019de929ca43065bfa450612cf12bf78b5cafa3884b96c090de326ff590448c933454af3'
        Write-Host "Downloading $mavenName"
        $ProgressPreference = 'SilentlyContinue'
        Invoke-WebRequest -Uri $source -UseBasicParsing -OutFile $archive
        if ((Get-FileHash -LiteralPath $archive -Algorithm SHA512).Hash -ne $sha512) {
            throw 'Maven archive SHA-512 verification failed.'
        }
        Expand-Archive -LiteralPath $archive -DestinationPath $toolsRoot -Force
        New-Item -Path $verified -ItemType File -Force | Out-Null
        Remove-Item -LiteralPath $archive
    }

    $env:JAVA_HOME = $JdkHome
    $buildId = '{0}-{1:yyyyMMdd-HHmmssfff}' -f $pom.project.version, [DateTime]::UtcNow
    $destination = Join-Path $localRoot "apps\$buildId"
    Write-Host "Building with $mavenName and $JdkHome"
    & (Join-Path $PSScriptRoot 'package.ps1') -Maven $maven -Destination $destination

    $appRoot = Join-Path $destination 'OfficeMarkdown'
    $app = Join-Path $appRoot 'OfficeMarkdown.exe'
    if (-not (Test-Path -LiteralPath $app -PathType Leaf)) { throw "Application launcher missing: $app" }
    $search = Join-Path $appRoot 'OfficeSearch.exe'
    $usage = & $search --help
    if ($LASTEXITCODE -ne 0 -or -not ($usage -match '^OfficeSearch\.exe ')) {
        throw 'OfficeSearch help check failed.'
    }

    $current = Join-Path $localRoot 'current.txt'
    $next = Join-Path $localRoot 'current.next'
    [IO.File]::WriteAllText($next, "$buildId`r`n", [Text.Encoding]::ASCII)
    if ([IO.File]::Exists($current)) {
        [IO.File]::Replace($next, $current, [NullString]::Value)
    } else {
        [IO.File]::Move($next, $current)
    }
    Write-Host "Installed: $app"
    if ($DesktopShortcut) {
        $desktop = [Environment]::GetFolderPath('DesktopDirectory')
        $linkPath = Join-Path $desktop 'OfficeMarkdown.lnk'
        $target = Join-Path $PSScriptRoot 'run-local.cmd'
        $shell = New-Object -ComObject WScript.Shell
        $link = $shell.CreateShortcut($linkPath)
        if ((Test-Path -LiteralPath $linkPath) -and $link.TargetPath -ne $target) {
            $linkPath = Join-Path $desktop 'OfficeMarkdown (local).lnk'
            $link = $shell.CreateShortcut($linkPath)
            if ((Test-Path -LiteralPath $linkPath) -and $link.TargetPath -ne $target) {
                throw "Desktop shortcut name already in use: $linkPath"
            }
        }
        $link.TargetPath = $target
        $link.WorkingDirectory = $PSScriptRoot
        $link.IconLocation = "$(Join-Path $PSScriptRoot 'packaging/OfficeMarkdown.ico'),0"
        $link.WindowStyle = 7
        $link.Description = 'OfficeMarkdown'
        $link.Save()
        Write-Host "Desktop shortcut: $linkPath"
    }
    Write-Host $usage
} finally {
    $env:JAVA_HOME = $originalJavaHome
    $buildLock.Dispose()
}
