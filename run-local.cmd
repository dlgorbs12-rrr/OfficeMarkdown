@echo off
setlocal DisableDelayedExpansion
set "OFFICEMD_MCP_RUNNER=%~dp0run-index-mcp.ps1"
if exist "%~dp0local\tools\copilot\node_modules\@github\copilot-win32-x64\copilot.exe" set "PATH=%~dp0local\tools\copilot\node_modules\@github\copilot-win32-x64;%PATH%"
set "BUILD_ID="
if exist "%~dp0local\current.txt" set /p "BUILD_ID=" < "%~dp0local\current.txt"
if not defined BUILD_ID goto missing
set "APP=%~dp0local\apps\%BUILD_ID%\OfficeMarkdown\OfficeMarkdown.exe"
if not exist "%APP%" goto missing
if /i "%~1"=="--path" (
    echo "%APP%"
    exit /b 0
)
if not defined OFFICEMD_PADDLE_PYTHON if exist "%~dp0local\tools\ocr\.venv\Scripts\python.exe" set "OFFICEMD_PADDLE_PYTHON=%~dp0local\tools\ocr\.venv\Scripts\python.exe"
if not defined OFFICEMD_PADDLE_MODELS if exist "%~dp0local\tools\ocr\models" set "OFFICEMD_PADDLE_MODELS=%~dp0local\tools\ocr\models"
if /i "%~1"=="--library" (
    if "%~2"=="" exit /b 2
    if not exist "%~f2\." exit /b 2
    start "" "%APP%" --library "%~f2"
    exit /b
)
start "" "%APP%"
exit /b %errorlevel%

:missing
echo Local OfficeMarkdown is missing. Run build-local.ps1 first. 1>&2
exit /b 1
