# OfficeMarkdown 검색 MCP 연결

## 앱에서 문서 찾기

로컬 빌드 후 `run-local.cmd`로 앱을 연다. ‘에이전트 검색’에서 원하는 제공자(Codex, Claude Code, GitHub Copilot)와 ‘MCP 문서 검색’을 선택한다. 에이전트 설정에서 CLI 실행 파일과 로그인을 확인할 수 있다. 색인된 전체 문서를 검색하며, 문서·구역을 선택하면 해당 범위로 좁힙니다. 추천 카드에는 에이전트가 조회한 원문과 대조한 인용을 표시한다.

## 외부 에이전트에서 문서 작성하기

OfficeMarkdown의 사용자별 공통 검색 색인을 연결한다. 앱 창을 닫아도 에이전트가 stdio 서버를 실행해 색인을 조회할 수 있다. 앱과 MCP는 같은 사용자의 전체 색인을 조회한다. 모델에는 도구가 반환한 제목·구역·발췌문과 인용용 원본 경로가 전달된다.

앱 오른쪽 위 **MCP 설정**에서 에이전트를 선택하고 서버 이름을 확인한 뒤 **등록**을 누른다. 같은 이름이 이미 있으면 다른 이름으로 등록한다. 등록 후 해당 에이전트의 새 세션을 연다. CLI 위치는 **에이전트 연결 설정**에서 지정할 수 있다.

수동 등록이 필요하면 로컬 빌드에서는 아래 PowerShell 명령으로 사용할 클라이언트에 한 번 등록한다. 각 명령은 해당 클라이언트의 사용자 설정에 `office` 서버를 추가한다. 기존에 같은 이름의 서버가 있다면 별도 이름을 선택한다. 서버 실행 스크립트는 매번 현재 로컬 빌드를 찾아 실행하므로 로컬 빌드를 갱신해도 등록 경로가 유지된다.

```powershell
$runner = (Resolve-Path .\run-index-mcp.ps1).Path
$serverArgs = @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',$runner)

codex mcp add office -- powershell.exe @serverArgs
claude mcp add --scope user office -- powershell.exe @serverArgs
copilot mcp add office -- powershell.exe @serverArgs
```

이 워크스페이스에 설치한 Copilot CLI를 PowerShell에서 쓰려면 위 명령의 `copilot`을 `.\local\tools\copilot\node_modules\@github\copilot-win32-x64\copilot.exe`로 바꾼다. 최초 인증은 같은 실행 파일의 `login` 명령으로 진행한다. 배포본은 설치 폴더의 `OfficeIndexMcp.exe`를 직접 서버 명령으로 등록하고 인수를 생략한다. 이전 등록의 `--library` 인수는 해당 폴더의 과거 색인을 가져오는 용도로 유지한다.

새 에이전트 세션에서 다음과 같이 요청한다.

> office MCP에서 IAM/SSO 사전조사서 예시를 찾아 계정·권한 및 인증 관련 시트를 읽어줘. 그 자료를 참고해 새 고객용 사전조사서.md를 작성해줘. 작성할 질문과 응답란을 표로 만들고, 고객별로 달라지는 값은 비워 둬. 마지막에 참고한 문서명, 시트명, 원본 링크를 적어줘.

`search`가 후보 문서를 찾고, `list_sections`가 시트나 슬라이드 목록을 반환한다. `read_evidence`는 실제 발췌문과 구역을 반환하며 외부 세션에는 `source_uri`를 함께 제공한다. 초안 파일은 외부 에이전트가 작업 폴더와 해당 세션의 쓰기 권한을 사용해 저장한다. 인용에는 세션이 끝나도 사용할 수 있는 문서명·구역·원본 URI를 쓴다.

## 검증 범위

동일한 테스트 보관함으로 앱의 근거 검색과 외부 CLI의 사전조사서 Markdown 초안 생성을 시험했다. Codex와 Claude Code는 실제 모델이 승인 담당자·예외 코드·원본 경로를 찾아 초안에 반영했다. GitHub Copilot은 설치된 정식 CLI와 로컬 모의 모델을 연결해 두 경로에서 실제 MCP 세 도구를 호출하고 근거가 반환되는 것을 확인했다. GitHub 호스팅 모델의 문서 작성은 해당 계정으로 로그인한 후 확인해야 한다.
