# HttpWebServer 골든 마스터 테스트 설계

- 작성일: 2026-10-06
- 상태: 구현 완료 (2026-10-07, 브랜치 test/golden-master). 최종 검토를 반영하며 하네스 테스트가 늘어 기본 87개, `-Pgolden` 179개다(8장 기준 84·175에서 기본 +3, Golden 태그 +1). 실행·갱신 방법은 docs/testing.md
- 갱신 (2026-10-06): 계획을 쓰며 실험으로 확인한 사실을 반영했다. 바뀐 곳은 3장의 KNOWN-ISSUE 7·8과 특이 동작, 4.1·4.2, 5.2, 6장이다.
- 기준 코드: `email-group-routing`의 `3e5c765`. PR #1 머지분과 Cassandra 키스페이스·컬럼 수정이 들어 있고, 아직 push 전이다.
- 작업 브랜치: `test/golden-master`. worktree는 `ARS/HttpWebServer-golden`이다.
- 관련 문서
  - `ARS/docker/README.md`: 로컬 Mongo·Cassandra 환경
  - `ARS/docker/cassandra/ars-schema.cql`: 운영 `ars` 키스페이스 스키마
  - `ARS/ManualStudio/manual/operator/components/httpwebserver.md`: 엔드포인트·호출자·페이로드
  - `ARS/ManualStudio/manual/operator/appendix/known-issues.md`: 알려진 결함

## 1. 목적과 범위

지금 있는 테스트는 스펙 4개, 테스트 14개다. 다루는 범위는 순수 함수 2개(`EmailBodyResolver`·`EmailRoutingResolver`, 78줄)와 `EmailHttpDataFormat` 파싱뿐이다. 골든 마스터는 없다.

이 작업의 목적은 **운영 코드를 바꾸지 않고** 현재 동작을 그대로 기록하는 회귀 안전망을 만드는 것이다. 이후 변경은 이 안전망 위에서 경계 TDD로 한다. 경계 TDD란 실패하는 경계 테스트를 먼저 쓰고 그다음에 구현하는 방식이다.

범위 안:
- 단위 계층: 외부 의존이 없는 코드. `ImageUtils`, Twirl `history`, JSON 포맷 6종이 여기에 해당한다.
- 라우팅 계층: `HttpEndPoint` 라우트 16개.
- 골든 계층: 액터 메시지 14개(`EmailWorker` 11개, `HttpWorker` 3개). 스냅샷도 포함한다.

범위 밖:
- 운영 코드 리팩터링(순수 함수 추출)과 결함 수정.
- 기동·배선 코드.
  - `Master`: ZooKeeper 등록, Jetty, 8000번 포트의 `ShutdownServer`.
  - `ServiceConfig.load`: 값이 없으면 `System.exit`를 호출한다.
  - `RedisActor`: Redis 발행.
- 아무 데서도 쓰지 않는 코드: `HttpConnector.SendShutdown`, `RedisSetData`, `RedisPubMessage`. 테스트하지 않고 삭제 후보로 남긴다.

## 2. 결정 사항 (2026-10-06 사용자 합의)

| # | 결정 | 이유 |
|---|---|---|
| D1 | `src/main`은 고치지 않는다. 테스트 코드와 골든 파일만 만든다 | 현재 동작을 먼저 고정한다. 리팩터링은 그 보호 아래에서 나중에 한다 |
| D2 | `pom.xml`은 테스트 설정과 패키징 수정까지 고친다. 패키징 수정은 별도 커밋으로 한다 | 지금 구조에서는 테스트 의존성이 배포 lib(`target/lib`)에 섞여 들어간다 |
| D3 | DB는 OrbStack에 떠 있는 기존 컨테이너 `mongodb-44`와 `my-cassandra-server`를 쓴다 | Cassandra 인증과 스키마가 이미 운영과 같게 맞춰져 있다 |
| D4 | Cassandra 스키마는 테스트가 `ARS/docker/cassandra/ars-schema.cql`을 직접 읽는다 | 운영 `DESCRIBE KEYSPACE ars`의 결과다. 사본을 두지 않으니 운영과 어긋날 일이 없다. RMS 계약 파일과 같은 방식이다 |
| D5 | Cassandra 경로도 범위에 넣는다 | 로컬 스키마가 운영과 같고, 코드도 `3e5c765`에서 이 스키마에 맞게 고쳐졌다 |

