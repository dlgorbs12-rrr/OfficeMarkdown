$ErrorActionPreference='Stop'
$venv=Join-Path $env:LOCALAPPDATA 'OfficeMarkdown/vision-env'
$python=Join-Path $venv 'Scripts/python.exe'
if (!(Test-Path $python)) {
    & py -3 -m venv $venv
    if ($LASTEXITCODE) { throw 'Python 3.10+ is required.' }
}
& $python -m pip install 'markitdown-ocr==0.1.2' 'openai>=1,<3'
if ($LASTEXITCODE) { throw 'Could not install MarkItDown OCR.' }
