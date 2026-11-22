param(
    [string]$Python = "python",
    [string]$Maven = "mvn",
    [ValidateRange(1, 100)][int]$Runs = 5,
    [switch]$ExcelCells
)
$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    Get-Command $Program -ErrorAction Stop | Out-Null
    # Windows PowerShell maps redirected native stderr to errors even for successful tests.
    $ErrorActionPreference = "Continue"
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed with exit code $LASTEXITCODE" }
}
Push-Location $repo
try {
    Invoke-Checked $Maven @("-q", "package")
    $out = Join-Path $repo ("target/wiki-baseline-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory $out | Out-Null
    $src = Join-Path $out "sources"
    Invoke-Checked $Python @("$PSScriptRoot/fixtures.py", $src)
    Invoke-Checked $Python @("$PSScriptRoot/verify_sources.py", $src)
    $cp = "target/classes;target/app/*"
    Invoke-Checked "$env:JAVA_HOME/bin/javac" @("-encoding","UTF-8","-cp",$cp,"-d",$out,"$PSScriptRoot/WikiBaseline.java")
    foreach ($i in 1..$Runs) {
        $run = Join-Path $out "run-$i"
        $benchArgs = @("-cp","$out;$cp","WikiBaseline",$src,$run,"$PSScriptRoot/questions.json")
        if ($ExcelCells) { $benchArgs += "--excel-cells" }
        Invoke-Checked "$env:JAVA_HOME/bin/java" $benchArgs
        Invoke-Checked $Python @("$PSScriptRoot/score.py","$run/predictions.json")
    }
    Write-Output "Results: $out"
} finally {
    Pop-Location
}