## 3. 설계에 영향을 주는 사실

**JDK 8이 필수다. 컴파일 때문만이 아니라 실행 동작이 달라지기 때문이다.**
- JDK 8의 ImageIO에는 TIFF 리더가 없다. 그래서 정상 TIFF를 넣어도 `ImageUtils.convertTiffBytesToJpegBytes`가 `null`을 돌려준다(2026-10-06 실험).
- JDK 16에는 TIFF 리더가 있다. 테스트를 새 JDK로 돌리면 운영에서 나는 실패가 가려진다.
- 운영 런타임도 JDK 8이다(Dockerfile 기준 `alpine-java:8`). 코드가 `sun.misc.BASE64Decoder`를 쓰므로, 코드를 고치지 않고는 JDK 9 이상으로 올릴 수 없다.

**전역 가변 상태가 있다.**
- `ServiceConfig`의 `var` 전부. 특히 `database`, `ServicePublicAddress`, 템플릿 폴더 경로.
- `Master.system`.

**액터를 절대 경로로 찾는다.** `/user/Master`, `/user/Master/HttpWorker`, `/user/Master/EmailWorker`, `/user/Master/RedisActor`.

**Cassandra 키스페이스 이름이 코드에 박혀 있다.** `HttpWorker`와 `EmailWorker` 모두 `connect("ars")`를 호출한다.

**공유 인스턴스를 쓴다.**
- `mongodb-44`(27017번 포트, 인증 없음)
  - `EARS`에 개발 데이터가 있다: 템플릿 13건, 수신자 3건, 장비 82건, 복구 이력 15만 건.
  - 이 밖에 `EARS_dev`, `WEB_MANAGER` 등을 여러 프로젝트가 함께 쓴다.
- `my-cassandra-server`(9042번 포트)
  - 2026-10-06부터 `PasswordAuthenticator`로 인증이 켜져 있다.
  - `ars` 키스페이스는 비어 있다. `image_storage`·`snapshot_test`는 UISimulator가 쓴다.

**알려진 결함(KNOWN-ISSUE로 기록한다)**
1. JDK 8에서 TIFF 변환이 실패한다. 그래서 `.tif`·`.TIF`·`.tiff` 첨부 저장은 Failed가 된다. `3e5c765`부터 확장자를 소문자로 비교하기 때문에 대문자 `.TIF`도 여기에 포함된다.
2. CQL 주입: `QueryHistory`·`CustomFiles`·`SnapShotImage`가 URL로 받은 값을 쿼리 문자열에 그대로 붙인다.
3. 이력 HTML을 이스케이프하지 않는다. Twirl이 `@Html`로 출력하고, 입력 경로인 `/ARS/AppendHistory`에는 인증이 없다.
4. 메일 템플릿을 갱신하고 조회하는 키에 `app`이 빠져 있다. 그래서 앱이 달라도 같은 문서를 덮어쓰거나 읽는다.
5. 복구 메일을 감싸는 템플릿(`RecoveryDefault`)을 액터가 시작할 때 한 번만 읽는다.
6. 고정 필드(`hostname` 등) 값에 `$`가 있고 템플릿에 그 필드의 토큰이 있으면, 치환 중에 예외가 나서 Fail이 된다. 변수 값은 `quoteReplacement`로 감싸서 괜찮다.
7. **HTTP 서버가 기동하지 않는다.**
   - `Master.WebServiceStart`는 Scalatra 부트스트랩을 `"ScalatraBootstrap"`(패키지 없음)으로 등록한다. 그런데 실제 클래스는 `com.sec.eeg.ars.ScalatraBootstrap`이다.
   - 그래서 Jetty 기동이 `No lifecycle class found!`로 실패하고, `Master.preStart`도 실패한다(2026-10-06 실험).
   - 운영은 정상 동작하므로, 오늘 고친 `ears`·`years`와 같은 옮겨 적기 오류로 추정한다.
8. **오류 응답에 내부 정보가 그대로 나간다.**
   - 숫자 파라미터 오류: 500 본문에 스택 트레이스.
   - 액터가 10초 안에 응답하지 않을 때: 500 본문에 예외 내용.
   - 없는 경로: 404 본문에 전체 라우트 목록.

