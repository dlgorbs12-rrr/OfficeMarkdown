# 개발과 릴리스

Windows x64에서 JDK 21, Maven 3.9를 사용합니다. `JAVA_HOME`은 JDK 폴더를 가리켜야 합니다. MSI를 만들려면 [WiX Toolset 3.14.1](https://github.com/wixtoolset/wix3/releases/tag/wix3141rtm)의 바이너리 폴더를 PATH에 추가해 `candle.exe`와 `light.exe`를 실행할 수 있게 합니다.

## 프로젝트 폴더에 설치하고 실행하기

Windows x64에서 JDK 21 이상을 설치한 뒤 프로젝트 루트의 PowerShell에서 실행합니다.

```powershell
.\build-local.ps1
.\run-local.cmd
```

빌드는 `JAVA_HOME`이나 `PATH`에서 JDK를 찾습니다. 다른 JDK를 사용하려면 `-JdkHome 'C:\경로\JDK'`를 지정하세요. 처음 실행하면 Apache Maven 3.9.16을 내려받고 SHA-512를 검증한 뒤 `local/tools`에 설치합니다.

소스를 수정한 뒤 `build-local.ps1`을 다시 실행하면 테스트와 패키징을 거쳐 `local/apps` 아래에 새 실행본을 만듭니다. `run-local.cmd`는 검증에 성공한 최신 실행본을 엽니다. 각 실행본에 Java 런타임이 포함되며, 변환 자료와 검색 인덱스는 기존 Windows 계정의 `%LOCALAPPDATA%\OfficeMarkdown`에 저장됩니다.

```powershell
$app = (.\run-local.cmd --path).Trim('"')
& (Join-Path (Split-Path $app) 'OfficeSearch.exe') --help
```

## 빌드와 검증

대형 XLSX와 OCR 엔진의 비교 조건 및 적용 결정은 [성능 검증 기록](PERFORMANCE.md)에 정리했습니다. Rust 비교 프로토타입은 설치본에 포함하지 않으며, 앱은 Java 스트리밍 변환을 사용합니다.

```powershell
mvn test
.\package.ps1 -Installer -Destination .\dist\2.5.0
```

새 출력 폴더를 사용하세요. 스크립트가 테스트를 다시 실행하고 독립 실행 폴더, MSI, SHA-256 파일을 생성합니다. Maven 경로는 `-Maven`으로 지정할 수 있습니다. 독립 실행 폴더만 필요하면 `-Installer`를 생략합니다.

설치본을 테스트 계정에 설치하고 시작 메뉴에서 실행합니다. 샘플 PPT와 여러 시트가 있는 Excel을 추가하고, 제목 및 본문 검색, MD 보기와 원본 열기를 확인합니다. 질문 기능은 테스트 문서와 인증된 CLI로 점검합니다. 업데이트 출시 전에는 이전 MSI 위에 새 MSI를 설치해 변환 자료와 시작 메뉴 항목을 확인합니다.

## 다음 버전 올리기

앱 업데이트에 사용하는 정식 릴리스는 `vX.Y.Z` 태그로 게시하고 GitHub의 최신 릴리스로 지정합니다. 태그와 같은 버전의 `OfficeMarkdown-X.Y.Z.msi` 및 `.msi.sha256` 두 파일을 첨부합니다. 앱에 표시되는 버전과 설치본 버전은 모두 `pom.xml`에서 가져옵니다.

1. 기능별 브랜치에서 변경하고 테스트한 뒤 main에 병합합니다.
2. `pom.xml`의 프로젝트 버전(숫자 세 자리, 예: `2.4.1`)을 높이고 사용법 변경을 README에 기록합니다. Windows Installer가 기존 설치를 식별하는 `package.ps1`의 `--win-upgrade-uuid`는 유지합니다.
3. 새 버전 번호를 출력 폴더명에 사용해 설치본을 빌드하고 검증합니다. 코드와 문서를 커밋하고 main을 푸시합니다.
4. 검증한 커밋에 버전 태그를 붙이고 MSI와 해시를 Release의 Assets에 첨부합니다. 다음은 2.4.1 예시입니다.

```powershell
git tag v2.4.1
git push origin v2.4.1
gh release create v2.4.1 .\dist\2.4.1\OfficeMarkdown-2.4.1.msi .\dist\2.4.1\OfficeMarkdown-2.4.1.msi.sha256 --verify-tag --title "OfficeMarkdown 2.4.1" --generate-notes
```

소스 저장소에는 코드·빌드 스크립트·가이드만 커밋합니다. 사용자 문서, 색인, 인증 정보, 빌드 결과는 배포 자산과 분리합니다.

## 한글 정밀 OCR 설치

64비트 Python 3.12가 있는 Windows에서 프로젝트 루트의 PowerShell로 실행합니다.

```powershell
.\ocr\install-paddle.ps1
.\run-local.cmd
```

설치 스크립트는 프로젝트의 `local/tools/ocr`에 가상환경과 고정 버전의 한국어·영어 OCR 모델을 준비하고 모델 SHA-256을 확인합니다. 최초 설치에는 인터넷 연결이 필요합니다. 기존 `run-local.cmd`는 이 환경을 찾아 현재 실행본에만 설정을 전달합니다. 앱의 **그림 글자 인식**에서 **PaddleOCR · 한글 정밀 · 로컬**을 선택하세요. 기본 Windows OCR과 별도로 선택하는 모드이며, 첫 인식에는 모델을 로드하는 시간이 추가됩니다.

다른 설치 위치를 지정하려면 `-InstallRoot`를 사용합니다. 이 경우 스크립트가 출력한 `OFFICEMD_PADDLE_PYTHON`과 `OFFICEMD_PADDLE_MODELS` 경로를 앱 실행 환경에 지정해야 합니다. 설치본에서는 `app/ocr/install-paddle.ps1`을 사용할 수 있습니다. 모델이 없거나 손상되면 오류를 표시하며 인식 중에 자동으로 다운로드하지 않습니다. 변환 도중 OCR이 실패하면 해당 문서의 기존 Markdown을 유지합니다.
