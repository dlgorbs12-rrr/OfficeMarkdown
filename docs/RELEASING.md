# 개발과 릴리스

Windows x64에서 JDK 21, Maven 3.9를 사용합니다. `JAVA_HOME`은 JDK 폴더를 가리켜야 합니다. MSI를 만들려면 [WiX Toolset 3.14.1](https://github.com/wixtoolset/wix3/releases/tag/wix3141rtm)의 바이너리 폴더를 PATH에 추가해 `candle.exe`와 `light.exe`를 실행할 수 있게 합니다.

## 빌드와 검증

```powershell
mvn test
.\package.ps1 -Installer -Destination .\dist\2.4.0
```

새 출력 폴더를 사용하세요. 스크립트가 테스트를 다시 실행하고 독립 실행 폴더, MSI, SHA-256 파일을 생성합니다. Maven 경로는 `-Maven`으로 지정할 수 있습니다. 독립 실행 폴더만 필요하면 `-Installer`를 생략합니다.

설치본을 테스트 계정에 설치하고 시작 메뉴에서 실행합니다. 샘플 PPT와 여러 시트가 있는 Excel을 추가하고, 제목 및 본문 검색, MD 보기와 원본 열기를 확인합니다. 질문 기능은 테스트 문서와 인증된 CLI로 점검합니다. 업데이트 출시 전에는 이전 MSI 위에 새 MSI를 설치해 변환 자료와 시작 메뉴 항목을 확인합니다.

## 다음 버전 올리기

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
