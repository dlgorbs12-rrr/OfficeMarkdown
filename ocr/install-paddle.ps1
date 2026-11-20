param(
    [string]$InstallRoot = (Join-Path (Split-Path -Parent $PSScriptRoot) 'local/tools/ocr')
)

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath($InstallRoot)
$venv = Join-Path $root '.venv'
$python = Join-Path $venv 'Scripts/python.exe'
$models = Join-Path $root 'models'
New-Item -ItemType Directory -Path $models -Force | Out-Null

if (!(Test-Path -LiteralPath $python -PathType Leaf)) {
    & py -3.12 -m venv $venv
    if ($LASTEXITCODE) { throw '64비트 Python 3.12 가상환경을 만들 수 없습니다.' }
}

$checkVersions = @'
from importlib.metadata import PackageNotFoundError, version
import sys

if sys.version_info[:2] != (3, 12) or sys.maxsize <= 2**32:
    sys.exit(2)
expected = {'paddlepaddle': '3.2.2', 'paddleocr': '3.3.2', 'paddlex': '3.3.13'}
try:
    sys.exit(0 if all(version(name) == value for name, value in expected.items()) else 1)
except PackageNotFoundError:
    sys.exit(1)
'@
& $python -I -c $checkVersions
if ($LASTEXITCODE -eq 2) { throw 'PaddleOCR에는 64비트 Python 3.12 가상환경이 필요합니다.' }
if ($LASTEXITCODE) {
    & $python -I -m ensurepip --upgrade
    if ($LASTEXITCODE) { throw '가상환경에 pip를 준비할 수 없습니다.' }
    & $python -I -m pip install --only-binary=:all: 'paddlepaddle==3.2.2' 'paddleocr==3.3.2' 'paddlex==3.3.13'
    if ($LASTEXITCODE) { throw 'PaddleOCR 패키지 설치에 실패했습니다.' }
}

$modelSpecs = @(
    @{
        Name = 'PP-OCRv5_mobile_det_infer'
        Archive = '50446e5d01ac2a73d5319c89513281f6578414c888c602f9af13f93feefffc58'
        Weights = 'afa1820cb16c1fd0dad589d0f8b389139061c1ef6d68019685fd07be997dda5b'
    },
    @{
        Name = 'korean_PP-OCRv5_mobile_rec_infer'
        Archive = 'a6261f800ad335aa6ef77b6f7c4dcedfd5e11b95f4d70982d41ee31c2ace5e66'
        Weights = 'cac3e5f12cf04aaa77f6a5bc704e4e736ef2908476551891d84b41b4e9090462'
    }
)
$baseUri = 'https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/'
$extract = @'
from pathlib import Path
import sys
import tarfile
from tempfile import TemporaryDirectory

archive, models, name = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3]
with TemporaryDirectory(prefix='.unpack-', dir=models) as temporary:
    staging = Path(temporary)
    with tarfile.open(archive) as model:
        model.extractall(staging, filter='data')
    (staging / name).rename(models / name)
'@

foreach ($spec in $modelSpecs) {
    $model = Join-Path $models $spec.Name
    if (!(Test-Path -LiteralPath $model)) {
        $archiveName = $spec.Name + '.tar'
        $archive = Join-Path $models $archiveName
        if (!(Test-Path -LiteralPath $archive -PathType Leaf)) {
            $download = $archive + '.' + [Guid]::NewGuid().ToString('N') + '.part'
            try {
                Invoke-WebRequest -UseBasicParsing -Uri ($baseUri + $archiveName) -OutFile $download
                if ((Get-FileHash -Algorithm SHA256 -LiteralPath $download).Hash -ne $spec.Archive) {
                    throw "모델 다운로드의 SHA-256이 일치하지 않습니다. $($spec.Name)"
                }
                Move-Item -LiteralPath $download -Destination $archive
            } finally {
                if (Test-Path -LiteralPath $download -PathType Leaf) { Remove-Item -LiteralPath $download }
            }
        }
        if ((Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash -ne $spec.Archive) {
            throw "모델 압축 파일의 SHA-256이 일치하지 않습니다. $($spec.Name)"
        }
        & $python -I -c $extract $archive $models $spec.Name
        if ($LASTEXITCODE) { throw "모델 압축을 풀 수 없습니다. $($spec.Name)" }
    }

    $weights = Join-Path $model 'inference.pdiparams'
    if (!(Test-Path -LiteralPath (Join-Path $model 'inference.json') -PathType Leaf) -or
        !(Test-Path -LiteralPath (Join-Path $model 'inference.yml') -PathType Leaf) -or
        !(Test-Path -LiteralPath $weights -PathType Leaf) -or
        (Get-FileHash -Algorithm SHA256 -LiteralPath $weights).Hash -ne $spec.Weights) {
        throw "설치된 모델 파일을 검증할 수 없습니다. $($spec.Name)"
    }
}

Write-Output "OFFICEMD_PADDLE_PYTHON=$python"
Write-Output "OFFICEMD_PADDLE_MODELS=$models"
