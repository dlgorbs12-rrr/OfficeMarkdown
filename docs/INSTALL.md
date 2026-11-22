# Windows 설치와 업데이트

## 처음 설치할 때

프로젝트 폴더에서 다음 PowerShell 명령을 실행하면 최신 정식 릴리스의 Windows x64 MSI를 내려받고, GitHub 에셋 해시와 릴리스 체크섬을 대조한 뒤 Windows 설치 마법사를 엽니다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\install.ps1
```

소스 폴더가 없는 PC에서는 아래 명령을 PowerShell에 붙여 넣으세요. 저장소의 설치 스크립트를 임시 파일로 받아 실행합니다.

```powershell
$setup = Join-Path ([IO.Path]::GetTempPath()) ("OfficeMarkdown-{0}.ps1" -f [guid]::NewGuid())
try {
    Invoke-WebRequest -UseBasicParsing -ErrorAction Stop -Uri 'https://raw.githubusercontent.com/dlgorbs12-rrr/OfficeMarkdown/main/install.ps1' -OutFile $setup
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File $setup
} finally {
    [IO.File]::Delete($setup)
}
```

직접 다운로드하려면 다음 순서를 따르세요.

1. 이 저장소의 **Releases**에서 최신 정식 릴리스를 엽니다.
2. Assets에서 `OfficeMarkdown-버전.msi`를 내려받습니다. 같은 이름의 `.sha256` 파일에 배포자가 올린 해시가 있습니다. PowerShell에서 `Get-FileHash .\OfficeMarkdown-버전.msi -Algorithm SHA256`로 비교할 수 있습니다.
3. MSI를 실행해 현재 Windows 사용자 계정에 설치합니다. 시작 메뉴의 **OfficeMarkdown**으로 실행합니다.
4. **문서 추가 → 파일 추가**로 PPT나 Excel 파일을 변환한 뒤, 문서 제목 일부로 검색합니다. 원본 열기에는 해당 파일 형식과 연결된 Windows 앱이 필요합니다.

## 로컬 빌드로 검색 테스트

JDK 21 이상을 `JAVA_HOME`에 지정한 뒤 프로젝트 폴더에서 PowerShell로 실행합니다.

```powershell
.\build-local.ps1
.\run-local.cmd --library ".\정밀OCR결과"
```

`--library`에는 변환된 Markdown과 그림 자산이 있는 폴더를 지정합니다. 인자 없이 실행하면 기본 보관함을 엽니다. **에이전트 대화**에서 연결된 에이전트에게 필요한 페이지나 그림을 요청하면 Office MCP로 보관함을 조회합니다.

## 업데이트

3.3에서는 **도움말 → 업데이트 확인**, 2.7부터 3.2까지는 **업데이트 → 업데이트 확인**으로 현재 버전을 GitHub의 최신 정식 릴리스와 비교합니다. 시작할 때 확인할지는 같은 메뉴에서 설정합니다. 조회와 다운로드에는 인터넷 연결이 필요합니다.

MSI로 설치한 앱에서는 새 버전 알림에서 **업데이트**를 누릅니다. 앱이 릴리스에 첨부된 SHA-256 파일과 GitHub의 에셋 해시를 검사하고 자동으로 설치를 시작합니다. 실행 중인 변환이나 색인 갱신, 문서 질문이 끝나면 설치를 진행하세요. Windows 설치 마법사가 시작되면 앱이 종료됩니다. 설치를 마치고 시작 메뉴에서 다시 엽니다.

2.6.1 이하에서는 업데이트 메뉴가 있는 버전의 MSI를 한 번 직접 설치해야 합니다. 프로젝트의 `run-local.cmd` 실행본이나 복사한 실행 폴더는 새 릴리스 페이지를 열어 MSI로 설치할 수 있습니다. 로컬 빌드를 계속 사용할 때는 소스를 갱신한 뒤 `build-local.ps1`로 다시 빌드합니다.

## 기존 자료

이전에 압축을 풀거나 폴더를 복사해서 사용했다면 실행 중인 앱을 닫고 MSI를 설치한 뒤 시작 메뉴에서 실행하세요. 같은 계정의 변환 자료는 `%LOCALAPPDATA%\OfficeMarkdown\Markdown`에서 다시 읽습니다. 원본 파일을 옮긴 경우에는 새 위치에서 다시 추가하세요.

이후 업데이트는 새 버전의 MSI를 같은 계정에서 실행합니다. Windows **설정 → 앱 → 설치된 앱 → OfficeMarkdown**에서 앱을 제거할 수 있습니다. 자료를 백업할 때는 앱을 닫고 Markdown 폴더 전체와 원본 PPT·Excel 파일을 따로 보관하세요.

## 선택 기능 설정

한글 그림 검색에는 Windows 한국어 OCR 언어 기능이 필요합니다. 문서에 질문하려면 앱의 **에이전트 연결 설정**에서 CLI를 설치하고 로그인하세요. 검색 예시는 [빠른 시작](../README.md#첫-문서-검색하기), 지원 CLI와 연결 방법은 [에이전트 연결 안내](MCP_CLIENTS.md)를 확인하세요.