**결함이라고 단정하기 어려운 특이 동작 (표시 없이 그대로 기록한다)**
- 제목이 대괄호로 겹친다.
  - 미리 렌더한 본문 경로는 RMS 제목에 이미 `[EARS]`가 있는데 그 앞에 `[EARS]`를 또 붙인다: `[EARS][[EARS] CPU CRITICAL - EQP001]…`. EmailingAgent가 이 문자열을 어떻게 해석하는지는 확인이 필요하다.
  - `ScriptResult` 제목은 `[[EARS][Script 성공]][제목][장비]스크립트명:`이다.
- 치환되지 않고 남는 토큰이 있다.
  - RTM 메일은 `@IP`를 치환하지 않는다.
  - 복구 메일은 스냅샷이 없으면 `@__snapshot__`을 지우지 않는다.
- `indext-` 접두사가 붙은 파일은 제목이 `-…`이 된다. `LoadEmailTemplate`는 폴더가 없어도 Success를 돌려준다.
- 같은 조건의 팝업·이미지 문서가 중복이면 빈 응답이 된다.
- HTTP 응답 형식
  - 문자열 응답(HTML 포함)은 `text/plain`, 바이트 응답(이미지 포함)은 `application/octet-stream`으로 나간다.
  - CORS는 요청한 Origin을 그대로 허용하고 자격 증명도 허용한다. 하지만 사전 요청(OPTIONS)에는 405를 돌려준다.

## 4. 테스트 구조

| 계층 | 대상 | 외부 의존 | 검증 방식 | 실행 |
|---|---|---|---|---|
| 단위 | `ImageUtils`, Twirl `history`, JSON 포맷 6종, 기존 Resolver 2개, JDK 검사 | 없음 | 단언. Twirl만 골든 파일 | `mvn test` |
| 라우팅 | `HttpEndPoint` 16개 라우트 | 없음 | 단언 | `mvn test` |
| 골든 | 액터 메시지 14개 | Mongo, Cassandra | 골든 파일 | `mvn test -Pgolden` |

### 4.1 라우팅 계층
- 운영(`Master.WebServiceStart`)과 같은 구성으로 띄운다. Jetty `WebAppContext`에 `ScalatraListener`를 달고, 실제 `ScalatraBootstrap`이 `Master.system`(테스트가 넣는 전역 값)으로 `HttpEndPoint`를 마운트한다. 포트는 임의로 잡는다.
- 다른 점은 하나다. 부트스트랩 이름을 패키지까지 적는다(`com.sec.eeg.ars.ScalatraBootstrap`). 운영 값으로는 기동이 실패하기 때문이다(KNOWN-ISSUE 7). 운영 값으로 기동이 실패하는 동작은 별도 테스트로 고정한다.
- 테스트용 `ActorSystem`의 `/user/Master`에 가짜 `Master`를 띄운다. 그 아래 `HttpWorker`·`EmailWorker` 자리에는 받은 메시지를 기록하고 미리 정한 응답을 돌려주는 가짜 액터를 둔다.
- HTTP 호출에는 이미 의존성에 있는 Apache HttpClient를 쓴다.

### 4.2 골든 계층
- 진짜 `EmailWorker`와 `HttpWorker`를 운영과 같은 경로(`/user/Master/EmailWorker`, `/user/Master/HttpWorker`)에 띄운다. `RedisActor` 자리에는 `EmailFormat`을 기록하는 가짜 액터를 둔다.
- Mongo는 `ServiceConfig.database`에 테스트 DB를 넣는 방식으로 연결한다. 이 값은 운영에서도 바깥(`Master`)에서 넣는 전역 변수라서, 운영 코드를 고칠 필요가 없다.
- Cassandra는 실행할 때마다 테스트 키스페이스 `hws_golden`을 만든다. 만드는 방법은 `ars-schema.cql`의 키스페이스 이름 `ars`를 `hws_golden`으로 바꿔 실행하는 것이다.
  - 액터에 넘기는 `Cluster`는 실제 `Cluster`를 Mockito spy로 감싼 것이다. 이 spy가 `connect("ars")` 호출을 `connect("hws_golden")`으로 돌린다.
  - spy는 `Mockito.mock(classOf[Cluster], withSettings().spiedInstance(…).defaultAnswer(CALLS_REAL_METHODS))`로 만든다. Mockito 4.11의 `spy`는 Scala 2.11에서 오버로드가 모호해 컴파일되지 않는다.
  - 케이스 사이에 테이블을 비울 때는 `TRUNCATE`를 쓰지 않고 파티션 단위 `DELETE`를 쓴다. `TRUNCATE`는 공유 컨테이너에 스냅샷을 쌓기 때문이다.
  - 로컬 `ars` 키스페이스는 건드리지 않는다.
