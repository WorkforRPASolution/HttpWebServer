# HttpWebServer 테스트

설계: `docs/plans/2026-10-06-golden-master-test-design.md`
구현 계획: `docs/plans/2026-10-06-golden-master-test-plan.md`

운영 코드(`src/main`)를 바꾸지 않고, 현재 동작을 그대로 기록한 회귀 안전망이다. 결함이 있는 동작도 "현재 동작"으로 고정돼 있다. 앞으로 동작을 바꿀 때는 경계 TDD로 한다. 먼저 테스트나 골든 파일을 원하는 동작으로 고쳐 실패시킨 다음, 코드를 고친다.

## 실행

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home   # JDK 8 전용
mvn test                                    # 단위·라우팅 계층 84개. 컨테이너 불필요, mvn package 도 이것만 돈다
mvn test -Pgolden                           # 골든 계층까지 175개 (약 1분 30초)
mvn test -Pgolden -Dgolden.update=true      # 골든 파일 기록·갱신 → 반드시 git diff 로 검토
mvn test -Pgolden -Dsuites=com.sec.eeg.ars.golden.MailGoldenSpec   # 스위트 하나만
```

- 테스트 클래스 이름을 바꾸거나 지웠다면 `mvn clean test`로 돌린다. `target/test-classes`에 남은 옛 클래스도 실행되기 때문이다.
- JDK 8이 아니면 `JdkGuard`가 실패시킨다. JDK 8에서만 TIFF 변환 실패(KNOWN-ISSUE 1)가 운영과 같게 재현된다.

## 계층

| 계층 | 위치 | 외부 의존 | 검증 방식 |
|---|---|---|---|
| 단위 | `utils/`, `twirl/`, `data/`, `actor/`(기존 Resolver) | 없음 | 단언. Twirl만 골든 파일(`golden/HistoryTemplate/expected.html`) |
| 라우팅 | `endpoint/` | 없음 (내장 Jetty) | 단언. 라우트 16개, 응답 형식, 오류 응답, CORS |
| 골든 | `golden/` + `src/test/resources/golden/<메시지>/<케이스>/` | OrbStack Mongo·Cassandra | `expected.txt`와 비교. 액터 메시지 14개, 케이스 82개 |
| 하네스 | `testkit/` | 일부 Golden 태그 | 하네스 자체 테스트 |

## 골든 계층에 필요한 것

- OrbStack 컨테이너 두 개가 떠 있어야 한다. 자세한 설정은 `ARS/docker/README.md`를 본다.
  - `mongodb-44`(27017)
  - `my-cassandra-server`(9042, 인증 켜짐)
- 테스트는 전용 Mongo DB `HWS_GOLDEN`과 전용 키스페이스 `hws_golden`만 쓴다. `EARS`와 `ars`는 건드리지 않는다.
- Cassandra 스키마는 `ARS/docker/cassandra/ars-schema.cql`(운영 DESCRIBE 결과)을 직접 읽어 키스페이스 이름만 바꾼다.
- `SendEmail`의 기본 페이로드는 `ARS/ResourceMonitorServer/tests/data/akka_email_contract.json`(RMS 계약 파일)을 직접 읽는다.
- 접속 정보는 환경변수로 바꿀 수 있다.

| 환경변수 | 기본값 |
|---|---|
| `HWS_GOLDEN_MONGO_URL` | `mongodb://localhost:27017/?serverSelectionTimeoutMS=3000` |
| `HWS_GOLDEN_CASSANDRA_HOST` / `_PORT` | `127.0.0.1` / `9042` |
| `HWS_GOLDEN_CASSANDRA_USER` / `_PASSWORD` | README의 로컬 개발용 HttpWebServer 계정 |

## 골든 케이스 만들기·고치기

- 케이스 하나는 폴더 하나다: `src/test/resources/golden/<메시지>/<케이스>/case.json`. 기대 출력은 같은 폴더의 `expected.txt`다.
- `case.json` 필드
  - `message`: 액터 메시지 이름.
  - `base` + `set`/`unset`: 본문을 만드는 방법. `rms:legacy|rendered|grouped`는 RMS 계약 파일, 그 밖의 이름은 `_fixtures/payloads/<이름>.json`이다.
  - `bodyRaw`: JSON이 깨진 본문을 그대로 보낼 때.
  - `params`: GET 계열 메시지의 필드.
  - `fixtures`: 공용 사전 상태 이름. `_fixtures/<이름>.mongo.json`과 `.cassandra.json`을 읽는다.
  - `mongo` / `cassandra`: 케이스 전용 문서·행.
  - `mongoAfterStart`: 액터가 시작한 뒤에 넣을 문서.
  - `config`: `ServicePublicAddress` 등 설정값.
  - `knownIssue`: 결함 설명. `expected.txt` 머리말이 된다.
