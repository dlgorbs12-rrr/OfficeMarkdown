# Windows 설치와 업데이트

## 처음 설치할 때

1. 저장소에 접근할 수 있는 GitHub 계정으로 로그인하고 [Releases](https://github.com/dlgorbs12-rrr/OfficeMarkdown/releases/latest)를 엽니다.
2. Assets에서 `OfficeMarkdown-2.4.0.msi` 또는 최신 버전의 MSI를 내려받습니다. 같은 이름의 `.sha256` 파일에 배포자가 올린 해시가 있습니다. PowerShell에서 `Get-FileHash .\OfficeMarkdown-2.4.0.msi -Algorithm SHA256`로 비교할 수 있습니다.
3. MSI를 실행해 현재 Windows 사용자 계정에 설치합니다. 시작 메뉴의 **OfficeMarkdown**으로 실행합니다.
4. **파일 추가**로 PPT나 Excel 파일을 변환한 뒤, 문서 제목 일부로 검색합니다. 원본 열기에는 해당 파일 형식과 연결된 Windows 앱이 필요합니다.

## 기존 사용자

이전에 압축을 풀거나 폴더를 복사해서 사용했다면 실행 중인 앱을 닫고 MSI를 설치한 뒤 시작 메뉴에서 실행하세요. 같은 계정의 변환 자료는 `%LOCALAPPDATA%\OfficeMarkdown\Markdown`에서 다시 읽습니다. 원본 파일을 옮긴 경우에는 새 위치에서 다시 추가하세요.

이후 업데이트는 새 버전의 MSI를 같은 계정에서 실행합니다. Windows **설정 → 앱 → 설치된 앱 → OfficeMarkdown**에서 앱을 제거할 수 있습니다. 자료를 백업할 때는 앱을 닫고 Markdown 폴더 전체와 원본 PPT·Excel 파일을 따로 보관하세요.

## 선택 기능 설정

한글 그림 검색에는 Windows 한국어 OCR 언어 기능이 필요합니다. 문서에 질문하려면 앱의 **에이전트 설정**에서 CLI를 설치하고 로그인하세요. 외부 전송 범위, OCR 모드, 검색식과 CLI 예시는 [사용 가이드](../README.md)에 있습니다.