- `EmailWorker` 생성자의 `conf` 인자는 실제로 쓰이지 않으므로 빈 설정을 넘긴다.
- 케이스 하나는 다음 순서로 실행한다.
  1. 테스트 Mongo DB와 테스트 키스페이스의 테이블을 비운다.
  2. 공용 fixture를 넣고, 케이스별 문서·행을 넣는다. 템플릿 가져오기 케이스라면 임시 폴더에 입력 폴더 구조를 만든다.
  3. `ServiceConfig` 값을 설정한다.
  4. 액터를 새로 띄운다. `EmailWorker`는 시작할 때 `RecoveryDefault`를 읽으므로, 반드시 2번 다음에 띄운다.
  5. 메시지를 보내고 응답과 `EmailFormat`을 모은다(5.6).
  6. 실행 전후 DB와 폴더를 비교해 `expected.txt` 형식으로 만든 뒤 비교한다.
  7. 액터를 멈추고 `ServiceConfig` 값을 되돌린다.

### 4.3 격리 장치
- JDK 검사: `java.version`이 `1.8`로 시작하지 않으면 모든 테스트보다 먼저 실패한다.
- Mongo 테스트 DB 이름은 `HWS_GOLDEN`, Cassandra 테스트 키스페이스 이름은 `hws_golden`으로 고정한다. 지우거나 비우기 직전에 이 이름이 맞는지 다시 확인하고, 다르면 멈춘다.
- 테스트마다 `ServiceConfig` 값을 설정하고, 끝나면 원래 값으로 돌려놓는다. 스위트는 순서대로 하나씩 실행한다.
- 접속 정보는 환경변수로 바꿀 수 있다.
  - Mongo: `HWS_GOLDEN_MONGO_URL` (기본 `mongodb://localhost:27017`)
  - Cassandra: `HWS_GOLDEN_CASSANDRA_HOST` (기본 `localhost`), `HWS_GOLDEN_CASSANDRA_PORT` (기본 `9042`), `HWS_GOLDEN_CASSANDRA_USER`, `HWS_GOLDEN_CASSANDRA_PASSWORD`
  - 계정 기본값은 `ARS/docker/README.md`에 적힌 로컬 개발용 HttpWebServer 계정이다.
- 접속에 실패하면 원인 확인에 필요한 내용을 메시지에 담는다. 예를 들어 컨테이너가 떠 있는지, Cassandra 인증 설정은 README의 어느 절을 보면 되는지 알려 준다.

### 4.4 실행 방법

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home
mvn test                                  # 단위·라우팅 (컨테이너 불필요, mvn package도 이것만 실행)
mvn test -Pgolden                         # 골든까지 전부 (mongodb-44, my-cassandra-server 필요)
mvn test -Pgolden -Dgolden.update=true    # 골든 파일 갱신 → git diff로 검토
mvn test -Pgolden -Dsuites=<스위트 클래스>  # 스위트 하나만
```

## 5. 골든 파일 형식

### 5.1 파일 배치

```
src/test/resources/golden/
  _fixtures/
    email-base.mongo.json        여러 케이스가 함께 쓰는 사전 상태 (컬렉션 → 문서 목록)
  <메시지>/<케이스>/
    case.json                    입력
    expected.txt                 기대 출력
    files/                       템플릿 가져오기 케이스에서만 쓰는 입력 폴더 구조
  HistoryTemplate/expected.html  Twirl 단위 테스트용 골든 파일
