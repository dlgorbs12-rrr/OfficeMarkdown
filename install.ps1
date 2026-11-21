#requires -Version 5.1
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
$repository = 'dlgorbs12-rrr/OfficeMarkdown'
$release = Invoke-RestMethod "https://api.github.com/repos/$repository/releases/latest"
if ($release.draft -ne $false -or $release.prerelease -ne $false -or
    $release.tag_name -cnotmatch '^v([0-9]{1,9}\.[0-9]{1,9}\.[0-9]{1,9})$') {
    throw 'Invalid stable release metadata.'
}
$installerName = "OfficeMarkdown-$($Matches[1]).msi"
$directory = New-Item -ItemType Directory -Path (Join-Path ([IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString()))
try {
    foreach ($name in @("$installerName.sha256", $installerName)) {
        $assets = @($release.assets | Where-Object { $_.name -ceq $name })
        $url = "https://github.com/$repository/releases/download/$($release.tag_name)/$name"
        if ($assets.Count -ne 1 -or $assets[0].browser_download_url -cne $url -or
            $assets[0].digest -notmatch '^sha256:[0-9a-f]{64}$') { throw "Invalid asset: $name" }
        $path = Join-Path $directory.FullName $name
        Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $path
        $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
        if ("sha256:$hash" -ne $assets[0].digest) { throw "SHA-256 mismatch: $name" }
    }
    $checksum = [IO.File]::ReadAllText("$path.sha256").TrimEnd([char]13, [char]10)
    if ($checksum -ne "$hash  $installerName") { throw 'Release checksum mismatch.' }
    $process = Start-Process -FilePath "$env:SystemRoot\System32\msiexec.exe" -ArgumentList "/i `"$path`" /norestart" -Wait -PassThru
    if ($process.ExitCode -notin @(0, 3010)) { throw "Windows Installer exited with code $($process.ExitCode)." }
} finally {
    foreach ($name in @($installerName, "$installerName.sha256")) {
        [IO.File]::Delete((Join-Path $directory.FullName $name))
    }
    [IO.Directory]::Delete($directory.FullName)
}
