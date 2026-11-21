# OfficeMarkdown 3.0

Windows에서 PowerPoint, Excel, Word, PDF를 Markdown으로 변환하고 검색하는 앱입니다. 문서 본문과 그림 속 글자를 찾거나, AI에 작성할 문서를 설명해 참고할 슬라이드·시트·페이지를 찾을 수 있습니다.

## 설치

Windows x64에서 **PowerShell**을 열고 아래 명령을 실행하세요. 최신 정식 설치 파일의 SHA-256을 확인한 뒤 설치 마법사가 열립니다. 설치가 끝나면 시작 메뉴에서 **OfficeMarkdown**을 실행하세요. Java와 Office는 별도로 설치할 필요가 없습니다.

```powershell
$setup = Join-Path $env:TEMP ("OfficeMarkdown-{0}.ps1" -f [guid]::NewGuid())
Invoke-WebRequest -UseBasicParsing -ErrorAction Stop -Uri 'https://raw.githubusercontent.com/dlgorbs12-rrr/OfficeMarkdown/v3.0.0/install.ps1' -OutFile $setup
powershell.exe -NoProfile -ExecutionPolicy Bypass -File $setup
```

직접 설치하려면 [최신 릴리즈](https://github.com/dlgorbs12-rrr/OfficeMarkdown/releases/latest)에서 `.msi` 파일을 받으세요. 기존 사용자는 앱의 **업데이트 → 업데이트 확인**에서 새 버전을 설치할 수 있습니다.

## 첫 문서 검색하기

1. **문서 추가 → 파일 추가** 또는 **폴더 추가**로 자료를 변환합니다. **변환 결과 보기**를 누르면 내장 Markdown 뷰어에서 표·목록·그림을 확인할 수 있습니다.
2. 찾으려는 자료에 맞춰 검색합니다.

   | 검색 | 입력 예시 |
   |---|---|
   | **키워드 검색** | `결과점검 체크리스트`처럼 제목이나 본문의 단어를 입력합니다. |
   | **이미지 검색** | `영구 삭제 사유 입력`처럼 화면에 보이는 문구나 그림 설명을 입력합니다. |
   | **에이전트 검색** | “착수보고서에 넣을 작업 일정표를 찾아줘”라고 질문하고 추천 근거와 출처를 확인합니다. 처음에는 **에이전트 연결 설정**에서 지원 CLI를 연결하고 로그인하세요. |

3. 결과를 선택하면 해당 슬라이드·시트·페이지를 읽을 수 있습니다. 원래 파일은 **원본 열기**로 엽니다.

**그림 검색을 보강하려면** 변환할 때 **그림 글자 인식**을 켜거나, 기존 자료에서 **보관함 관리 → 그림 검색 색인 보강**을 실행하세요. 글자가 깨진 문서는 **보관함 → 저품질 OCR 후보 찾기 → 후보 전체 정밀 OCR**로 한꺼번에 다시 읽을 수 있습니다. 정밀 OCR에는 로컬 한국어 모델이 필요합니다. [한글 정밀 OCR 설치](docs/RELEASING.md#한글-정밀-ocr-설치)

기본 보관함은 `%LOCALAPPDATA%\OfficeMarkdown\Markdown`입니다. 키워드·이미지 검색은 로컬 색인을 사용하며, AI 검색에서는 질문과 검색 방식에 필요한 발췌문이 연결된 모델에 전달됩니다.

[Windows 설치·업데이트](docs/INSTALL.md) · [AI 에이전트와 MCP 연결](docs/MCP_CLIENTS.md) · [개발·빌드·배포](docs/RELEASING.md)