```

### 5.2 `case.json` (입력)

```json
{
  "message": "SendEmail",
  "body": { "hostname": "EQP001", "...": "..." },
  "fixtures": ["email-base"],
  "mongo": { "EMAIL_TEMPLATE_REPOSITORY": [ { "...": "..." } ] },
  "cassandra": { "historylog": [ { "...": "..." } ] },
  "config": { "ServicePublicAddress": "hws.golden:8080" },
  "knownIssue": "결함일 때만 적는 설명"
}
```

- 본문은 `base`에서 출발한다.
  - `"rms:legacy"`, `"rms:rendered"`, `"rms:grouped"`는 RMS 계약 파일의 페이로드를 그대로 쓴다.
  - 그 밖의 이름은 `_fixtures/payloads/<이름>.json`을 쓴다.
  - 그 위에 `set`(덮어쓸 최상위 필드)과 `unset`(지울 필드)을 적용한다. JSON이 깨진 케이스는 `bodyRaw` 문자열에 넣는다.
- `params`: GET 계열 메시지의 필드다. 예: `{ "eqpid": "EQP001", "crtime": "1700000000000" }`. 조회 대상 행은 `cassandra`에 고정 시각으로 미리 넣는다.
- `fixtures`: `_fixtures/<이름>.mongo.json`과 `_fixtures/<이름>.cassandra.json`을 차례로 합친다.
- `mongoAfterStart`: 액터가 시작한 뒤에 넣을 문서다. 시작할 때 한 번만 읽는 동작(KNOWN-ISSUE 5)을 보이는 데 쓴다.
- `{"$file": "_files/favicon.png"}`: 어디에 쓰느냐에 따라 다른 값으로 바뀐다. 본문에서는 base64 문자열, Mongo 문서에서는 바이너리, Cassandra 행에서는 `0x…` blob이 된다.

### 5.3 `expected.txt` (출력)

```
# case: SendEmail/legacy-subcode-fallback
# KNOWN-ISSUE: ...          (해당할 때만)
== reply ==
== redis ==
== mongo ==
== cassandra ==
== files ==
```

- `reply`: 액터의 응답이다.
  - 문자열은 그대로 적는다.
  - 바이트 배열은 `<N bytes sha256:앞 12자리>`로 적는다.
  - Twirl `Html`은 본문을 적는다.
  - Scalatra 응답 객체(`InternalServerError` 등)는 상태 코드와 본문을 적는다.
- `redis`: 받은 `EmailFormat` 하나마다 `EmailFormat(project=…, category=…)` 한 줄과 본문 전체를 적는다.
- `mongo`: 실행 전후 컬렉션을 비교해 추가·수정·삭제된 문서를 적는다. 문서는 키를 정렬한 JSON 한 줄로 쓴다.
- `cassandra`: 테스트 키스페이스의 테이블별로 실행 전후 차이를 적는다. blob은 `<N bytes sha256:…>`로 쓴다.
- `files`: 템플릿 폴더가 어디로 옮겨졌는지 상대 경로로 적는다.
- 변경이 없는 구역은 `(변경 없음)`으로 적는다. 로그는 기록하지 않는다.

### 5.4 정규화
- `System.currentTimeMillis`로 만든 스냅샷 시각은 처음 나온 순서대로 `<T1>`, `<T2>`로 바꾼다. 메일 본문의 링크와 Cassandra 행에 같은 `<Tn>`이 찍혀야 하므로, 둘이 연결돼 있는지도 함께 검증된다.
- Mongo `_id`는 지운다. 바이너리는 길이와 해시로만 적는다.
- 컬렉션과 테이블 안의 문서·행은 정렬해서 적는다.

### 5.5 비교, 갱신, 검토
- 평소에는 비교만 한다. 다르면 같은 폴더에 `actual.txt`를 쓰고(git 제외) 차이를 보여 주며 실패한다. `expected.txt`가 아예 없을 때도 같은 방식으로 실패한다.
- `-Dgolden.update=true`로 실행하면 `expected.txt`를 덮어쓰고 통과한다. 바뀐 파일은 `git diff`로 사람이 검토한 뒤에 커밋한다.
- 처음 기록할 때도 결과를 그대로 믿지 않는다. 케이스마다 결과를 검토하고, 결함이면 `knownIssue`를 채운다.
- 결함은 경계 TDD로 고친다.
  1. `expected.txt`를 원하는 동작으로 먼저 고친다. 테스트가 실패한다(RED).
  2. 코드를 고친다(GREEN).
  3. `knownIssue`를 지운다.

### 5.6 액터 응답 수집 규칙
- 응답은 `ask`로 받는다. 타임아웃은 5초다.
- 코드는 `EmailFormat`을 응답보다 먼저 보낸다. 하지만 다른 액터를 거쳐 도착하므로, 응답을 받은 뒤 기록용 액터에서 최대 1초 더 기다려 모은다. `EmailFormat`이 오지 않아야 하는 케이스는 0.5초 동안 아무것도 오지 않는지 확인한다.
- 응답한 뒤 예외를 다시 던지는 경로(`DriverException`)는 응답만 기록한다. 그 뒤의 액터 재시작은 기록하지 않는다.

### 5.7 데이터 원칙
- 값은 모두 만든 것만 쓴다(`EQP001`, `MODEL-A`, `PHOTO`, `EMAIL-TEST-…`). 운영 데이터, 실제 장비 ID, 실제 수신자 카테고리는 넣지 않는다.
- 기본 페이로드의 모양은 실제 호출자가 요청을 만드는 코드에서 가져온다. 대상은 RMS 계약 파일, ARSAgent, CommandServer, AgentWatchdog, InterfaceServer다.

## 6. 케이스 목록

### 6.1 단위 계층

| 대상 | 케이스 |
|---|---|
| `ImageUtils` | TIFF → `null` (KNOWN-ISSUE 1). PNG → JPEG(크기 유지). 깨진 바이트·빈 배열 → `null` |
| Twirl `history` | 본문 HTML 골든 파일. `<script>`가 이스케이프 없이 나간다 (KNOWN-ISSUE 3) |
| JSON 포맷 6종 | 정상 추출. 필수 필드가 없거나 타입이 틀리면 `MappingException`. 모르는 필드는 무시. `HttpResponse`의 메시지가 `null`일 때 출력 |
| JDK 검사 | `java.version`이 1.8이 아니면 실패 |
| 기존 스펙 4개 | 그대로 둔다 |

### 6.2 라우팅 계층

- 라우트마다 확인하는 것:
  - 어느 액터(`HttpWorker`·`EmailWorker`·`Master`)로 어떤 메시지와 파라미터가 가는가.
  - `/ARS/SnapshotImage`(소문자 별칭)가 대문자 경로와 같은 메시지로 가는가.
- 모든 라우트에 공통으로 확인하는 것:
  - 응답 종류별 HTTP 결과: JSON 문자열, 바이트, HTML, 500.
  - `txn`·`year`·`month`가 숫자가 아닐 때의 응답.
  - 한글이나 공백이 들어간 경로 파라미터.
  - 액터가 10초 안에 응답하지 않을 때. 느린 케이스라서 1건만 둔다.
  - CORS 헤더.
  - 없는 경로, 대소문자만 다른 경로.
  - `/EARS/kill`이 `Master`로 `ShutDown`을 보내고 200을 돌려주는지.

### 6.3 골든 계층

| 메시지 | 주요 케이스 |
|---|---|
| `SendEmail` | 아래 목록 참고 |
| `SendEmailForRTM` | 템플릿, 토큰, 수신자. 제목 형식 `[EARS][제목][eqpid][code]`. 템플릿 없음, 수신자 없음 |
| `SendRecoveryEmail` | 감싸는 템플릿 `RecoveryDefault`가 있을 때와 없을 때(`@contents` 치환). 스냅샷. 수신자. 템플릿을 시작할 때 한 번만 읽음 (KNOWN-ISSUE 5) |
| `ScriptResult` | `SC_PROPERTY`로 발송 끄기(성공·실패 각각). 속성이나 문서가 없으면 예외를 삼키고 발송. 발송을 끄면 빈 응답. 제목의 이중 괄호 `[[EARS][Script 성공]]…` |
| `PopupContent` / `PopupContentV2` | 있음, 없음, 중복(빈 응답). V2는 `"html,noSendEmail"` 형식이고, 값이 없으면 `false` |
| `EmailImage` | 있음, 없음, 중복 |
| `LoadEmailTemplate` | 아래 목록 참고 |
| `LoadPopupTemplate` | 폴더명 `process^model^code`. `index.html`이 있어야 함. BOM 제거. 이미지 등록. `_workdone`으로 이동. 폴더가 없으면 Failed |
| `AddHistory` | 이력 HTML 한 칸 조립과 Cassandra 저장. JSON이 깨진 경우 |
| `QueryHistory` | 여러 단계를 단계 순서대로 이어 붙인 HTML. 데이터가 없으면 500. 저장된 HTML이 그대로 출력됨 (KNOWN-ISSUE 3) |
| `SaveCustomsFile` | 일반 파일 저장. `.tif`·`.TIF`·`.tiff` → Failed (KNOWN-ISSUE 1). 확장자 없음. `fname`이 null |
| `CustomFiles` / `SnapShotImage` | 있음, 없음. 따옴표가 든 값으로 쿼리가 깨지는 경우 (KNOWN-ISSUE 2) |

`SendEmail` 케이스:
- 미리 렌더한 본문(`renderedBody`) 경로. 수신자 직접 지정(`emailCategory`). `displayId` 헤드라인.
- 템플릿 3단계 대체 조회: 정확히 일치 → subcode `_` → code `_`. 템플릿 없음.
- 토큰 치환 전부.
  - `@Line`: `EQP_LINE_MAP`에 매핑이 있을 때와 없을 때.
  - `@Sdwt`.
  - `@CODE`: subcode가 있을 때와 없을 때.
  - `$`가 든 값: 변수 값은 안전하고, `hostname`은 Fail이 된다 (KNOWN-ISSUE 6).
- 수신자 4단계 대체 조회와 `EQP_INFO` 대체 조회. 수신자 없음.
- 스냅샷 첨부: Cassandra에 저장되고 본문 링크는 `<T1>`이 된다.
- `app`에 따른 project 이름: `ARS`가 들어 있으면 EARS, 아니면 그대로.
- `app`이 달라도 같은 템플릿을 읽음 (KNOWN-ISSUE 4).
- JSON이 깨진 경우, 필드가 없는 경우.

`LoadEmailTemplate` 케이스:
- 폴더명 `app^process^model^code[^subcode]` 해석. 이름이 잘못된 폴더와 `_workdone`은 건너뛴다. html이 없는 폴더는 옮기지 않는다.
- 이미지를 등록하고 본문의 이미지 링크를 치환한다.
- `indext-` 접두사 처리.
- `_workdone`으로 이동한다. 같은 이름이 이미 있으면 바꿔 넣는다.
- 폴더가 없어도 Success를 돌려준다.
- `app` 없이 덮어쓴다 (KNOWN-ISSUE 4).

규모는 골든 82개, 라우팅 26개다. 케이스별 `case.json`과 검토 포인트는 구현 계획서가 정본이다.

결함으로 단정하기 어려운 특이 동작은 `knownIssue` 없이 기록만 하고, 고칠지는 나중에 정한다. 해당하는 것은 다음과 같다.
- `ScriptResult` 제목의 이중 괄호
- `indext-` 접두사 처리
- `LoadEmailTemplate`가 폴더가 없어도 Success를 돌려주는 것
- 같은 조건의 템플릿·팝업·이미지 문서가 중복일 때의 동작

## 7. pom, 외부 파일, 브랜치

### 7.1 pom 변경 (커밋 두 개)

커밋 1. 패키징 수정. 배포물이 바뀌는 커밋이다.
- `maven-dependency-plugin`의 `copy-dependencies`에 `<includeScope>runtime</includeScope>`를 넣는다.
- `<dependencyManagement>`로 `org.scala-lang:scala-reflect`를 `${scala.version}`(2.11.8)에 고정한다.
- 확인할 것:
  - `mvn dependency:list`에서 scala-reflect가 2.11.8인지.
  - `target/lib`에 scalatest·scalactic·akka-testkit이 없는지.
  - 기존 테스트 14개가 통과하는지.

커밋 2. 테스트 설정
- `org.mockito:mockito-core:4.11.0`을 test 스코프로 추가한다. JDK 8을 지원하는 마지막 계열이다.
- `scalatest-maven-plugin` 설정:
  - 기본 실행에서는 Golden 태그를 제외한다.
  - `golden` 프로필에서는 이 제외를 해제한다.
  - `golden.update` 시스템 속성을 테스트 JVM으로 넘긴다.
- `.gitignore`에 `actual.txt`를 추가한다.

### 7.2 외부 파일

테스트는 저장소 밖 파일 두 개를 읽는다.
- `ResourceMonitorServer/tests/data/akka_email_contract.json`: 기존 단위 테스트(`EmailHttpDataFormatSpec`)가 읽고, 골든 계층도 `SendEmail` 기본 페이로드로 읽는다.
- `docker/cassandra/ars-schema.cql`: 골든 계층이 테스트 키스페이스를 만들 때 읽는다.

둘 다 기존 방식대로 `.`, `..`, `../..` 아래에서 찾는다. 없으면 찾아본 경로를 알려 주고 실패한다.

### 7.3 브랜치와 커밋
- 브랜치는 `test/golden-master`이고, `3e5c765`에서 시작한다.
- 다른 세션이 `ARS/HttpWebServer` 작업 트리를 함께 쓴다. 그래서 별도 worktree `ARS/HttpWebServer-golden`에서 작업한다. 형제 폴더라서 7.2의 상대 경로가 그대로 맞는다.
- 커밋 순서:
  1. 패키징 수정
  2. 테스트 설정
  3. 하네스(골든 비교기, fixture 로더와 격리 장치, 가짜 Master, JDK 검사)
  4. 단위 계층
  5. 라우팅 계층
  6. 골든 계층: 메일 4종 → 팝업·이미지 → 템플릿 가져오기 → Cassandra 경로
- 커밋마다 테스트를 통과시킨다. 커밋 전에는 `git status`로 이 작업의 파일만 올라가는지 확인한다.
- push와 PR은 사용자가 지시할 때만 한다. `3e5c765`가 아직 push 전이므로, PR을 만들 때 이 커밋을 어떻게 처리할지 정한다.

## 8. 완료 기준

1. JDK 8에서 `mvn test`가 컨테이너 없이 통과한다. 단위·라우팅 계층과 기존 14개를 합쳐 84개다.
2. JDK 8에서 `mvn test -Pgolden`이 통과한다. 하네스 자체 테스트 9개와 골든 82개를 더해 175개다. `mongodb-44`와 `my-cassandra-server`가 떠 있어야 한다.
3. 라우트 16개와 메시지 14개가 6장의 케이스 목록대로 모두 덮였다.
4. 결함을 기록한 골든 파일에는 모두 `# KNOWN-ISSUE` 머리말이 있다. 그 목록은 3장의 결함 번호와 맞아야 한다.
5. `src/main`이 바뀌지 않았다. 기준 커밋 대비 `git diff 3e5c765 -- src/main`이 비어 있다.
6. `target/lib`에 테스트 jar가 없고, `scala-reflect`가 2.11.8이다.
7. 격리가 지켜졌다. 다른 작업이 돌지 않을 때 골든 실행 전후의 `EARS` 컬렉션별 건수와 `ars` 테이블별 건수를 비교해 같은지 한 번 점검한다. 다른 세션이 같은 DB를 쓰기 때문에 이 점검을 항상 도는 단언으로 두지는 않는다.
8. 같은 코드로 두 번 연속 실행해도 결과가 같다. 갱신 모드로 실행해도 골든 파일이 바뀌지 않는다.
9. 실행 방법, 갱신 방법, 필요한 컨테이너를 적은 짧은 문서가 있다.

