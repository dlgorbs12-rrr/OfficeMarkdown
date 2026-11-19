param([string]$ImageFile, [switch]$Worker)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$null = [Windows.Storage.StorageFile,Windows.Storage,ContentType=WindowsRuntime]
$null = [Windows.Storage.Streams.IRandomAccessStream,Windows.Storage.Streams,ContentType=WindowsRuntime]
$null = [Windows.Graphics.Imaging.BitmapDecoder,Windows.Graphics.Imaging,ContentType=WindowsRuntime]
$null = [Windows.Media.Ocr.OcrEngine,Windows.Foundation,ContentType=WindowsRuntime]
$asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.IsGenericMethod -and $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation' + [char]96 + '1'
} | Select-Object -First 1
function Await($operation, $type) {
    $task = $asTask.MakeGenericMethod($type).Invoke($null,@($operation))
    $task.GetAwaiter().GetResult()
}
$engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
    if (!$engine) { throw 'Install a Windows OCR language pack.' }
function Read-Image([string]$ImageFile) {
    $stream=$null; $bitmap=$null
    try {
    $file = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync($ImageFile)) ([Windows.Storage.StorageFile])
    $stream = Await ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
    $decoder = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
    $bitmap = Await ($decoder.GetSoftwareBitmapAsync([Windows.Graphics.Imaging.BitmapPixelFormat]::Bgra8,[Windows.Graphics.Imaging.BitmapAlphaMode]::Ignore)) ([Windows.Graphics.Imaging.SoftwareBitmap])
    $result = Await ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])
        return ($result.Lines | ForEach-Object { $_.Text }) -join [Environment]::NewLine
    } finally { if($bitmap){$bitmap.Dispose()}; if($stream){$stream.Dispose()} }
}
if (!$Worker) { [Console]::WriteLine((Read-Image $ImageFile)); exit }
while ($null -ne ($request = [Console]::ReadLine())) {
    try {
        $path = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($request))
        $text = Read-Image $path
        $status = 'OK '
    } catch { $text = $_.Exception.Message; $status = 'ERR ' }
    [Console]::WriteLine($status + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes([string]$text)))
}