- `{"$file": "_files/favicon.png"}`는 어디에 쓰느냐에 따라 바뀐다. 본문에서는 base64, Mongo 문서에서는 바이너리, Cassandra 행에서는 blob이 된다.
- 템플릿 가져오기 케이스의 입력 폴더는 케이스 폴더의 `files/email/…`, `files/popup/…`에 둔다.
- 새 케이스를 추가하는 순서
  1. `case.json`을 만든다.
  2. `-Pgolden`으로 돌려 "골든 파일이 없다"로 실패하는지 본다.
  3. `-Dgolden.update=true`로 기록한다.
  4. `expected.txt`를 코드와 맞춰 검토한다.
  5. 다시 돌려 통과하는지 본다.
- 불일치가 나면 같은 폴더에 `actual.txt`가 남는다(git 제외). 의도한 변경일 때만 갱신 모드로 덮어쓰고, `git diff`로 검토한 뒤 커밋한다.

## 알려진 결함 (KNOWN-ISSUE)

현재 동작 그대로 고정해 두었다. 고칠 때는 해당 테스트나 골든 파일을 먼저 원하는 동작으로 바꾼다.

| # | 결함 | 고정한 곳 |
|---|---|---|
| 1 | JDK 8 ImageIO에 TIFF 리더가 없다. TIFF→JPEG 변환이 null이 되고 `.tif`·`.TIF`·`.tiff` 첨부 저장이 Failed가 된다 | `ImageUtilsSpec`, `SaveCustomsFile/save-tif-*`, `save-tiff` |
| 2 | URL 값을 CQL 문자열에 그대로 붙인다 (주입 위험) | `QueryHistory/query-history-injection`, `CustomFiles/customfiles-injection`, `SnapShotImage/snapshot-bad-crtime` |
| 3 | 이력 HTML을 이스케이프하지 않는다. `/ARS/AppendHistory`는 인증이 없다 | `HistoryTemplateSpec`, `AddHistory/add-history-script-text`, `QueryHistory/query-history-unescaped` |
| 4 | 메일 템플릿 조회·갱신 키에 `app`이 없다 | `SendEmail/legacy-app-nonars`, `LoadEmailTemplate/import-app-overwrite` |
| 5 | `RecoveryDefault`를 액터 시작 때 한 번만 읽는다 | `SendRecoveryEmail/recovery-wrapper-read-once` |
| 6 | 고정 필드(`hostname` 등)의 `$`가 치환 중 예외를 낸다 | `SendEmail/legacy-hostname-dollar` |
| 7 | `Master.WebServiceStart`의 부트스트랩 이름 `"ScalatraBootstrap"`으로는 Jetty가 뜨지 않는다. 옮겨 적기 오류로 추정한다 | `BootstrapLifecycleSpec` |
| 8 | 오류 응답에 내부 정보가 나간다. 스택 트레이스, 예외 내용, 전체 라우트 목록이다 | `HttpEndPointRoutingSpec` |

## 특이 동작 (결함으로 단정하지 않고 기록만 한 것)

- 대괄호가 겹치는 제목: 미리 렌더한 본문 경로는 `[EARS][[EARS] CPU CRITICAL - EQP001]…`, `ScriptResult`는 `[[EARS][Script 성공]][제목][장비]스크립트명:`.
- 치환되지 않는 토큰: RTM 메일의 `@IP`, 스냅샷이 없을 때 복구 메일의 `@__snapshot__`.
- 이름·폴더 처리: `indext-` 접두사 파일의 제목은 `-…`가 된다. `LoadEmailTemplate`는 폴더가 없어도 Success를 돌려준다. 팝업·이미지 문서가 중복이면 빈 응답이 된다.
- HTTP 응답 형식: 문자열 응답은 `text/plain`, 바이트 응답은 `application/octet-stream`으로 나간다. CORS 사전 요청(OPTIONS)은 405다.

## 문제 해결

| 증상 | 확인할 것 |
|---|---|
| `Mongo 에 접속하지 못했다` | `docker ps`에 `mongodb-44`가 있는지 |
| `Cassandra 에 접속하지 못했다` | `my-cassandra-server`가 떠 있는지, 인증 설정이 README의 "Cassandra (compose 밖)" 절과 같은지. 컨테이너를 다시 만들면 인증이 꺼진다 |
| `외부 파일을 찾지 못했다` | worktree가 `ARS/` 바로 아래에 있는지. `../docker`, `../ResourceMonitorServer`를 찾는다 |
| 지운 테스트가 계속 돈다 | `mvn clean test` |
| 골든 불일치 | 같은 폴더의 `actual.txt`와 `expected.txt`를 비교한다. 의도한 변경이면 갱신 모드로 기록하고 검토한다 |