## 9. 위험과 대응

| 위험 | 대응 |
|---|---|
| ScalaTest 3.0.8이 scala-reflect 2.11.8 고정에서 깨질 수 있다 | 커밋 1에서 기존 14개로 바로 확인한다. 깨지면 고정 방식을 사용자와 다시 정한다 |
| Mongo 서버는 4.4인데 드라이버 3.11.2의 공식 지원은 4.2까지다 | 쓰는 연산이 기본 CRUD뿐이라 문제 가능성은 낮다. 골든 계층 첫 커밋에서 확인한다 |
| 공유 DB를 다른 세션이 동시에 쓴다 | 테스트 전용 DB와 키스페이스만 쓴다. 지우기 전에 이름을 다시 확인한다 |
| 컨테이너를 다시 만들면 Cassandra 인증이 꺼진다(README) | 접속에 실패하면 README의 해당 절을 안내하는 메시지를 낸다 |
| 액터가 비동기라 테스트 결과가 들쭉날쭉할 수 있다 | 5.6의 수집 규칙을 따른다. 두 번 연속 실행이 같은지를 완료 기준 8로 확인한다 |
| Mockito spy가 `Cluster` 내부 상태를 복사한다 | `connect`만 가로채고 다른 메서드는 호출하지 않는다. 문제가 생기면 `Cluster` 하위 클래스로 바꾼다 |
| Testcontainers는 Docker 29와의 호환이 확인되지 않았다 | 쓰지 않는다. 이미 떠 있는 컨테이너를 쓴다(D3) |

## 10. 다음 단계 (이 설계의 범위 밖)

- 이 문서를 검토한 뒤 구현 계획 문서를 쓰고, 계획에 따라 단계별로 구현한다.
- KNOWN-ISSUE 결함은 경계 TDD로 고친다. 백로그 등록은 ARS 루트 조율 세션이 `ARS-` 접두사와 `--where HttpWebServer`로 한다.
- 리팩터링(순수 함수 추출)과 기동·배선 테스트는 그 뒤에 한다.
