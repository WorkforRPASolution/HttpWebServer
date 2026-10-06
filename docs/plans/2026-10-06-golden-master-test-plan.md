# HttpWebServer 골든 마스터 테스트 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 운영 코드(`src/main`)를 바꾸지 않고, HttpWebServer의 현재 동작을 단위·라우팅·골든 3계층 테스트로 고정한다.

**Architecture:**
- 단위·라우팅 계층은 외부 의존 없이 기본 `mvn test`에서 돈다. 라우팅 계층은 운영과 같은 Jetty + ScalatraBootstrap 구성에 가짜 액터를 끼운다.
- 골든 계층은 진짜 `EmailWorker`·`HttpWorker`를 가짜 `Master` 아래 운영과 같은 경로에 띄운다.
  - OrbStack의 Mongo(`HWS_GOLDEN` DB)와 Cassandra(`hws_golden` 키스페이스)에 사전 상태를 넣는다.
  - 응답, Redis 메일 메시지, DB 변경, 폴더 이동을 텍스트로 그려 `expected.txt`와 비교한다.
  - `-Pgolden`에서만 돈다.

**Tech Stack:** JDK 8 (zulu 1.8.0_472), Maven 3.9, Scala 2.11.8, ScalaTest 3.0.8, akka-testkit 2.4.16, Mockito 4.11.0, json4s 3.2.11, Jetty 9.0.4, Scalatra 2.3.0, Mongo 드라이버 3.11.2 (서버 4.4.30), Cassandra 드라이버 3.7.1 (서버 3.11.6)

**Spec:** `docs/plans/2026-10-06-golden-master-test-design.md`

**검증 상태:** 이 계획의 코드, 스크립트, 기대 수치는 2026-10-06에 scratchpad 사본에서 실제로 컴파일하고 돌려 본 것을 그대로 옮겼다. 결과는 기본 84개, `-Pgolden` 175개 통과였다. 두 번 연속 실행해도 같았고, 갱신 모드로 돌려도 골든 파일 83개가 바뀌지 않았다. 계획서는 검증한 파일에서 기계적으로 조립했다.

## Global Constraints

- `src/main`은 한 줄도 바꾸지 않는다. 끝났을 때 `git diff 3e5c765 -- src/main`이 비어 있어야 한다.
- JDK 8로만 빌드하고 테스트한다: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home`.
- 작업은 worktree `/Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden`(브랜치 `test/golden-master`, 설계 문서 커밋 `f5de5f4` 위)에서만 한다. 다른 세션이 쓰는 메인 작업 트리 `ARS/HttpWebServer`는 건드리지 않는다.
- 테스트 DB는 `HWS_GOLDEN`, 테스트 키스페이스는 `hws_golden`이다. `EARS`, `ars` 같은 기존 이름은 쓰지 않는다.
- Golden 태그의 이름은 `com.sec.eeg.ars.testkit.Golden`이다.
- 골든 파일은 `src/test/resources/golden/` 아래에 둔다. `actual.*`은 git에서 제외한다.
- 데이터는 만든 값만 쓴다(`EQP001`, `MODEL-A`, `EMAIL-TEST-…`). 운영 데이터는 넣지 않는다.
- Scala 2.11 문법 함정
  - `s"..."` 안에 `\"`를 쓰지 않는다. 삼중 따옴표 `s"""..."""`를 쓴다.
  - `try (a, b)` 대신 `try { (a, b) }`로 쓴다.
  - 자바 함수형 인터페이스는 익명 클래스로 구현한다(SAM 변환 없음).
- Mockito spy는 `Mockito.mock(classOf[X], Mockito.withSettings().spiedInstance(obj).defaultAnswer(Mockito.CALLS_REAL_METHODS))`로 만든다. `Mockito.spy`는 오버로드가 모호해 컴파일되지 않는다.
- akka-testkit 2.4.16에서는 `expectNoMsg`를 쓴다. `expectNoMessage`는 없다.
- 커밋
  - 커밋 전에 `git status --short`로 이 Task의 파일만 올라가는지 확인한다.
  - 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`를 붙인다.
  - push와 PR은 하지 않는다.
- 테스트 클래스를 지우거나 이름을 바꿨다면 `mvn clean`부터 한다. `target/test-classes`에 남은 `.class`도 실행되기 때문이다.

## Review Focus

1. **OrbStack 컨테이너가 꺼져 있을 때**: 골든 테스트가 30초씩 멈추면 안 된다. 어떤 컨테이너를 확인할지 알려 주며 바로 실패해야 한다. → Task 4 `MongoGoldenSpec`, Task 5 `CassandraGoldenSpec`의 접속 실패 테스트.
2. **골든 파일이 없거나 손으로 고친 경우**: `actual.*`을 남기고 처음으로 다른 줄을 보여 줘야 한다. → Task 3 `GoldenFileSpec`.
3. **한글(UTF-8) 왕복**: `case.json`, fixture, 골든 파일의 한글이 깨지면 안 된다. → Task 3 `GoldenFileSpec`, Task 6 `GoldenCaseSpec`, Task 9 결과 검토.
4. **`case.json` 오타**(모르는 message, 빠진 base·params, 없는 fixture): 어느 케이스의 무엇이 틀렸는지 알려 줘야 한다. → Task 6 `GoldenCaseSpec`.
5. **한 케이스에 스냅샷이 여러 개**: `<T1>`, `<T2>`가 시각 순서대로 매겨져야 한다. → Task 6 `GoldenRenderSpec`.

---

## 작업 환경 (모든 Task 공통)

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home
mvn -B -o test                                    # 단위·라우팅 (컨테이너 불필요)
mvn -B -o test -Pgolden                           # + 골든 (OrbStack mongodb-44, my-cassandra-server 필요)
mvn -B -o test -Pgolden -Dgolden.update=true      # 골든 파일 기록·갱신
mvn -B -o test -Pgolden -Dsuites=<클래스 전체 이름> # 스위트 하나만
```

- `-o`(오프라인)는 Task 2에서 Mockito를 한 번 내려받은 뒤부터 쓴다. Task 1~2의 첫 실행에는 `-o`를 붙이지 않는다.
- 각 Task 끝의 기대 테스트 수는 누적값이다. 시작할 때는 기존 테스트 14개다.
- 골든 테스트를 실행하기 전에 `docker ps`로 `mongodb-44`와 `my-cassandra-server`가 떠 있는지 확인한다.

## 파일 구조

| 파일 | 역할 | Task |
|---|---|---|
| `pom.xml` | 패키징 수정, 테스트 의존성, Golden 태그 제외, `golden` 프로필 | 1, 2 |
| `.gitignore` | `actual.*` 제외 | 2 |
| `testkit/Golden.scala` | Golden 태그 | 2 |
| `testkit/JdkGuard.scala` | JDK 8 검사 | 2 |
| `testkit/Canonical.scala` | 결정적 표기 (키 정렬, 바이너리 → 길이+해시) | 3 |
| `testkit/Diff.scala` | 이름별 문서 목록의 전후 차이 | 3 |
| `testkit/GoldenFile.scala` | 골든 파일 비교·갱신·`actual.*` | 3 |
| `testkit/FixtureJson.scala` | JSON 읽기, `{"$file": …}` 치환 | 4 |
| `testkit/MongoGolden.scala` | 테스트 DB `HWS_GOLDEN` 비우기·넣기·스냅샷 | 4 |
| `testkit/ExternalFile.scala` | 저장소 밖 파일(RMS 계약, Cassandra 스키마) 찾기 | 5 |
| `testkit/CassandraGolden.scala` | 테스트 키스페이스 `hws_golden` 생성·비우기·넣기·스냅샷, `connect("ars")` 돌리기 | 5 |
| `testkit/ActorKit.scala` | `Forwarder`, `Responder`, `StubMaster`, `awaitActor` | 6 |
| `testkit/TempCase.scala` | 하네스 자체 테스트용 임시 케이스 | 6 |
| `testkit/GoldenCase.scala` | `case.json` 읽기 (base/set/unset/fixtures/…) | 6 |
| `testkit/GoldenRunner.scala` | 케이스 실행: `GoldenMessages`, `GoldenRender`, `ServiceConfigState`, `GoldenRunner` | 6 |
| `testkit/RoutingHarness.scala` | Jetty + ScalatraBootstrap + 가짜 액터 | 8 |
| `testkit/GoldenSuite.scala` | 메시지 폴더마다 Golden 테스트 등록 | 9 |
| `utils/ImageUtilsSpec.scala`, `twirl/HistoryTemplateSpec.scala`, `data/JsonInterfacesSpec.scala` | 단위 계층 | 7 |
| `endpoint/HttpEndPointRoutingSpec.scala`, `endpoint/BootstrapLifecycleSpec.scala` | 라우팅 계층 | 8 |
| `golden/MailGoldenSpec.scala`, `LookupGoldenSpec.scala`, `TemplateImportGoldenSpec.scala`, `CassandraPathGoldenSpec.scala` | 골든 계층 | 9–12 |
| `src/test/resources/golden/_files/` | `favicon.png`(286바이트, 48×48), `favicon.tif` | 4, 7 |
| `src/test/resources/golden/_fixtures/` | 공용 사전 상태, 기본 페이로드 | 9–12 |
| `src/test/resources/golden/<메시지>/<케이스>/` | `case.json`, `expected.txt`, `files/` | 9–12 |
| `docs/testing.md` | 실행·갱신 방법, KNOWN-ISSUE 목록 | 13 |

(`testkit/…`, `utils/…` 등은 `src/test/scala/com/sec/eeg/ars/` 아래 경로다.)

---

## Task 1: 패키징 수정 — 테스트 jar 가 배포 lib 에 섞이지 않게

배포물이 바뀌는 커밋이라 다른 변경과 섞지 않는다 (설계 D2).

**Files:**
- Modify: `pom.xml` (`<dependencies>` 바로 앞, `maven-dependency-plugin` 의 `copy-dependencies` 설정)

**Interfaces:** 없음 (빌드 설정)

- [ ] **Step 1: 지금 상태가 문제임을 확인한다 (실패하는 검사)**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home
mvn -B dependency:list | grep scala-reflect
mvn -B -q clean package -DskipTests && ls target/lib | grep -cE 'scalatest|scalactic|testkit'
```
기대: `org.scala-lang:scala-reflect:jar:2.11.12:compile` 그리고 `3` (테스트 jar 3개가 배포 lib 에 있다)

- [ ] **Step 2: `pom.xml` 을 고친다**

첫 번째 `  <dependencies>` 줄 바로 앞에 넣는다:

```xml
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.scala-lang</groupId>
        <artifactId>scala-reflect</artifactId>
        <version>${scala.version}</version>
      </dependency>
    </dependencies>
  </dependencyManagement>

```

`copy-dependencies` 실행의 `<outputDirectory>${project.build.directory}/lib</outputDirectory>` 바로 아래 줄에 넣는다:

```xml
              <includeScope>runtime</includeScope>
```

- [ ] **Step 3: 고쳐졌는지 확인한다**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && mvn -B dependency:list | grep scala-reflect
mvn -B clean package 2>&1 | grep -E 'Tests: |BUILD'
ls target/lib | wc -l; ls target/lib | grep -cE 'scalatest|scalactic|testkit'
```
기대: `scala-reflect:jar:2.11.8:compile`, `Tests: succeeded 14, failed 0`, `BUILD SUCCESS`, lib `142`개, 테스트 jar `0`

- [ ] **Step 4: 커밋**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && git status --short   # 이 Task 의 파일만 보여야 한다
git add pom.xml
git commit -q -F - <<'EOF'
pom: 테스트 jar 를 배포 lib 에서 빼고 scala-reflect 를 2.11.8 로 고정

copy-dependencies 에 includeScope=runtime 을 넣어 scalatest·scalactic·akka-testkit 이
target/lib 에 섞이지 않게 하고, 테스트 하네스가 2.11.12 로 끌어올린 scala-reflect 를
dependencyManagement 로 하네스 도입 전 값(2.11.8)에 고정한다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git log --oneline -1
```


## Task 2: 테스트 설정 — Golden 태그, golden 프로필, golden.update 전달, JDK 검사

**Files:**
- Modify: `pom.xml`, `.gitignore`
- Create: `src/test/scala/com/sec/eeg/ars/testkit/Golden.scala`, `src/test/scala/com/sec/eeg/ars/testkit/JdkGuard.scala`
- Test: `src/test/scala/com/sec/eeg/ars/testkit/JdkGuardSpec.scala`, `src/test/scala/com/sec/eeg/ars/testkit/GoldenTagSpec.scala`

**Interfaces:**
- Produces: `object Golden extends org.scalatest.Tag("com.sec.eeg.ars.testkit.Golden")`, `JdkGuard.require8(): Unit`, 시스템 속성 `golden.update`(기본 `"false"`), Maven 프로필 `golden`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/scala/com/sec/eeg/ars/testkit/JdkGuardSpec.scala`:

```scala
package com.sec.eeg.ars.testkit

import org.scalatest.FunSuite

class JdkGuardSpec extends FunSuite {
  test("테스트 JVM 은 JDK 8 이다") {
    JdkGuard.require8()
    assert(System.getProperty("java.version").startsWith("1.8"))
  }
}
```

`src/test/scala/com/sec/eeg/ars/testkit/GoldenTagSpec.scala`:

```scala
package com.sec.eeg.ars.testkit

import org.scalatest.FunSuite

class GoldenTagSpec extends FunSuite {
  test("pom 의 golden.update 값이 테스트 JVM 까지 전달된다") {
    assert(sys.props.get("golden.update").isDefined)
  }

  test("이 테스트는 -Pgolden 일 때만 실행된다", Golden) {
    assert(true)
  }
}
```

- [ ] **Step 2: 실패를 확인한다**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && mvn -B test 2>&1 | grep -E 'error:|BUILD'
```
기대: `not found: value JdkGuard`, `not found: value Golden` 컴파일 오류, `BUILD FAILURE`

- [ ] **Step 3: 구현한다**

`src/test/scala/com/sec/eeg/ars/testkit/Golden.scala`:

```scala
package com.sec.eeg.ars.testkit

import org.scalatest.Tag

/** OrbStack의 Mongo·Cassandra가 필요한 테스트. 기본 `mvn test`에서는 빠지고 `-Pgolden`에서만 돈다. */
object Golden extends Tag("com.sec.eeg.ars.testkit.Golden")
```

`src/test/scala/com/sec/eeg/ars/testkit/JdkGuard.scala`:

```scala
package com.sec.eeg.ars.testkit

/**
 * HttpWebServer 테스트는 JDK 8에서만 의미가 있다. JDK 8의 ImageIO에는 TIFF 리더가 없어서
 * 운영과 같은 결과(TIFF 변환 실패)가 나오는 것은 JDK 8뿐이다.
 * JDK 9 이상에서는 scalac 2.11.8이 컴파일 단계에서 먼저 실패하므로, 이 검사는 IDE 등에서
 * 다른 JDK로 테스트만 돌리는 경우를 막는다.
 */
object JdkGuard {
  def require8(): Unit = {
    val v = System.getProperty("java.version")
    if (!v.startsWith("1.8"))
      throw new IllegalStateException(
        s"HttpWebServer 테스트는 JDK 8에서만 실행한다 (현재 java.version=$v). " +
          "export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home 후 다시 실행한다.")
  }
}
```

`pom.xml` — `<scala.compat.version>2.11</scala.compat.version>` 바로 아래에:

```xml
    <!-- Golden 태그 테스트는 기본 실행에서 뺀다. -Pgolden 이 이 값을 none 으로 바꾼다 -->
    <hws.tagsToExclude>com.sec.eeg.ars.testkit.Golden</hws.tagsToExclude>
    <!-- 골든 파일 갱신 모드: -Dgolden.update=true -->
    <golden.update>false</golden.update>
```

`pom.xml` — 의존성 목록의 마지막(`akka-testkit_2.11` 의존성 다음, `</dependencies>` 앞)에:

```xml
    <!-- 골든 하네스: 운영 코드의 connect("ars") 를 테스트 키스페이스로 돌리는 Cluster spy -->
    <dependency>
      <groupId>org.mockito</groupId>
      <artifactId>mockito-core</artifactId>
      <version>4.11.0</version>
      <scope>test</scope>
    </dependency>
```

`pom.xml` — `scalatest-maven-plugin` 의 `<filereports>WDF TestSuite.txt</filereports>` 바로 아래에:

```xml
          <tagsToExclude>${hws.tagsToExclude}</tagsToExclude>
          <systemProperties>
            <golden.update>${golden.update}</golden.update>
          </systemProperties>
```

`pom.xml` — 마지막 `</project>` 바로 앞에:

```xml
  <profiles>
    <!-- OrbStack 의 mongodb-44·my-cassandra-server 가 필요한 골든 테스트까지 돌린다 -->
    <profile>
      <id>golden</id>
      <properties>
        <hws.tagsToExclude>none</hws.tagsToExclude>
      </properties>
    </profile>
  </profiles>
```

`.gitignore` 끝에:

```
### 골든 마스터: 불일치 시 남는 실제 출력 ###
src/test/resources/golden/**/actual.*
```

- [ ] **Step 4: 통과를 확인한다 (이 단계에서 Mockito 를 내려받으므로 -o 없이)**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && mvn -B test 2>&1 | grep -E 'Tests: |BUILD' && mvn -B test -Pgolden 2>&1 | grep -E 'Tests: |BUILD'
```
기대: 기본 `Tests: succeeded 16, failed 0`, `-Pgolden` `Tests: succeeded 17, failed 0` (Golden 태그 1개 차이)

- [ ] **Step 5: 커밋**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && git status --short   # 이 Task 의 파일만 보여야 한다
git add pom.xml \
        .gitignore \
        src/test/scala/com/sec/eeg/ars/testkit/Golden.scala \
        src/test/scala/com/sec/eeg/ars/testkit/JdkGuard.scala \
        src/test/scala/com/sec/eeg/ars/testkit/JdkGuardSpec.scala \
        src/test/scala/com/sec/eeg/ars/testkit/GoldenTagSpec.scala
git commit -q -F - <<'EOF'
테스트 설정: Golden 태그·golden 프로필·golden.update 전달·JDK 8 검사

기본 mvn test 는 Golden 태그를 빼고 컨테이너 없이 돈다. -Pgolden 이 골든 테스트를 켠다.
mockito-core 4.11.0 (test) 은 Cassandra Cluster spy 용이다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git log --oneline -1
```


## Task 3: 골든 비교기와 결정적 표기 — Canonical, Diff, GoldenFile

**Files:**
- Create: `src/test/scala/com/sec/eeg/ars/testkit/Canonical.scala`, `src/test/scala/com/sec/eeg/ars/testkit/Diff.scala`, `src/test/scala/com/sec/eeg/ars/testkit/GoldenFile.scala`
- Test: `src/test/scala/com/sec/eeg/ars/testkit/CanonicalSpec.scala`, `src/test/scala/com/sec/eeg/ars/testkit/DiffSpec.scala`, `src/test/scala/com/sec/eeg/ars/testkit/GoldenFileSpec.scala`

**Interfaces:**
- Produces:
  - `Canonical.value(v: Any): String` — 키 정렬 JSON 비슷한 표기, `Canonical.bytes(b: Array[Byte]): String` = `<N bytes sha256:앞12>`, `Canonical.quote(s: String): String`, `Canonical.sha256(b): String`
  - `Diff.render(before: Map[String, Seq[String]], after: Map[String, Seq[String]]): Seq[String]` — `이름`, `  - 문서`, `  + 문서` 줄
  - `GoldenFile.Root: File` (= `user.dir/src/test/resources/golden`), `GoldenFile.check(expected: File, actualText: String, update: Boolean = updateMode): Option[String]` (None=일치·갱신, Some=실패 메시지), `GoldenFile.normalize`, `GoldenFile.diff`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/scala/com/sec/eeg/ars/testkit/CanonicalSpec.scala`:

```scala
package com.sec.eeg.ars.testkit

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8

import org.bson.Document
import org.bson.types.Binary
import org.scalatest.FunSuite

class CanonicalSpec extends FunSuite {
  test("객체 키를 정렬하고 중첩 구조를 유지한다") {
    val d = Document.parse("""{"b": 1, "a": {"d": 2, "c": [3, "x"]}}""")
    assert(Canonical.value(d) == """{"a":{"c":[3,"x"],"d":2},"b":1}""")
  }

  test("바이너리는 길이와 sha256 앞 12자리로 줄인다") {
    val hello = "hello".getBytes(UTF_8)
    assert(Canonical.bytes(hello) == "<5 bytes sha256:2cf24dba5fb0>")
    assert(Canonical.value(hello) == "\"<5 bytes sha256:2cf24dba5fb0>\"")
    assert(Canonical.value(new Binary(hello)) == Canonical.value(hello))
    assert(Canonical.value(ByteBuffer.wrap(hello)) == Canonical.value(hello))
    assert(Canonical.bytes(Array.empty[Byte]) == "<0 bytes sha256:e3b0c44298fc>")
  }

  test("문자열은 JSON 규칙대로 이스케이프하고 한글은 그대로 둔다") {
    assert(Canonical.quote("한글 \"q\" \\ \n\t") == "\"한글 \\\"q\\\" \\\\ \\n\\t\"")
  }

  test("null, 숫자, 불리언, 날짜") {
    assert(Canonical.value(null) == "null")
    assert(Canonical.value(java.lang.Long.valueOf(1700000000000L)) == "1700000000000")
    assert(Canonical.value(java.lang.Boolean.TRUE) == "true")
    assert(Canonical.value(new java.util.Date(0L)) == "\"1970-01-01T00:00:00.000Z\"")
  }
}
```

`src/test/scala/com/sec/eeg/ars/testkit/DiffSpec.scala`:

```scala
package com.sec.eeg.ars.testkit

import org.scalatest.FunSuite

class DiffSpec extends FunSuite {
  test("이름별로 삭제·추가를 그리고, 같은 문서는 개수대로 비교한다") {
    val before = Map("A" -> Seq("x", "x", "y"), "B" -> Seq("k"))
    val after = Map("A" -> Seq("x", "z"), "B" -> Seq("k"), "C" -> Seq("n"))
    assert(Diff.render(before, after) == Seq("A", "  - x", "  - y", "  + z", "C", "  + n"))
  }

  test("변화가 없으면 빈 목록") {
    assert(Diff.render(Map("A" -> Seq("x")), Map("A" -> Seq("x"))).isEmpty)
  }
}
```

`src/test/scala/com/sec/eeg/ars/testkit/GoldenFileSpec.scala`:

```scala
package com.sec.eeg.ars.testkit

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

import org.scalatest.FunSuite

class GoldenFileSpec extends FunSuite {
  private def tempDir(): File = Files.createTempDirectory("golden-file-spec").toFile

  private def write(f: File, s: String): Unit = Files.write(f.toPath, s.getBytes(UTF_8))

  private def read(f: File): String = new String(Files.readAllBytes(f.toPath), UTF_8)

  test("같으면 None 이고, 남아 있던 actual 파일을 지운다") {
    val d = tempDir()
    val exp = new File(d, "expected.txt")
    val act = new File(d, "actual.txt")
    write(exp, "a\n한글\n")
    write(act, "old")
    assert(GoldenFile.check(exp, "a\n한글\n", update = false).isEmpty)
    assert(!act.exists())
  }

  test("다르면 actual 파일을 쓰고 첫 차이를 알려 준다") {
    val d = tempDir()
    val exp = new File(d, "expected.txt")
    write(exp, "a\nb\nc\n")
    val msg = GoldenFile.check(exp, "a\nX\nc\n", update = false)
    assert(msg.isDefined)
    assert(msg.get.contains("첫 차이: 2번째 줄"))
    assert(msg.get.contains("-    2| b"))
    assert(msg.get.contains("+    2| X"))
    assert(read(new File(d, "actual.txt")) == "a\nX\nc\n")
  }

  test("expected 가 없으면 실패하고 actual 을 남긴다") {
    val d = tempDir()
    val msg = GoldenFile.check(new File(d, "expected.txt"), "new", update = false)
    assert(msg.exists(_.contains("골든 파일이 없다")))
    assert(read(new File(d, "actual.txt")) == "new\n")
  }

  test("갱신 모드는 expected 를 쓰고 actual 을 지운다") {
    val d = tempDir()
    val exp = new File(d, "expected.txt")
    val act = new File(d, "actual.txt")
    write(act, "stale")
    assert(GoldenFile.check(exp, "fresh", update = true).isEmpty)
    assert(read(exp) == "fresh\n")
    assert(!act.exists())
  }

  test("CRLF 와 끝 줄바꿈 차이는 같은 것으로 본다") {
    val d = tempDir()
    val exp = new File(d, "expected.txt")
    write(exp, "a\r\nb")
    assert(GoldenFile.check(exp, "a\nb\n", update = false).isEmpty)
  }

  test("expected.html 의 실제 출력은 actual.html 에 쓴다") {
    val d = tempDir()
    GoldenFile.check(new File(d, "expected.html"), "<p>x</p>", update = false)
    assert(new File(d, "actual.html").exists())
  }
}
```

- [ ] **Step 2: 실패를 확인한다**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && mvn -B -o test 2>&1 | grep -E 'error:|BUILD'
```
기대: `not found: value Canonical` 등 컴파일 오류, `BUILD FAILURE`

- [ ] **Step 3: 구현한다**

`src/test/scala/com/sec/eeg/ars/testkit/Canonical.scala`:

```scala
package com.sec.eeg.ars.testkit

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.TimeZone

import scala.collection.JavaConverters._

/** 골든 출력용 결정적 표기. 객체 키는 정렬하고, 바이너리는 길이+해시로 줄인다. */
object Canonical {
  def sha256(b: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(b).map("%02x".format(_)).mkString

  def bytes(b: Array[Byte]): String = s"<${b.length} bytes sha256:${sha256(b).take(12)}>"

  def value(v: Any): String = v match {
    case null => "null"
    case s: String => quote(s)
    case b: java.lang.Boolean => b.toString
    case n: java.lang.Number => n.toString
    case b: Array[Byte] => quote(bytes(b))
    case b: org.bson.types.Binary => quote(bytes(b.getData))
    case bb: ByteBuffer =>
      val c = bb.duplicate()
      val a = new Array[Byte](c.remaining())
      c.get(a)
      quote(bytes(a))
    case d: java.util.Date => quote(iso(d))
    case _: org.bson.types.ObjectId => quote("<ObjectId>")
    case m: java.util.Map[_, _] => obj(m.asScala.toSeq.map { case (k, x) => (String.valueOf(k), x) })
    case m: scala.collection.Map[_, _] => obj(m.toSeq.map { case (k, x) => (String.valueOf(k), x) })
    case l: java.util.Collection[_] => l.asScala.map(value).mkString("[", ",", "]")
    case s: Seq[_] => s.map(value).mkString("[", ",", "]")
    case other => quote(other.toString)
  }

  private def obj(fields: Seq[(String, Any)]): String =
    fields.sortBy(_._1).map { case (k, x) => quote(k) + ":" + value(x) }.mkString("{", ",", "}")

  private def iso(d: java.util.Date): String = {
    val f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    f.setTimeZone(TimeZone.getTimeZone("UTC"))
    f.format(d)
  }

  def quote(s: String): String = {
    val sb = new StringBuilder("\"")
    s.foreach {
      case '"' => sb.append("\\\"")
      case '\\' => sb.append("\\\\")
      case '\n' => sb.append("\\n")
      case '\r' => sb.append("\\r")
      case '\t' => sb.append("\\t")
      case c if c < ' ' => sb.append("\\u%04x".format(c.toInt))
      case c => sb.append(c)
    }
    sb.append('"').toString
  }
}
```

`src/test/scala/com/sec/eeg/ars/testkit/Diff.scala`:

```scala
package com.sec.eeg.ars.testkit

/** 이름(컬렉션·테이블)별 문서 목록 두 벌의 차이를 "- 문서" / "+ 문서" 줄로 그린다. 같은 문서가 여러 개여도 개수대로 비교한다. */
object Diff {
  def render(before: Map[String, Seq[String]], after: Map[String, Seq[String]]): Seq[String] =
    (before.keySet ++ after.keySet).toSeq.sorted.flatMap { name =>
      val b = before.getOrElse(name, Nil)
      val a = after.getOrElse(name, Nil)
      val removed = (b diff a).sorted
      val added = (a diff b).sorted
      if (removed.isEmpty && added.isEmpty) Nil
      else name +: (removed.map("  - " + _) ++ added.map("  + " + _))
    }
}
```

`src/test/scala/com/sec/eeg/ars/testkit/GoldenFile.scala`:

```scala
package com.sec.eeg.ars.testkit

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/**
 * 골든 파일 비교기. 평소에는 비교만 하고, 다르면 같은 폴더에 actual.* 를 남기고 실패 메시지를 돌려준다.
 * -Dgolden.update=true 일 때만 expected.* 를 덮어쓴다.
 */
object GoldenFile {
  /** 소스 트리의 골든 폴더. 갱신 모드가 여기에 써야 하므로 target/test-classes 가 아니다. */
  val Root: File = new File(sys.props("user.dir"), "src/test/resources/golden")

  def updateMode: Boolean = sys.props.get("golden.update").contains("true")

  /** None = 일치(또는 갱신함), Some(메시지) = 불일치 */
  def check(expected: File, actualText: String, update: Boolean = updateMode): Option[String] = {
    val actual = normalize(actualText)
    val actualFile = new File(expected.getParentFile, expected.getName.replaceFirst("^expected", "actual"))
    expected.getParentFile.mkdirs()
    if (update) {
      Files.write(expected.toPath, actual.getBytes(UTF_8))
      Files.deleteIfExists(actualFile.toPath)
      None
    } else {
      val exp = if (expected.exists()) Some(normalize(new String(Files.readAllBytes(expected.toPath), UTF_8))) else None
      if (exp.contains(actual)) {
        Files.deleteIfExists(actualFile.toPath)
        None
      } else {
        Files.write(actualFile.toPath, actual.getBytes(UTF_8))
        Some(message(expected, actualFile, exp, actual))
      }
    }
  }

  /** 줄바꿈을 \n 으로 맞추고 끝에 줄바꿈 하나를 보장한다 */
  def normalize(s: String): String = {
    val unix = s.replace("\r\n", "\n")
    if (unix.endsWith("\n")) unix else unix + "\n"
  }

  private def message(expected: File, actualFile: File, exp: Option[String], actual: String): String = {
    val head = exp match {
      case None => s"골든 파일이 없다: ${expected.getPath}"
      case Some(e) => s"골든 파일과 다르다: ${expected.getPath}\n" + diff(e, actual)
    }
    head + s"\n실제 출력: ${actualFile.getPath}\n의도한 변경이면 -Dgolden.update=true 로 갱신한 뒤 git diff 로 검토한다."
  }

  /** 처음으로 다른 줄의 앞뒤를 보여 준다 */
  def diff(expected: String, actual: String): String = {
    val e = expected.split("\n", -1)
    val a = actual.split("\n", -1)
    val first = (0 until math.max(e.length, a.length)).find(i => e.lift(i) != a.lift(i)).getOrElse(0)
    val from = math.max(0, first - 2)
    val to = first + 3
    def show(label: String, lines: Array[String]): String =
      (from until math.min(to, lines.length)).map(i => f"$label ${i + 1}%4d| ${lines(i)}").mkString("\n")
    s"첫 차이: ${first + 1}번째 줄 (기대 ${e.length}줄, 실제 ${a.length}줄)\n${show("-", e)}\n${show("+", a)}"
  }
}
```

- [ ] **Step 4: 통과를 확인한다**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && mvn -B -o test 2>&1 | grep -E 'Tests: |\*\*\* FAILED|ABORTED|error:|BUILD'
```
기대: `Tests: succeeded 28, failed 0`

- [ ] **Step 5: 커밋**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && git status --short   # 이 Task 의 파일만 보여야 한다
git add src/test/scala/com/sec/eeg/ars/testkit/Canonical.scala \
        src/test/scala/com/sec/eeg/ars/testkit/Diff.scala \
        src/test/scala/com/sec/eeg/ars/testkit/GoldenFile.scala \
        src/test/scala/com/sec/eeg/ars/testkit/CanonicalSpec.scala \
        src/test/scala/com/sec/eeg/ars/testkit/DiffSpec.scala \
        src/test/scala/com/sec/eeg/ars/testkit/GoldenFileSpec.scala
git commit -q -F - <<'EOF'
골든 비교기: 결정적 표기·전후 차이·골든 파일 비교/갱신

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git log --oneline -1
```


## Task 4: Mongo 하네스와 `$file` 표기 — FixtureJson, MongoGolden

**Files:**
- Create: `src/test/scala/com/sec/eeg/ars/testkit/FixtureJson.scala`, `src/test/scala/com/sec/eeg/ars/testkit/MongoGolden.scala`, `src/test/resources/golden/_files/favicon.png`
- Test: `src/test/scala/com/sec/eeg/ars/testkit/FixtureJsonSpec.scala`, `src/test/scala/com/sec/eeg/ars/testkit/MongoGoldenSpec.scala`

**Interfaces:**
- Consumes: `GoldenFile.Root`, `Canonical.value`, `Diff.render`
- Produces:
  - `FixtureJson.read(f: File): JValue`, `FixtureJson.fileBytes(path: String): Array[Byte]`, `FixtureJson.resolveFiles(v: JValue, mode: FileMode): JValue` (`AsBase64` | `AsMongoBinary` | `AsCassandraBlob`), `FixtureJson.compactJson(v: JValue): String`
  - `MongoGolden.DbName = "HWS_GOLDEN"`, `MongoGolden.database: MongoDatabase`, `MongoGolden.ping(db, url)`, `reset()`, `seed(collections: Map[String, Seq[String]])`, `snapshot(): Map[String, Seq[String]]`

- [ ] **Step 1: 테스트용 PNG 를 둔다**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && mkdir -p src/test/resources/golden/_files && cp src/main/_webapp/img/favicon.png src/test/resources/golden/_files/favicon.png && shasum -a 256 src/test/resources/golden/_files/favicon.png | cut -c1-12
```
기대: `99d69510d1da` (286 바이트, 48×48)

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`src/test/scala/com/sec/eeg/ars/testkit/FixtureJsonSpec.scala`:

```scala
package com.sec.eeg.ars.testkit

import java.util.Base64

import com.sec.eeg.ars.testkit.FixtureJson._
import org.json4s.jackson.JsonMethods._
import org.scalatest.FunSuite

class FixtureJsonSpec extends FunSuite {
  private val png = "_files/favicon.png"
  private val doc = parse(s"""{"a": {"$$file": "$png"}, "b": [{"$$file": "$png"}], "c": "그대로"}""")

  test("_files/favicon.png 는 286 바이트 PNG 다") {
    assert(fileBytes(png).length == 286)
  }

  test("본문용: base64 문자열") {
    val b64 = Base64.getEncoder.encodeToString(fileBytes(png))
    assert(compactJson(resolveFiles(doc, AsBase64)) == s"""{"a":"$b64","b":["$b64"],"c":"그대로"}""")
  }

  test("Mongo 용: Extended JSON 바이너리") {
    val b64 = Base64.getEncoder.encodeToString(fileBytes(png))
    assert(compactJson(resolveFiles(doc, AsMongoBinary)).startsWith(s"""{"a":{"$$binary":"$b64","$$type":"00"}"""))
  }

  test("Cassandra 용: 0x 로 시작하는 16진수") {
    assert(compactJson(resolveFiles(doc, AsCassandraBlob)).startsWith("""{"a":"0x89504e47"""))
  }

  test("없는 파일은 경로를 알려 준다") {
    val e = intercept[IllegalArgumentException](resolveFiles(parse("""{"x": {"$file": "_files/없음.bin"}}"""), AsBase64))
    assert(e.getMessage.contains("_files/없음.bin"))
  }
}
```

`src/test/scala/com/sec/eeg/ars/testkit/MongoGoldenSpec.scala`:

```scala
package com.sec.eeg.ars.testkit

import com.mongodb.client.MongoClients
import org.bson.Document
import org.scalatest.FunSuite

class MongoGoldenSpec extends FunSuite {
  test("접속할 수 없으면 무엇을 확인할지 알려 주며 빨리 실패한다") {
    val url = "mongodb://localhost:1/?serverSelectionTimeoutMS=500"
    val client = MongoClients.create(url)
    try {
      val t0 = System.currentTimeMillis()
      val e = intercept[IllegalStateException](MongoGolden.ping(client.getDatabase("x"), url))
      assert(e.getMessage.contains("mongodb-44"))
      assert(System.currentTimeMillis() - t0 < 5000)
    } finally client.close()
  }

  test("테스트 DB 이름은 HWS_GOLDEN 이다", Golden) {
    assert(MongoGolden.database.getName == "HWS_GOLDEN")
  }

  test("비우고, 넣고, 전후 차이를 본다", Golden) {
    MongoGolden.reset()
    MongoGolden.seed(Map("C" -> Seq("""{"k": "v", "n": 1}""")))
    val before = MongoGolden.snapshot()
    assert(before == Map("C" -> Seq("""{"k":"v","n":1}""")))
    MongoGolden.database.getCollection("C").insertOne(Document.parse("""{"k": "새것"}"""))
    assert(Diff.render(before, MongoGolden.snapshot()) == Seq("C", """  + {"k":"새것"}"""))
    MongoGolden.reset()
    assert(MongoGolden.snapshot().isEmpty)
  }
}
```

- [ ] **Step 3: 실패를 확인한다**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && mvn -B -o test 2>&1 | grep -E 'error:|BUILD'
```
기대: `not found: value FixtureJson`, `not found: value MongoGolden` 컴파일 오류

- [ ] **Step 4: 구현한다**

`src/test/scala/com/sec/eeg/ars/testkit/FixtureJson.scala`:

```scala
package com.sec.eeg.ars.testkit

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.util.Base64

import org.json4s._
import org.json4s.jackson.JsonMethods._

/** case.json·fixture 파일을 읽고, {"$file": "_files/..."} 표기를 쓰임새에 맞는 값으로 바꾼다 */
object FixtureJson {
  sealed trait FileMode
  /** 요청 본문 필드: base64 문자열 */
  case object AsBase64 extends FileMode
  /** Mongo Extended JSON 바이너리: {"$binary": base64, "$type": "00"} */
  case object AsMongoBinary extends FileMode
  /** Cassandra INSERT JSON 의 blob: "0x<hex>" */
  case object AsCassandraBlob extends FileMode

  def read(f: File): JValue = parse(new String(Files.readAllBytes(f.toPath), UTF_8))

  def fileBytes(path: String): Array[Byte] = {
    val f = new File(GoldenFile.Root, path)
    if (!f.isFile) throw new IllegalArgumentException(s"$$file 경로가 없다: ${f.getPath}")
    Files.readAllBytes(f.toPath)
  }

  def resolveFiles(v: JValue, mode: FileMode): JValue = v match {
    case JObject(List(("$file", JString(path)))) =>
      val b = fileBytes(path)
      mode match {
        case AsBase64 => JString(Base64.getEncoder.encodeToString(b))
        case AsMongoBinary => JObject(List("$binary" -> JString(Base64.getEncoder.encodeToString(b)), "$type" -> JString("00")))
        case AsCassandraBlob => JString("0x" + b.map("%02x".format(_)).mkString)
      }
    case JObject(fields) => JObject(fields.map { case (k, x) => (k, resolveFiles(x, mode)) })
    case JArray(xs) => JArray(xs.map(resolveFiles(_, mode)))
    case other => other
  }

  def compactJson(v: JValue): String = compact(render(v))
}
```

`src/test/scala/com/sec/eeg/ars/testkit/MongoGolden.scala`:

```scala
package com.sec.eeg.ars.testkit

import com.mongodb.client.{MongoClient, MongoClients, MongoDatabase}
import org.bson.Document

import scala.collection.JavaConverters._

/** OrbStack mongodb-44 위의 테스트 전용 DB(HWS_GOLDEN). 다른 DB는 건드리지 않는다. */
object MongoGolden {
  val DbName = "HWS_GOLDEN"
  val DefaultUrl = "mongodb://localhost:27017/?serverSelectionTimeoutMS=3000"
  lazy val url: String = sys.env.getOrElse("HWS_GOLDEN_MONGO_URL", DefaultUrl)

  private lazy val client: MongoClient = MongoClients.create(url)

  lazy val database: MongoDatabase = {
    val db = client.getDatabase(DbName)
    ping(db, url)
    db
  }

  /** 접속을 확인하고, 실패하면 무엇을 확인할지 알려 주는 예외를 던진다 */
  def ping(db: MongoDatabase, url: String): Unit =
    try db.runCommand(new Document("ping", 1))
    catch {
      case e: Exception => throw new IllegalStateException(
        s"Mongo 에 접속하지 못했다 ($url). OrbStack 의 mongodb-44 컨테이너가 떠 있는지 확인한다 (docker ps). 원인: ${e.getMessage}", e)
    }

  private def guarded: MongoDatabase = {
    require(database.getName == DbName, s"테스트 DB 이름이 $DbName 이 아니다: ${database.getName}")
    database
  }

  def reset(): Unit = guarded.drop()

  /** 컬렉션 -> Extended JSON 문서 문자열들 */
  def seed(collections: Map[String, Seq[String]]): Unit =
    collections.foreach { case (name, docs) =>
      if (docs.nonEmpty) guarded.getCollection(name).insertMany(docs.map(d => Document.parse(d)).asJava)
    }

  /** 컬렉션 -> 정렬된 문서 표기 (_id 제외) */
  def snapshot(): Map[String, Seq[String]] = {
    val db = guarded
    db.listCollectionNames().asScala.toSeq.map { name =>
      name -> db.getCollection(name).find().asScala.toSeq.map { d =>
        d.remove("_id")
        Canonical.value(d)
      }.sorted
    }.toMap
  }
}
```

- [ ] **Step 5: 통과를 확인한다**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && mvn -B -o test 2>&1 | grep -E 'Tests: |\*\*\* FAILED|ABORTED|error:|BUILD' && mvn -B -o test -Pgolden -Dsuites=com.sec.eeg.ars.testkit.MongoGoldenSpec 2>&1 | grep -E 'Tests: |BUILD'
```
기대: 기본 `Tests: succeeded 34, failed 0`. MongoGoldenSpec 단독 `-Pgolden` 은 `Tests: succeeded 3, failed 0` (접속 실패 테스트는 5초 안에 끝난다)

- [ ] **Step 6: 커밋**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && git status --short   # 이 Task 의 파일만 보여야 한다
git add src/test/resources/golden/_files/favicon.png \
        src/test/scala/com/sec/eeg/ars/testkit/FixtureJson.scala \
        src/test/scala/com/sec/eeg/ars/testkit/MongoGolden.scala \
        src/test/scala/com/sec/eeg/ars/testkit/FixtureJsonSpec.scala \
        src/test/scala/com/sec/eeg/ars/testkit/MongoGoldenSpec.scala
git commit -q -F - <<'EOF'
골든 하네스: Mongo 테스트 DB(HWS_GOLDEN)와 $file 표기

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git log --oneline -1
```


## Task 5: Cassandra 하네스 — ExternalFile, CassandraGolden

`ARS/docker/cassandra/ars-schema.cql`(운영 DESCRIBE 결과)을 직접 읽어 `hws_golden` 키스페이스를 만든다. 비울 때 `TRUNCATE` 대신 파티션 단위 `DELETE` 를 쓴다 (공유 컨테이너에 스냅샷이 쌓이지 않게).

**Files:**
- Create: `src/test/scala/com/sec/eeg/ars/testkit/ExternalFile.scala`, `src/test/scala/com/sec/eeg/ars/testkit/CassandraGolden.scala`
- Test: `src/test/scala/com/sec/eeg/ars/testkit/CassandraGoldenSpec.scala`

**Interfaces:**
- Consumes: `Canonical.value`, `Diff.render`, Mockito
- Produces:
  - `ExternalFile.find(rel: String): File`, `ExternalFile.rmsContract: File`, `ExternalFile.cassandraSchema: File`
  - `CassandraGolden.Keyspace = "hws_golden"`, `openCluster(host, port, user, password): Cluster`, `connectOrExplain(c: Cluster, where: String): Session`, `ensureReady()`, `schemaStatements(): Seq[String]`, `tables: Seq[String]`, `clear()`, `seed(rows: Map[String, Seq[String]])` (INSERT JSON 문자열), `snapshot(): Map[String, Seq[String]]`, `emailSnapshotTimes(): Set[Long]`, `redirectingCluster(): Cluster`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/scala/com/sec/eeg/ars/testkit/CassandraGoldenSpec.scala`:

```scala
package com.sec.eeg.ars.testkit

import org.scalatest.FunSuite

class CassandraGoldenSpec extends FunSuite {
  test("접속할 수 없으면 무엇을 확인할지 알려 준다") {
    val c = CassandraGolden.openCluster("127.0.0.1", 1, "u", "p")
    try {
      val e = intercept[IllegalStateException](CassandraGolden.connectOrExplain(c, "127.0.0.1:1"))
      assert(e.getMessage.contains("my-cassandra-server"))
    } finally c.close()
  }

  test("스키마 파일의 키스페이스 이름만 hws_golden 으로 바꾼다") {
    val stmts = CassandraGolden.schemaStatements()
    assert(stmts.head.startsWith("CREATE KEYSPACE IF NOT EXISTS hws_golden"))
    assert(stmts.tail.nonEmpty && stmts.tail.forall(_.startsWith("CREATE TABLE IF NOT EXISTS hws_golden.")))
    assert(!stmts.exists(_.contains(" ars.")))
  }

  test("테이블은 운영 ars 와 같은 다섯 개다", Golden) {
    assert(CassandraGolden.tables == Seq("customfiles", "emailsnapshot", "historylog", "snapshot", "snapshotlist"))
  }

  test("넣고, 전후 차이를 보고, 비운다", Golden) {
    CassandraGolden.clear()
    CassandraGolden.seed(Map("historylog" -> Seq("""{"eqpid":"EQP001","txn":7,"step":1,"body":"<b>1</b>"}""")))
    val snap = CassandraGolden.snapshot()
    assert(snap("historylog") == Seq("""{"body":"<b>1</b>","eqpid":"EQP001","step":1,"txn":7}"""))
    CassandraGolden.seed(Map("emailsnapshot" -> Seq("""{"eqpid":"EQP001","timestamp":1700000000000,"body":"0x68656c6c6f"}""")))
    assert(Diff.render(snap, CassandraGolden.snapshot()) ==
      Seq("emailsnapshot", """  + {"body":"<5 bytes sha256:2cf24dba5fb0>","eqpid":"EQP001","timestamp":1700000000000}"""))
    assert(CassandraGolden.emailSnapshotTimes() == Set(1700000000000L))
    CassandraGolden.clear()
    assert(CassandraGolden.snapshot().values.forall(_.isEmpty))
  }

  test("운영 코드의 connect(\"ars\") 를 테스트 키스페이스로 돌린다", Golden) {
    val s = CassandraGolden.redirectingCluster().connect("ars")
    try assert(s.getLoggedKeyspace == "hws_golden") finally s.close()
  }
}
```

- [ ] **Step 2: 실패를 확인한다**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && mvn -B -o test 2>&1 | grep -E 'error:|BUILD'
```
기대: `not found: value CassandraGolden` 컴파일 오류

- [ ] **Step 3: 구현한다**

`src/test/scala/com/sec/eeg/ars/testkit/ExternalFile.scala`:

```scala
package com.sec.eeg.ars.testkit

import java.io.File

/** 저장소 밖(ARS 형제 폴더)의 파일을 찾는다. mvn 은 user.dir=프로젝트 루트로 테스트를 돌린다. */
object ExternalFile {
  def find(rel: String): File = {
    val candidates = Seq(".", "..", "../..").map(p => new File(s"$p/$rel"))
    candidates.find(_.exists()).getOrElse(throw new IllegalStateException(
      s"외부 파일을 찾지 못했다: $rel (user.dir=${sys.props("user.dir")}). 찾아본 경로: " +
        candidates.map(_.getAbsolutePath).mkString(", ")))
  }

  def rmsContract: File = find("ResourceMonitorServer/tests/data/akka_email_contract.json")

  def cassandraSchema: File = find("docker/cassandra/ars-schema.cql")
}
```

`src/test/scala/com/sec/eeg/ars/testkit/CassandraGolden.scala`:

```scala
package com.sec.eeg.ars.testkit

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

import com.datastax.driver.core.{Cluster, Session}
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer

import scala.collection.JavaConverters._

/**
 * OrbStack my-cassandra-server 위의 테스트 키스페이스(hws_golden).
 * 스키마는 ARS/docker/cassandra/ars-schema.cql(운영 DESCRIBE 결과)을 직접 읽고 키스페이스 이름만 바꾼다.
 */
object CassandraGolden {
  val Keyspace = "hws_golden"
  lazy val host: String = sys.env.getOrElse("HWS_GOLDEN_CASSANDRA_HOST", "127.0.0.1")
  lazy val port: Int = sys.env.getOrElse("HWS_GOLDEN_CASSANDRA_PORT", "9042").toInt
  // 기본 계정은 ARS/docker/README.md 의 로컬 개발용 HttpWebServer 계정(운영 코드와 같은 값)이다.
  lazy val user: String = sys.env.getOrElse("HWS_GOLDEN_CASSANDRA_USER", "ars")
  lazy val password: String = sys.env.getOrElse("HWS_GOLDEN_CASSANDRA_PASSWORD", "visuallove")

  def openCluster(host: String, port: Int, user: String, password: String): Cluster =
    Cluster.builder().addContactPoint(host).withPort(port).withCredentials(user, password).build()

  /** 접속하고, 실패하면 무엇을 확인할지 알려 주는 예외를 던진다 */
  def connectOrExplain(c: Cluster, where: String): Session =
    try c.connect()
    catch {
      case e: Exception => throw new IllegalStateException(
        s"Cassandra 에 접속하지 못했다 ($where). OrbStack 의 my-cassandra-server 가 떠 있는지, " +
          "인증 설정이 ARS/docker/README.md 의 'Cassandra (compose 밖)' 절과 같은지 확인한다. 원인: " + e.getMessage, e)
    }

  private lazy val cluster: Cluster = openCluster(host, port, user, password)

  /** JVM 당 한 번: 테스트 키스페이스를 지우고 스키마 파일로 다시 만든다 */
  private lazy val admin: Session = {
    val s = connectOrExplain(cluster, s"$host:$port, 사용자 $user")
    require(Keyspace == "hws_golden", s"테스트 키스페이스 이름이 바뀌었다: $Keyspace")
    s.execute(s"DROP KEYSPACE IF EXISTS $Keyspace")
    schemaStatements().foreach(stmt => s.execute(stmt))
    s
  }

  def ensureReady(): Unit = admin

  /** ars-schema.cql 의 문장들을 테스트 키스페이스용으로 바꾼다 */
  def schemaStatements(): Seq[String] = {
    val text = new String(Files.readAllBytes(ExternalFile.cassandraSchema.toPath), UTF_8)
    text.split("\n").filterNot(_.trim.startsWith("--")).mkString("\n")
      .split(";").map(_.trim).filter(_.nonEmpty).toSeq
      .map(_.replace("KEYSPACE IF NOT EXISTS ars", s"KEYSPACE IF NOT EXISTS $Keyspace")
        .replace("TABLE IF NOT EXISTS ars.", s"TABLE IF NOT EXISTS $Keyspace."))
  }

  def tables: Seq[String] = {
    ensureReady()
    cluster.getMetadata.getKeyspace(Keyspace).getTables.asScala.map(_.getName).toSeq.sorted
  }

  /** 모든 테이블의 모든 파티션을 지운다. TRUNCATE 는 공유 컨테이너에 스냅샷을 쌓으므로 쓰지 않는다. */
  def clear(): Unit =
    for (t <- tables) {
      val pk = cluster.getMetadata.getKeyspace(Keyspace).getTable(t).getPartitionKey.asScala.map(_.getName)
      val keys = admin.execute(s"SELECT DISTINCT ${pk.mkString(", ")} FROM $Keyspace.$t").all().asScala
      keys.foreach { k =>
        admin.execute(s"DELETE FROM $Keyspace.$t WHERE " + pk.map(_ + " = ?").mkString(" AND "), pk.map(n => k.getObject(n)): _*)
      }
    }

  /** 테이블 -> INSERT JSON 문자열들 */
  def seed(rows: Map[String, Seq[String]]): Unit =
    rows.foreach { case (t, jsons) => jsons.foreach(j => admin.execute(s"INSERT INTO $Keyspace.$t JSON ?", j)) }

  /** 테이블 -> 정렬된 행 표기 */
  def snapshot(): Map[String, Seq[String]] =
    tables.map { t =>
      t -> admin.execute(s"SELECT * FROM $Keyspace.$t").all().asScala.toSeq.map { r =>
        Canonical.value(r.getColumnDefinitions.asList.asScala.map(d => d.getName -> r.getObject(d.getName)).toMap)
      }.sorted
    }.toMap

  /** emailsnapshot 의 timestamp 값들 (스냅샷 시각 정규화용) */
  def emailSnapshotTimes(): Set[Long] =
    admin.execute(s"SELECT timestamp FROM $Keyspace.emailsnapshot").all().asScala.map(_.getLong("timestamp")).toSet

  /** 운영 코드의 connect("ars") 를 테스트 키스페이스로 돌리는 Cluster (Mockito spy) */
  def redirectingCluster(): Cluster = {
    ensureReady()
    val spy = Mockito.mock(classOf[Cluster],
      Mockito.withSettings().spiedInstance(cluster).defaultAnswer(Mockito.CALLS_REAL_METHODS))
    Mockito.doAnswer(new Answer[Session] {
      def answer(i: InvocationOnMock): Session = cluster.connect(Keyspace)
    }).when(spy).connect("ars")
    spy
  }
}
```

- [ ] **Step 4: 통과를 확인한다**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && mvn -B -o test 2>&1 | grep -E 'Tests: |\*\*\* FAILED|ABORTED|error:|BUILD' && mvn -B -o test -Pgolden -Dsuites=com.sec.eeg.ars.testkit.CassandraGoldenSpec 2>&1 | grep -E 'Tests: |BUILD'
```
기대: 기본 `Tests: succeeded 36, failed 0`. CassandraGoldenSpec 단독 `-Pgolden` 은 `Tests: succeeded 5, failed 0`

- [ ] **Step 5: 로컬 `ars` 키스페이스가 그대로인지 확인한다**

```bash
for t in emailsnapshot historylog customfiles snapshot snapshotlist; do printf 'ars.%s=' $t; docker exec my-cassandra-server cqlsh -u cassandra -p cassandra -e "SELECT count(*) FROM ars.$t;" 2>/dev/null | awk 'NR==4{gsub(/ /,""); print}'; done
```
기대: 모두 `0` (시작 전과 같다)

- [ ] **Step 6: 커밋**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && git status --short   # 이 Task 의 파일만 보여야 한다
git add src/test/scala/com/sec/eeg/ars/testkit/ExternalFile.scala \
        src/test/scala/com/sec/eeg/ars/testkit/CassandraGolden.scala \
        src/test/scala/com/sec/eeg/ars/testkit/CassandraGoldenSpec.scala
git commit -q -F - <<'EOF'
골든 하네스: Cassandra 테스트 키스페이스(hws_golden)와 connect("ars") 우회

스키마는 ARS/docker/cassandra/ars-schema.cql(운영 DESCRIBE 결과)을 직접 읽는다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git log --oneline -1
```


## Task 6: 액터 하네스와 케이스 실행기 — ActorKit, GoldenCase, GoldenRunner

**Files:**
- Create: `src/test/scala/com/sec/eeg/ars/testkit/ActorKit.scala`, `src/test/scala/com/sec/eeg/ars/testkit/TempCase.scala`, `src/test/scala/com/sec/eeg/ars/testkit/GoldenCase.scala`, `src/test/scala/com/sec/eeg/ars/testkit/GoldenRunner.scala`
- Test: `src/test/scala/com/sec/eeg/ars/testkit/GoldenCaseSpec.scala`, `src/test/scala/com/sec/eeg/ars/testkit/GoldenRenderSpec.scala`, `src/test/scala/com/sec/eeg/ars/testkit/GoldenRunnerSpec.scala`

**Interfaces:**
- Consumes: `FixtureJson`, `ExternalFile`, `GoldenFile`, `MongoGolden`, `CassandraGolden`, `Diff`, `Canonical`, `JdkGuard`
- Produces:
  - `class Forwarder(target: ActorRef)` + `Forwarder.props`, `class Responder(probe: ActorRef, reply: AtomicReference[Any])` + `Responder.NoReply` + `Responder.props`, `class StubMaster(probe: ActorRef, children: Map[String, Props])` + `StubMaster.props`, `ActorKit.awaitActor(system, path, within = 5.seconds): ActorRef`
  - `TempCase.dir(message: String, name: String, json: String): File`
  - `final case class GoldenCase(dir, id, message, body: Option[String], params: Map[String,String], mongo, mongoAfterStart, cassandra: Map[String, Seq[String]], config: Map[String,String], knownIssue: Option[String])` + `expectedFile`, `filesDir`; `GoldenCase.dirs(message): Seq[File]`, `GoldenCase.load(dir): GoldenCase`, `GoldenCase.basePayload(base): JValue`
  - `GoldenMessages.build(c): (String, Any)`, `GoldenMessages.EmailWorkerPath`, `HttpWorkerPath`
  - `GoldenRender.render(c, reply, formats, mongo, cassandra, files): String`, `GoldenRender.replyText(r: Any): String`, `GoldenRender.normalizeTimes(text, newTimes: Seq[Long]): String`
  - `ServiceConfigState.capture()` / `.restore()`
  - `GoldenRunner.run(c: GoldenCase): String`, `GoldenRunner.DefaultPublicAddress = "hws.golden:8080"`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/scala/com/sec/eeg/ars/testkit/TempCase.scala`:

```scala
package com.sec.eeg.ars.testkit

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/** 하네스 자체 테스트용: 임시 폴더에 <message>/<name>/case.json 을 만든다 */
object TempCase {
  def dir(message: String, name: String, json: String): File = {
    val d = new File(Files.createTempDirectory("golden-temp-case").toFile, s"$message/$name")
    d.mkdirs()
    Files.write(new File(d, "case.json").toPath, json.getBytes(UTF_8))
    d
  }
}
```

`src/test/scala/com/sec/eeg/ars/testkit/GoldenCaseSpec.scala`:

```scala
package com.sec.eeg.ars.testkit

import com.sec.eeg.ars.actor.QueryHistory
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.scalatest.FunSuite

class GoldenCaseSpec extends FunSuite {
  private implicit val formats: Formats = DefaultFormats

  test("rms 기본 페이로드에 set·unset 을 적용한다") {
    val c = GoldenCase.load(TempCase.dir("SendEmail", "x",
      """{"message":"SendEmail","base":"rms:legacy","set":{"subcode":"MEM_WARN","app":"RMS"},"unset":["ip"]}"""))
    val body = parse(c.body.get)
    assert(c.id == "SendEmail/x")
    assert((body \ "subcode").extract[String] == "MEM_WARN")
    assert((body \ "app").extract[String] == "RMS")
    assert(body \ "ip" == JNothing)
    assert((body \ "hostname").extract[String] == "EQP001")
  }

  test("bodyRaw 는 그대로 보낸다") {
    val c = GoldenCase.load(TempCase.dir("SendEmail", "raw", """{"message":"SendEmail","bodyRaw":"{not json"}"""))
    assert(c.body.contains("{not json"))
  }

  test("한글, $file, params, config, knownIssue 를 읽는다") {
    val c = GoldenCase.load(TempCase.dir("EmailImage", "k",
      """{"message":"EmailImage","params":{"prefix":"P","fname":"한글.png"},"config":{"ServicePublicAddress":"a:1"},
        |"mongo":{"EMAIL_IMAGE_REPOSITORY":[{"name":"한글.png","body":{"$file":"_files/favicon.png"}}]},
        |"knownIssue":"설명"}""".stripMargin))
    assert(c.params("fname") == "한글.png")
    assert(c.config("ServicePublicAddress") == "a:1")
    assert(c.knownIssue.contains("설명"))
    assert(c.mongo("EMAIL_IMAGE_REPOSITORY").head.contains("\"$binary\""))
  }

  test("없는 fixture 는 케이스 이름과 경로를 알려 주며 실패한다") {
    val e = intercept[IllegalArgumentException](GoldenCase.load(TempCase.dir("SendEmail", "nofix",
      """{"message":"SendEmail","base":"rms:legacy","fixtures":["없는것"]}""")))
    assert(e.getMessage.contains("SendEmail/nofix"))
    assert(e.getMessage.contains("_fixtures/없는것.mongo.json"))
  }

  test("모르는 message, 빠진 본문·파라미터는 케이스 이름과 함께 실패한다") {
    val unknown = GoldenCase.load(TempCase.dir("Nope", "a", """{"message":"Nope"}"""))
    assert(intercept[IllegalArgumentException](GoldenMessages.build(unknown)).getMessage.contains("Nope/a: 모르는 message"))
    val noBody = GoldenCase.load(TempCase.dir("SendEmail", "b", """{"message":"SendEmail"}"""))
    assert(intercept[IllegalArgumentException](GoldenMessages.build(noBody)).getMessage.contains("base 또는 bodyRaw"))
    val noParam = GoldenCase.load(TempCase.dir("EmailImage", "c", """{"message":"EmailImage","params":{"prefix":"P"}}"""))
    assert(intercept[IllegalArgumentException](GoldenMessages.build(noParam)).getMessage.contains("params.fname"))
  }

  test("message 이름이 보낼 경로와 액터 메시지로 바뀐다") {
    val c = GoldenCase.load(TempCase.dir("QueryHistory", "q", """{"message":"QueryHistory","params":{"eqpid":"E","txn":"7"}}"""))
    assert(GoldenMessages.build(c) == (GoldenMessages.HttpWorkerPath, QueryHistory("E", 7L)))
  }
}
```

`src/test/scala/com/sec/eeg/ars/testkit/GoldenRenderSpec.scala`:

```scala
package com.sec.eeg.ars.testkit

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8

import com.sec.eeg.ars.actor.EmailFormat
import org.scalatest.FunSuite

class GoldenRenderSpec extends FunSuite {
  private val c = GoldenCase(new File("x/y"), "M/c", "M", None, Map.empty, Map.empty, Map.empty, Map.empty, Map.empty, Some("설명"))

  test("응답 종류별 표기") {
    assert(GoldenRender.replyText("""{"a":1}""") == """{"a":1}""")
    assert(GoldenRender.replyText("hello".getBytes(UTF_8)) == "<5 bytes sha256:2cf24dba5fb0>")
    assert(GoldenRender.replyText(html.history.render("<b>x</b>")).contains("<b>x</b>"))
    assert(GoldenRender.replyText(org.scalatra.InternalServerError("There is no data")) == "ActionResult(status=500, body=There is no data)")
  }

  test("구역 순서와 KNOWN-ISSUE 머리말") {
    val text = GoldenRender.render(c, "ok", Seq(EmailFormat("ARS", "CAT", "[t]:<p>본문</p>")), Seq("C", "  + {}"), Nil, Nil)
    assert(text ==
      """# case: M/c
        |# KNOWN-ISSUE: 설명
        |== reply ==
        |ok
        |== redis ==
        |EmailFormat(project=ARS, category=CAT)
        |[t]:<p>본문</p>
        |== mongo ==
        |C
        |  + {}
        |== cassandra ==
        |(변경 없음)
        |== files ==
        |(변경 없음)
        |""".stripMargin)
  }

  test("스냅샷 시각은 작은 것부터 <T1>, <T2>") {
    val t = "link/1790000000002 row 1790000000001 again 1790000000002"
    assert(GoldenRender.normalizeTimes(t, Seq(1790000000002L, 1790000000001L)) == "link/<T2> row <T1> again <T2>")
  }
}
```

`src/test/scala/com/sec/eeg/ars/testkit/GoldenRunnerSpec.scala`:

```scala
package com.sec.eeg.ars.testkit

import com.sec.eeg.ars.data.ServiceConfig
import org.scalatest.FunSuite

class GoldenRunnerSpec extends FunSuite {
  test("팝업 조회 한 건을 실행해 정해진 형식으로 그린다", Golden) {
    val c = GoldenCase.load(TempCase.dir("PopupContent", "selftest",
      """{"message":"PopupContent","params":{"process":"P","model":"M","code":"C"},
        |"mongo":{"POPUP_TEMPLATE_REPOSITORY":[{"process":"P","model":"M","code":"C","html":"<div>셀프테스트</div>"}]}}""".stripMargin))
    assert(GoldenRunner.run(c) ==
      """# case: PopupContent/selftest
        |== reply ==
        |<div>셀프테스트</div>
        |== redis ==
        |(변경 없음)
        |== mongo ==
        |(변경 없음)
        |== cassandra ==
        |(변경 없음)
        |== files ==
        |(변경 없음)
        |""".stripMargin)
  }

  test("스냅샷이 붙은 메일: 테스트 키스페이스에 저장되고 링크와 행의 시각이 같은 <T1> 이 된다", Golden) {
    val c = GoldenCase.load(TempCase.dir("SendEmail", "selftest",
      """{"message":"SendEmail","base":"rms:legacy",
        |"set":{"variables":{"__snapshot__":{"$file":"_files/favicon.png"}}},
        |"mongo":{
        |  "EMAIL_TEMPLATE_REPOSITORY":[{"process":"PHOTO","model":"MODEL-A","code":"RESOURCE_MONITOR","subcode":"CPU_CRITICAL","title":"T","html":"<p>snap=@__snapshot__</p>"}],
        |  "EMAIL_RECIPIENTS":[{"app":"ARS","code":"RESOURCE_MONITOR","process":"PHOTO","model":"MODEL-A","line":"L1","emailCategory":"EMAIL-TEST-1"}]}}""".stripMargin))
    val text = GoldenRunner.run(c)
    assert(text.contains("EmailFormat(project=ARS, category=EMAIL-TEST-1)"))
    assert(text.contains("[EARS][T][EQP001][RESOURCE_MONITOR-CPU_CRITICAL]:<p>snap=http://hws.golden:8080/ARS/SnapShotImage/EQP001/<T1></p>"))
    assert(text.contains("""  + {"body":"<286 bytes sha256:99d69510d1da>","eqpid":"EQP001","timestamp":<T1>}"""))
  }

  test("실행 뒤 ServiceConfig 값을 되돌린다", Golden) {
    val before = ServiceConfigState.capture()
    ServiceConfig.ServicePublicAddress = "before:1"
    try {
      GoldenRunner.run(GoldenCase.load(TempCase.dir("PopupContent", "restore",
        """{"message":"PopupContent","params":{"process":"P","model":"M","code":"X"}}""")))
      assert(ServiceConfig.ServicePublicAddress == "before:1")
    } finally before.restore()
  }
}
```

- [ ] **Step 2: 실패를 확인한다**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && mvn -B -o test 2>&1 | grep -E 'error:|BUILD'
```
기대: `not found: value GoldenCase` 등 컴파일 오류

- [ ] **Step 3: 구현한다**

`src/test/scala/com/sec/eeg/ars/testkit/ActorKit.scala`:

```scala
package com.sec.eeg.ars.testkit

import java.util.concurrent.atomic.AtomicReference

import akka.actor.{Actor, ActorIdentity, ActorRef, ActorSystem, Identify, Props}
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.Await
import scala.concurrent.duration._

/** 받은 메시지를 그대로 target 에 넘긴다 (골든 계층의 RedisActor 자리) */
class Forwarder(target: ActorRef) extends Actor {
  def receive = { case m => target forward m }
}

object Forwarder {
  def props(target: ActorRef): Props = Props(new Forwarder(target))
}

/** 받은 메시지를 probe 에 알리고, reply 에 든 값으로 응답한다 (라우팅 계층의 HttpWorker·EmailWorker 자리) */
class Responder(probe: ActorRef, reply: AtomicReference[Any]) extends Actor {
  def receive = {
    case m =>
      probe ! m
      reply.get() match {
        case Responder.NoReply => ()
        case r => sender() ! r
      }
  }
}

object Responder {
  /** reply 에 이 값이 들어 있으면 응답하지 않는다 */
  case object NoReply

  def props(probe: ActorRef, reply: AtomicReference[Any]): Props = Props(new Responder(probe, reply))
}

/** /user/Master 자리. 자식을 이름대로 만들고, 자기에게 온 메시지는 probe 에 넘긴다 */
class StubMaster(probe: ActorRef, children: Map[String, Props]) extends Actor {
  override def preStart(): Unit = children.foreach { case (name, props) => context.actorOf(props, name) }

  def receive = { case m => probe ! m }
}

object StubMaster {
  def props(probe: ActorRef, children: Map[String, Props]): Props = Props(new StubMaster(probe, children))
}

object ActorKit {
  /** path 의 액터가 생기고 preStart 를 마칠 때까지 기다린다 (Identify 는 preStart 뒤에 처리된다) */
  def awaitActor(system: ActorSystem, path: String, within: FiniteDuration = 5.seconds): ActorRef = {
    implicit val timeout: Timeout = Timeout(1.second)
    val deadline = within.fromNow
    while (deadline.hasTimeLeft()) {
      Await.result(system.actorSelection(path) ? Identify(path), 2.seconds) match {
        case ActorIdentity(_, Some(ref)) => return ref
        case _ => Thread.sleep(50)
      }
    }
    throw new IllegalStateException(s"액터가 생기지 않았다: $path")
  }
}
```

`src/test/scala/com/sec/eeg/ars/testkit/GoldenCase.scala`:

```scala
package com.sec.eeg.ars.testkit

import java.io.File

import com.sec.eeg.ars.testkit.FixtureJson._
import org.json4s._

/**
 * golden/<메시지>/<케이스>/case.json 한 건 (설계 5.2).
 *  - message: 액터 메시지 이름 (SendEmail, QueryHistory, ...)
 *  - base: 요청 본문의 출발점. "rms:legacy|rendered|grouped" 는 RMS 계약 파일, 그 밖은 _fixtures/payloads/<base>.json
 *  - set / unset: base 위에 덮어쓸 필드 / 지울 필드 (최상위 필드 단위)
 *  - bodyRaw: 본문을 문자열 그대로 보낼 때 (깨진 JSON 케이스)
 *  - params: GET 계열 메시지의 필드
 *  - fixtures: _fixtures/<이름>.mongo.json, _fixtures/<이름>.cassandra.json 을 차례로 합친다
 *  - mongo / cassandra: 케이스 전용 문서·행
 *  - mongoAfterStart: 액터가 시작한 뒤에 넣는 문서 (시작 때 한 번만 읽는 동작을 보이기 위한 것)
 *  - config: ServicePublicAddress, EmailTemplateImportLocation, PopupTemplateImportLocation
 *  - knownIssue: 결함을 기록한 케이스의 설명 (expected.txt 머리말이 된다)
 */
final case class GoldenCase(
  dir: File,
  id: String,
  message: String,
  body: Option[String],
  params: Map[String, String],
  mongo: Map[String, Seq[String]],
  mongoAfterStart: Map[String, Seq[String]],
  cassandra: Map[String, Seq[String]],
  config: Map[String, String],
  knownIssue: Option[String]) {

  def expectedFile: File = new File(dir, "expected.txt")

  def filesDir: File = new File(dir, "files")
}

object GoldenCase {
  private implicit val formats: Formats = DefaultFormats

  /** golden/<message>/ 아래 case.json 이 있는 폴더들 (이름순) */
  def dirs(message: String): Seq[File] = {
    val root = new File(GoldenFile.Root, message)
    val found = Option(root.listFiles()).getOrElse(Array.empty[File])
      .filter(d => new File(d, "case.json").isFile).sortBy(_.getName).toSeq
    if (found.isEmpty) throw new IllegalStateException(s"케이스가 없다: ${root.getPath}")
    found
  }

  def load(dir: File): GoldenCase = {
    val id = dir.getParentFile.getName + "/" + dir.getName
    try {
      val j = read(new File(dir, "case.json"))
      val message = (j \ "message").extractOpt[String].getOrElse(throw new IllegalArgumentException("message 가 없다"))
      val fixtures = (j \ "fixtures").extractOpt[List[String]].getOrElse(Nil)
      fixtures.foreach(checkFixture)
      GoldenCase(
        dir = dir,
        id = id,
        message = message,
        body = body(j),
        params = (j \ "params").extractOpt[Map[String, String]].getOrElse(Map.empty),
        mongo = merge(fixtures.flatMap(f => fixture(f, "mongo")).map(docs(_, AsMongoBinary)) :+ docs(j \ "mongo", AsMongoBinary)),
        mongoAfterStart = docs(j \ "mongoAfterStart", AsMongoBinary),
        cassandra = merge(fixtures.flatMap(f => fixture(f, "cassandra")).map(docs(_, AsCassandraBlob)) :+ docs(j \ "cassandra", AsCassandraBlob)),
        config = (j \ "config").extractOpt[Map[String, String]].getOrElse(Map.empty),
        knownIssue = (j \ "knownIssue").extractOpt[String])
    } catch {
      case e: Exception => throw new IllegalArgumentException(s"case.json 을 읽지 못했다: $id — ${e.getMessage}", e)
    }
  }

  private def fixture(name: String, kind: String): Option[JValue] = {
    val f = new File(GoldenFile.Root, s"_fixtures/$name.$kind.json")
    if (f.isFile) Some(read(f)) else None
  }

  private def checkFixture(name: String): Unit =
    if (fixture(name, "mongo").isEmpty && fixture(name, "cassandra").isEmpty)
      throw new IllegalArgumentException(s"fixture 가 없다: _fixtures/$name.mongo.json 또는 _fixtures/$name.cassandra.json")

  /** {"이름": [문서, ...]} -> 이름 -> 문서 JSON 문자열들 */
  private def docs(v: JValue, mode: FileMode): Map[String, Seq[String]] = v match {
    case JNothing => Map.empty
    case JObject(fields) => fields.map {
      case (name, JArray(xs)) => name -> xs.map(x => compactJson(resolveFiles(x, mode)))
      case (name, _) => throw new IllegalArgumentException(s"$name 의 값은 배열이어야 한다")
    }.toMap
    case _ => throw new IllegalArgumentException("문서 묶음은 {\"이름\": [ ... ]} 형식이어야 한다")
  }

  private def merge(parts: Seq[Map[String, Seq[String]]]): Map[String, Seq[String]] =
    parts.foldLeft(Map.empty[String, Seq[String]]) { (acc, m) =>
      m.foldLeft(acc) { case (a, (k, v)) => a.updated(k, a.getOrElse(k, Nil) ++ v) }
    }

  private def body(j: JValue): Option[String] = (j \ "bodyRaw", j \ "base") match {
    case (JString(raw), _) => Some(raw)
    case (JNothing, JString(base)) =>
      val set = j \ "set" match {
        case JObject(fs) => fs
        case JNothing => Nil
        case _ => throw new IllegalArgumentException("set 은 객체여야 한다")
      }
      val unset = (j \ "unset").extractOpt[List[String]].getOrElse(Nil)
      val merged = basePayload(base) match {
        case JObject(fs) => JObject(fs.filterNot { case (k, _) => unset.contains(k) || set.exists(_._1 == k) } ++ set)
        case _ => throw new IllegalArgumentException(s"base 가 객체가 아니다: $base")
      }
      Some(compactJson(resolveFiles(merged, AsBase64)))
    case (JNothing, JNothing) => None
    case _ => throw new IllegalArgumentException("bodyRaw 와 base 는 문자열이어야 한다")
  }

  def basePayload(base: String): JValue =
    if (base.startsWith("rms:")) {
      val key = base.stripPrefix("rms:")
      read(ExternalFile.rmsContract) \ key match {
        case o: JObject => o
        case _ => throw new IllegalArgumentException(s"RMS 계약 파일에 $key 가 없다")
      }
    } else {
      val f = new File(GoldenFile.Root, s"_fixtures/payloads/$base.json")
      if (!f.isFile) throw new IllegalArgumentException(s"base 페이로드가 없다: ${f.getPath}")
      read(f)
    }
}
```

`src/test/scala/com/sec/eeg/ars/testkit/GoldenRunner.scala`:

```scala
package com.sec.eeg.ars.testkit

import java.io.File
import java.nio.file.Files

import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.testkit.TestProbe
import akka.util.Timeout
import com.mongodb.client.MongoDatabase
import com.sec.eeg.ars.actor._
import com.sec.eeg.ars.data.ServiceConfig
import com.typesafe.config.ConfigFactory
import org.apache.commons.io.FileUtils

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.duration._

/** 테스트가 바꾸는 ServiceConfig 전역 값을 저장했다가 되돌린다 */
final case class ServiceConfigState(database: MongoDatabase, publicAddress: String, emailImport: String, popupImport: String) {
  def restore(): Unit = {
    ServiceConfig.database = database
    ServiceConfig.ServicePublicAddress = publicAddress
    ServiceConfig.EmailTemplateImportLocation = emailImport
    ServiceConfig.PopupTemplateImportLocation = popupImport
  }
}

object ServiceConfigState {
  def capture(): ServiceConfigState = ServiceConfigState(
    ServiceConfig.database, ServiceConfig.ServicePublicAddress,
    ServiceConfig.EmailTemplateImportLocation, ServiceConfig.PopupTemplateImportLocation)
}

/** case.json 의 message 를 실제 액터 메시지와 보낼 경로로 바꾼다 */
object GoldenMessages {
  val EmailWorkerPath = "/user/Master/EmailWorker"
  val HttpWorkerPath = "/user/Master/HttpWorker"

  def build(c: GoldenCase): (String, Any) = {
    def body: String = c.body.getOrElse(throw new IllegalArgumentException(s"${c.id}: ${c.message} 에는 base 또는 bodyRaw 가 필요하다"))
    def p(name: String): String = c.params.getOrElse(name, throw new IllegalArgumentException(s"${c.id}: params.$name 이 필요하다"))
    c.message match {
      case "SendEmail" => (EmailWorkerPath, SendEmail(body))
      case "SendEmailForRTM" => (EmailWorkerPath, SendEmailForRTM(body))
      case "SendRecoveryEmail" => (EmailWorkerPath, SendRecoveryEmail(body))
      case "ScriptResult" => (EmailWorkerPath, ScriptResult(body))
      case "LoadEmailTemplate" => (EmailWorkerPath, LoadEmailTemplate())
      case "LoadPopupTemplate" => (EmailWorkerPath, LoadPopupTemplate())
      case "EmailImage" => (EmailWorkerPath, EmailImage(p("prefix"), p("fname")))
      case "PopupContent" => (EmailWorkerPath, PopupContent(p("process"), p("model"), p("code")))
      case "PopupContentV2" => (EmailWorkerPath, PopupContentV2(p("process"), p("model"), p("code")))
      case "CustomFiles" => (EmailWorkerPath, CustomFiles(p("eqpid"), p("year").toInt, p("month").toInt, p("fname")))
      case "SnapShotImage" => (EmailWorkerPath, SnapShotImage(p("eqpid"), p("crtime")))
      case "AddHistory" => (HttpWorkerPath, AddHistory(body))
      case "QueryHistory" => (HttpWorkerPath, QueryHistory(p("eqpid"), p("txn").toLong))
      case "SaveCustomsFile" => (HttpWorkerPath, SaveCustomsFile(body))
      case other => throw new IllegalArgumentException(s"${c.id}: 모르는 message: $other")
    }
  }
}

/** 수집한 결과를 expected.txt 형식으로 그린다 (설계 5.3·5.4) */
object GoldenRender {
  def render(c: GoldenCase, reply: Any, formats: Seq[EmailFormat], mongo: Seq[String], cassandra: Seq[String], files: Seq[String]): String = {
    val sb = new StringBuilder
    sb.append(s"# case: ${c.id}\n")
    c.knownIssue.foreach(k => sb.append(s"# KNOWN-ISSUE: $k\n"))
    sb.append("== reply ==\n").append(replyText(reply)).append("\n")
    sb.append("== redis ==\n")
    if (formats.isEmpty) sb.append("(변경 없음)\n")
    else formats.foreach { f =>
      sb.append(s"EmailFormat(project=${f.project}, category=${f.category})\n").append(f.body).append("\n")
    }
    section(sb, "mongo", mongo)
    section(sb, "cassandra", cassandra)
    section(sb, "files", files)
    sb.toString
  }

  private def section(sb: StringBuilder, name: String, lines: Seq[String]): Unit = {
    sb.append(s"== $name ==\n")
    if (lines.isEmpty) sb.append("(변경 없음)\n") else lines.foreach(l => sb.append(l).append("\n"))
  }

  def replyText(r: Any): String = r match {
    case s: String => s
    case b: Array[Byte] => Canonical.bytes(b)
    case h: play.twirl.api.Html => h.body
    case a: org.scalatra.ActionResult => s"ActionResult(status=${a.status.code}, body=${a.body})"
    case other => s"${other.getClass.getName}: $other"
  }

  /** 실행 중에 생긴 스냅샷 시각을 오름차순으로 <T1>, <T2> ... 로 바꾼다 */
  def normalizeTimes(text: String, newTimes: Seq[Long]): String =
    newTimes.sorted.zipWithIndex.foldLeft(text) { case (t, (time, i)) => t.replace(time.toString, s"<T${i + 1}>") }
}

/** 골든 케이스 한 건을 실행해 expected.txt 형식의 텍스트를 만든다 (설계 4.2) */
object GoldenRunner {
  val DefaultPublicAddress = "hws.golden:8080"

  def run(c: GoldenCase): String = {
    JdkGuard.require8()
    CassandraGolden.ensureReady()
    // 1. 비우기  2. 사전 상태 넣기
    MongoGolden.reset()
    CassandraGolden.clear()
    MongoGolden.seed(c.mongo)
    CassandraGolden.seed(c.cassandra)
    val importRoot = Files.createTempDirectory("hws-golden-import").toFile
    if (c.filesDir.isDirectory) FileUtils.copyDirectory(c.filesDir, importRoot)
    // 3. ServiceConfig 설정
    val saved = ServiceConfigState.capture()
    ServiceConfig.database = MongoGolden.database
    ServiceConfig.ServicePublicAddress = c.config.getOrElse("ServicePublicAddress", DefaultPublicAddress)
    ServiceConfig.EmailTemplateImportLocation = new File(importRoot, c.config.getOrElse("EmailTemplateImportLocation", "email")).getPath
    ServiceConfig.PopupTemplateImportLocation = new File(importRoot, c.config.getOrElse("PopupTemplateImportLocation", "popup")).getPath
    implicit val system: ActorSystem = ActorSystem("golden")
    try {
      // 4. 액터 띄우기 (preStart 가 끝날 때까지 기다린 뒤 mongoAfterStart 를 넣는다)
      val redis = TestProbe()
      system.actorOf(StubMaster.props(TestProbe().ref, Map(
        "RedisActor" -> Forwarder.props(redis.ref),
        "EmailWorker" -> Props(classOf[EmailWorker], ConfigFactory.empty(), CassandraGolden.redirectingCluster()),
        "HttpWorker" -> Props(classOf[HttpWorker], CassandraGolden.redirectingCluster()))), "Master")
      val (path, msg) = GoldenMessages.build(c)
      val target = ActorKit.awaitActor(system, path)
      MongoGolden.seed(c.mongoAfterStart)
      val mongoBefore = MongoGolden.snapshot()
      val cassandraBefore = CassandraGolden.snapshot()
      val timesBefore = CassandraGolden.emailSnapshotTimes()
      val filesBefore = listFiles(importRoot)
      // 5. 보내고 모으기 (설계 5.6)
      val reply = Await.result(target.ask(msg)(Timeout(5.seconds)), 6.seconds)
      val formats = redis.receiveWhile(max = 1.second, idle = 500.millis) { case e: EmailFormat => e }
      // 6. 전후 비교
      val newTimes = (CassandraGolden.emailSnapshotTimes() -- timesBefore).toSeq
      val text = GoldenRender.render(c, reply, formats,
        Diff.render(mongoBefore, MongoGolden.snapshot()),
        Diff.render(cassandraBefore, CassandraGolden.snapshot()),
        fileChanges(filesBefore, listFiles(importRoot)))
      GoldenRender.normalizeTimes(text, newTimes)
    } finally {
      // 7. 정리
      Await.ready(system.terminate(), 10.seconds)
      saved.restore()
      FileUtils.deleteQuietly(importRoot)
    }
  }

  private def listFiles(root: File): Set[String] =
    FileUtils.listFiles(root, null, true).asScala.map(f => root.toPath.relativize(f.toPath).toString).toSet

  private def fileChanges(before: Set[String], after: Set[String]): Seq[String] =
    (before -- after).toSeq.sorted.map("- " + _) ++ (after -- before).toSeq.sorted.map("+ " + _)
}
```

- [ ] **Step 4: 통과를 확인한다**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && mvn -B -o test 2>&1 | grep -E 'Tests: |\*\*\* FAILED|ABORTED|error:|BUILD' && mvn -B -o test -Pgolden 2>&1 | grep -E 'Tests: |\*\*\* FAILED|ABORTED|error:|BUILD'
```
기대: 기본 `Tests: succeeded 45, failed 0`, `-Pgolden` `Tests: succeeded 54, failed 0`

- [ ] **Step 5: 커밋**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && git status --short   # 이 Task 의 파일만 보여야 한다
git add src/test/scala/com/sec/eeg/ars/testkit/ActorKit.scala \
        src/test/scala/com/sec/eeg/ars/testkit/TempCase.scala \
        src/test/scala/com/sec/eeg/ars/testkit/GoldenCase.scala \
        src/test/scala/com/sec/eeg/ars/testkit/GoldenRunner.scala \
        src/test/scala/com/sec/eeg/ars/testkit/GoldenCaseSpec.scala \
        src/test/scala/com/sec/eeg/ars/testkit/GoldenRenderSpec.scala \
        src/test/scala/com/sec/eeg/ars/testkit/GoldenRunnerSpec.scala
git commit -q -F - <<'EOF'
골든 하네스: 가짜 Master·케이스 로더·실행기

진짜 EmailWorker·HttpWorker 를 운영 경로(/user/Master/...)에 띄우고 응답·Redis 메시지·
DB 변경·폴더 이동을 expected.txt 형식으로 그린다. 스냅샷 시각은 <T1>, <T2> 로 정규화한다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git log --oneline -1
```


## Task 7: 단위 계층 — ImageUtils, Twirl history, JSON 포맷

**Files:**
- Create: `src/test/resources/golden/_files/favicon.tif`, `src/test/resources/golden/HistoryTemplate/expected.html` (갱신 모드로 기록)
- Test: `src/test/scala/com/sec/eeg/ars/utils/ImageUtilsSpec.scala`, `src/test/scala/com/sec/eeg/ars/twirl/HistoryTemplateSpec.scala`, `src/test/scala/com/sec/eeg/ars/data/JsonInterfacesSpec.scala`

**Interfaces:**
- Consumes: `GoldenFile`, `JdkGuard`
- Produces: `_files/favicon.tif` (Task 12 가 쓴다)

이 Task 는 기존 코드를 특성화한다. 테스트를 쓰면 바로 통과하는 것이 정상이다 (단, Twirl 골든 파일은 기록 전까지 실패한다 = RED).

- [ ] **Step 1: 테스트용 TIFF 를 만든다**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && sips -s format tiff src/main/_webapp/img/favicon.png --out src/test/resources/golden/_files/favicon.tif >/dev/null && file src/test/resources/golden/_files/favicon.tif
```
기대: `TIFF image data, … width=48` (해시는 macOS 버전마다 다를 수 있어 고정하지 않는다)

- [ ] **Step 2: 테스트를 쓴다**

`src/test/scala/com/sec/eeg/ars/utils/ImageUtilsSpec.scala`:

```scala
package com.sec.eeg.ars.utils

import java.io.{ByteArrayInputStream, File}
import java.nio.file.Files

import com.sec.eeg.ars.testkit.{GoldenFile, JdkGuard}
import javax.imageio.ImageIO
import org.scalatest.FunSuite

class ImageUtilsSpec extends FunSuite {
  private def sample(name: String): Array[Byte] = Files.readAllBytes(new File(GoldenFile.Root, s"_files/$name").toPath)

  test("KNOWN-ISSUE 1: JDK 8 ImageIO 에는 TIFF 리더가 없어 정상 TIFF 도 null 이 된다") {
    JdkGuard.require8()
    assert(!ImageIO.getReaderFormatNames.map(_.toLowerCase).contains("tiff"))
    assert(ImageUtils.convertTiffBytesToJpegBytes(sample("favicon.tif")) == null)
  }

  test("PNG 는 같은 크기의 JPEG 로 바뀌고 결과는 매번 같다") {
    val png = sample("favicon.png")
    val out = ImageUtils.convertTiffBytesToJpegBytes(png)
    assert(out != null)
    assert(out(0) == 0xFF.toByte && out(1) == 0xD8.toByte)
    val img = ImageIO.read(new ByteArrayInputStream(out))
    assert(img.getWidth == 48 && img.getHeight == 48)
    assert(java.util.Arrays.equals(out, ImageUtils.convertTiffBytesToJpegBytes(png)))
  }

  test("깨진 바이트와 빈 배열은 null") {
    assert(ImageUtils.convertTiffBytesToJpegBytes(Array[Byte](1, 2, 3)) == null)
    assert(ImageUtils.convertTiffBytesToJpegBytes(Array.empty[Byte]) == null)
  }
}
```

`src/test/scala/com/sec/eeg/ars/twirl/HistoryTemplateSpec.scala`:

```scala
package com.sec.eeg.ars.twirl

import java.io.File

import com.sec.eeg.ars.testkit.GoldenFile
import org.scalatest.FunSuite

class HistoryTemplateSpec extends FunSuite {
  test("이력 화면 HTML (KNOWN-ISSUE 3: 본문을 이스케이프하지 않는다)") {
    val body = html.history.render("<table><tr><td>1단계</td></tr></table><script>alert(1)</script>").body
    assert(body.contains("<script>alert(1)</script>"))
    GoldenFile.check(new File(GoldenFile.Root, "HistoryTemplate/expected.html"), body).foreach(m => fail(m))
  }
}
```

`src/test/scala/com/sec/eeg/ars/data/JsonInterfacesSpec.scala`:

```scala
package com.sec.eeg.ars.data

import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.scalatest.FunSuite

class JsonInterfacesSpec extends FunSuite {
  private implicit val formats: Formats = DefaultFormats

  private def mappingError[T: Manifest](json: String): String = intercept[MappingException](parse(json).extract[T]).getMessage

  private val email = """"hostname":"H","ip":"I","app":"A","process":"P","model":"M","line":"L","code":"C","subcode":"S""""
  private val emailNullHost = email.replace("\"hostname\":\"H\"", "\"hostname\":null")

  test("EmailHttpDataFormat: 모르는 필드는 무시, 숫자는 문자열로, null 은 그대로 null") {
    assert(parse(s"""{$email,"variables":{"k":"v"},"zzz":1}""").extract[EmailHttpDataFormat] ==
      EmailHttpDataFormat("H", "I", "A", "P", "M", "L", "C", "S", Map("k" -> "v")))
    assert(parse(s"""{$email,"variables":{"k":1}}""").extract[EmailHttpDataFormat].variables == Map("k" -> "1"))
    assert(parse(s"""{$email,"variables":{},"renderedBody":5}""").extract[EmailHttpDataFormat].renderedBody.contains("5"))
    assert(parse(s"""{$emailNullHost,"variables":{}}""").extract[EmailHttpDataFormat].hostname == null)
  }

  test("EmailHttpDataFormat: 필수 필드가 없으면 MappingException") {
    assert(mappingError[EmailHttpDataFormat](s"""{$email}""") == "No usable value for variables\nExpected object but got JNothing")
    assert(mappingError[EmailHttpDataFormat]("""{"hostname":"H","ip":"I","app":"A","process":"P","model":"M","line":"L","code":"C","variables":{}}""") ==
      "No usable value for subcode\nDid not find value which can be converted into java.lang.String")
  }

  test("RecoveryEmailHttpDataFormat, EARSRTMEmailHttpDataFormat") {
    assert(parse("""{"hostname":"H","process":"P","line":"L","model":"M","scname":"S","title":"T","body":"B","variables":{}}""").extract[RecoveryEmailHttpDataFormat] ==
      RecoveryEmailHttpDataFormat("H", "P", "L", "M", "S", "T", "B", Map()))
    assert(mappingError[RecoveryEmailHttpDataFormat]("""{"hostname":"H","process":"P","line":"L","model":"M","scname":"S","title":"T","variables":{}}""") ==
      "No usable value for body\nDid not find value which can be converted into java.lang.String")
    assert(parse("""{"process":"P","model":"M","line":"L","eqpid":"E","code":"C","variables":{}}""").extract[EARSRTMEmailHttpDataFormat] ==
      EARSRTMEmailHttpDataFormat("P", "M", "L", "E", "C", Map()))
    assert(mappingError[EARSRTMEmailHttpDataFormat]("""{"process":"P","model":"M","line":"L","code":"C","variables":{}}""") ==
      "No usable value for eqpid\nDid not find value which can be converted into java.lang.String")
  }

  test("ScriptResultFormat: success 가 문자열이면 MappingException") {
    assert(parse("""{"success":true,"hostname":"H","ip":"I","process":"P","line":"L","model":"M","scname":"S","output":"O","variables":{}}""").extract[ScriptResultFormat] ==
      ScriptResultFormat(true, "H", "I", "P", "L", "M", "S", "O", Map()))
    assert(mappingError[ScriptResultFormat]("""{"success":"true","hostname":"H","ip":"I","process":"P","line":"L","model":"M","scname":"S","output":"O","variables":{}}""") ==
      "No usable value for success\nDo not know how to convert JString(true) into boolean")
  }

  test("CustomFilesFormat: year 가 문자열이거나 fname 이 없으면 MappingException") {
    assert(parse("""{"hostname":"H","year":2026,"month":7,"fname":"a.txt","contents":"AAEC"}""").extract[CustomFilesFormat] ==
      CustomFilesFormat("H", 2026, 7, "a.txt", "AAEC"))
    assert(mappingError[CustomFilesFormat]("""{"hostname":"H","year":"2026","month":7,"fname":"a.txt","contents":"AAEC"}""") ==
      "No usable value for year\nDo not know how to convert JString(2026) into int")
    assert(mappingError[CustomFilesFormat]("""{"hostname":"H","year":2026,"month":7,"contents":"AAEC"}""") ==
      "No usable value for fname\nDid not find value which can be converted into java.lang.String")
  }

  test("ARSHttpDataFormat: 큰 txn 은 Long, image 가 없으면 MappingException") {
    assert(parse("""{"hostname":"H","step":1,"txn":1791279459916,"text":"T","image":"I","refimage":"R","imageWidth":1,"imageHeight":2,"refimageWidth":3,"refimageHeight":4}""").extract[ARSHttpDataFormat] ==
      ARSHttpDataFormat("H", 1, 1791279459916L, "T", "I", "R", 1, 2, 3, 4))
    assert(mappingError[ARSHttpDataFormat]("""{"hostname":"H","step":1,"txn":2,"text":"T","refimage":"R","imageWidth":1,"imageHeight":2,"refimageWidth":3,"refimageHeight":4}""") ==
      "No usable value for image\nDid not find value which can be converted into java.lang.String")
  }

  test("HttpResponse.toJson: null 메시지는 null, 한글·따옴표·줄바꿈은 JSON 이스케이프") {
    assert(JsonInterfaces.toJson(HttpResponse("Success", "")) == """{"result":"Success","message":""}""")
    assert(JsonInterfaces.toJson(HttpResponse("Failed", null)) == """{"result":"Failed","message":null}""")
    assert(JsonInterfaces.toJson(HttpResponse("Fail", "한글 \"q\" \\ \n")) == """{"result":"Fail","message":"한글 \"q\" \\ \n"}""")
  }
}
```

- [ ] **Step 3: Twirl 골든 파일이 없어 실패하는지 확인한다 (RED)**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && mvn -B -o test 2>&1 | grep -E 'Tests: |\*\*\* FAILED|ABORTED|error:|BUILD'
```
기대: `이력 화면 HTML … *** FAILED ***` 1개 (메시지 `골든 파일이 없다`), `Tests: succeeded 55, failed 1`

- [ ] **Step 4: 기록하고 검토한다**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && mvn -B -o test -Dgolden.update=true -Dsuites=com.sec.eeg.ars.twirl.HistoryTemplateSpec 2>&1 | grep -E 'Tests: '
grep -c '<script>alert(1)</script>' src/test/resources/golden/HistoryTemplate/expected.html
```
기대: `succeeded 1`, `1` (KNOWN-ISSUE 3: 본문이 이스케이프 없이 들어간다). 파일은 빈 줄 두 개 뒤 `<!doctype html>` 로 시작한다.

- [ ] **Step 5: 통과를 확인한다**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && mvn -B -o test 2>&1 | grep -E 'Tests: |\*\*\* FAILED|ABORTED|error:|BUILD'
```
기대: `Tests: succeeded 56, failed 0`

- [ ] **Step 6: 커밋**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && git status --short   # 이 Task 의 파일만 보여야 한다
git add src/test/resources/golden/_files/favicon.tif \
        src/test/resources/golden/HistoryTemplate/expected.html \
        src/test/scala/com/sec/eeg/ars/utils/ImageUtilsSpec.scala \
        src/test/scala/com/sec/eeg/ars/twirl/HistoryTemplateSpec.scala \
        src/test/scala/com/sec/eeg/ars/data/JsonInterfacesSpec.scala
git commit -q -F - <<'EOF'
단위 계층: ImageUtils·Twirl history·JSON 포맷 특성화

KNOWN-ISSUE 1 (JDK 8 TIFF 변환 null), KNOWN-ISSUE 3 (이력 HTML 미이스케이프)를 현재 동작으로 고정한다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git log --oneline -1
```


## Task 8: 라우팅 계층 — RoutingHarness, HttpEndPoint 16개 라우트, 부트스트랩

운영과 같은 구성(Jetty WebAppContext + ScalatraListener + ScalatraBootstrap + `Master.system`)으로 띄우되, 부트스트랩 이름만 패키지까지 적는다. 운영 값 `"ScalatraBootstrap"` 은 기동이 실패한다 (KNOWN-ISSUE 7, `BootstrapLifecycleSpec` 이 고정).

**Files:**
- Create: `src/test/scala/com/sec/eeg/ars/testkit/RoutingHarness.scala`
- Test: `src/test/scala/com/sec/eeg/ars/endpoint/HttpEndPointRoutingSpec.scala`, `src/test/scala/com/sec/eeg/ars/endpoint/BootstrapLifecycleSpec.scala`

**Interfaces:**
- Consumes: `StubMaster`, `Responder`, `Responder.NoReply`, `ActorKit.awaitActor`, `GoldenFile.Root`, `JdkGuard`
- Produces: `final class RoutingHarness` (`start()`, `stop()`, `reset()`, `get(path, headers*)`, `post(path, body, contentType, headers*)`, `options(path, headers*)`, `reply: AtomicReference[Any]`, `masterProbe`/`httpProbe`/`emailProbe: TestProbe`), `RoutingHarness.Response(status, body, headers)` + `text`, `header(name)`, `RoutingHarness.newServer(lifeCycleClass: String): Server`, `RoutingHarness.DefaultReply`, `RoutingHarness.BootstrapClassName`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/scala/com/sec/eeg/ars/endpoint/HttpEndPointRoutingSpec.scala`:

```scala
package com.sec.eeg.ars.endpoint

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

import com.sec.eeg.ars.actor._
import com.sec.eeg.ars.testkit.{GoldenFile, JdkGuard, Responder, RoutingHarness}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FunSuite}

import scala.concurrent.duration._

class HttpEndPointRoutingSpec extends FunSuite with BeforeAndAfterAll with BeforeAndAfterEach {
  private val h = new RoutingHarness
  private val json = Some("application/json")
  private val textPlain = "text/plain; charset=UTF-8"
  private val body = """{"k":"v"}"""

  override def beforeAll(): Unit = {
    JdkGuard.require8()
    h.start()
  }

  override def afterAll(): Unit = h.stop()

  override def beforeEach(): Unit = h.reset()

  private case class Route(method: String, path: String, worker: String, expected: Any)

  // HttpEndPoint 의 라우트 15개 (+ /EARS/kill 은 아래 별도 테스트) — 요청이 어느 액터로 어떤 메시지가 되는지
  private val routes = Seq(
    Route("POST", "/ARS/AppendHistory", "http", AddHistory(body)),
    Route("POST", "/ARS/LoadEmailTemplate", "email", LoadEmailTemplate()),
    Route("POST", "/ARS/LoadPopupTemplate", "email", LoadPopupTemplate()),
    Route("GET", "/ARS/Popup/PHOTO/MODEL-A/RM_CPU", "email", PopupContent("PHOTO", "MODEL-A", "RM_CPU")),
    Route("GET", "/ARS/v2/Popup/PHOTO/MODEL-A/RM_CPU", "email", PopupContentV2("PHOTO", "MODEL-A", "RM_CPU")),
    Route("GET", "/ARS/EmailImage/ARS_PHOTO_MODEL-A_RM_CPU__/logo.png", "email", EmailImage("ARS_PHOTO_MODEL-A_RM_CPU__", "logo.png")),
    Route("GET", "/ARS/SnapShotImage/EQP001/1700000000000", "email", SnapShotImage("EQP001", "1700000000000")),
    Route("GET", "/ARS/SnapshotImage/EQP001/1700000000000", "email", SnapShotImage("EQP001", "1700000000000")),
    Route("GET", "/ARS/History/EQP001/7", "http", QueryHistory("EQP001", 7L)),
    Route("POST", "/EmailNotify", "email", SendEmail(body)),
    Route("POST", "/RTM/EmailNotify", "email", SendEmailForRTM(body)),
    Route("POST", "/RecoveryEmailNotify", "email", SendRecoveryEmail(body)),
    Route("POST", "/ARS/ScriptResult", "email", ScriptResult(body)),
    Route("POST", "/ARS/SaveCustomfiles", "http", SaveCustomsFile(body)),
    Route("GET", "/ARS/Customfiles/EQP001/2026/07/report.txt", "email", CustomFiles("EQP001", 2026, 7, "report.txt")))

  routes.foreach { r =>
    test(s"${r.method} ${r.path} → ${r.expected}") {
      val resp = if (r.method == "GET") h.get(r.path) else h.post(r.path, body.getBytes(UTF_8), json)
      assert(resp.status == 200)
      assert(resp.text == RoutingHarness.DefaultReply)
      assert(resp.header("Content-Type").contains(textPlain))
      (if (r.worker == "http") h.httpProbe else h.emailProbe).expectMsg(3.seconds, r.expected)
    }
  }

  test("POST /EARS/kill → Master 에 ShutDown, 200 빈 본문") {
    val resp = h.post("/EARS/kill", Array.empty[Byte], None)
    assert(resp.status == 200)
    assert(resp.text == "")
    h.masterProbe.expectMsg(3.seconds, Master.ShutDown())
  }

  test("경로 파라미터의 한글·공백·+ 를 디코딩한다") {
    assert(h.get("/ARS/Popup/%ED%95%9C%EA%B8%80/M%20A/C%2BD").status == 200)
    h.emailProbe.expectMsg(3.seconds, PopupContent("한글", "M A", "C+D"))
  }

  test("요청 본문은 charset 이 없어도 UTF-8 로 읽는다") {
    val raw = """{"t":"한글"}""".getBytes(UTF_8)
    h.post("/ARS/AppendHistory", raw, json)
    h.httpProbe.expectMsg(3.seconds, AddHistory("""{"t":"한글"}"""))
    h.post("/ARS/AppendHistory", raw, None)
    h.httpProbe.expectMsg(3.seconds, AddHistory("""{"t":"한글"}"""))
  }

  test("바이트 응답은 이미지여도 application/octet-stream 으로 나간다") {
    val png = Files.readAllBytes(new File(GoldenFile.Root, "_files/favicon.png").toPath)
    h.reply.set(png)
    val resp = h.get("/ARS/EmailImage/ARS_PHOTO_MODEL-A_RM_CPU__/logo.png")
    assert(resp.status == 200)
    assert(resp.header("Content-Type").contains("application/octet-stream;charset=UTF-8"))
    assert(java.util.Arrays.equals(resp.body, png))
    h.reply.set(Array.empty[Byte])
    val empty = h.get("/ARS/EmailImage/ARS_PHOTO_MODEL-A_RM_CPU__/none.png")
    assert(empty.status == 200 && empty.body.isEmpty)
    assert(empty.header("Content-Type").contains("application/octet-stream;charset=UTF-8"))
  }

  test("Twirl HTML 응답은 text/html, InternalServerError 는 500") {
    h.reply.set(html.history.render("<b>x</b>"))
    val page = h.get("/ARS/History/EQP001/7")
    assert(page.status == 200)
    assert(page.header("Content-Type").contains("text/html; charset=UTF-8"))
    assert(page.text == html.history.render("<b>x</b>").body)
    h.reply.set(org.scalatra.InternalServerError("There is no data"))
    val none = h.get("/ARS/History/EQP001/8")
    assert(none.status == 500)
    assert(none.text == "There is no data")
    assert(none.header("Content-Type").contains(textPlain))
  }

  test("KNOWN-ISSUE 8: 숫자 파라미터 오류는 500 과 스택 트레이스를 본문에 그대로 보낸다") {
    val history = h.get("/ARS/History/EQP001/abc")
    assert(history.status == 500)
    assert(history.text.startsWith("java.lang.NumberFormatException: For input string: \"abc\""))
    assert(history.text.contains("java.lang.Long.parseLong"))
    val files = h.get("/ARS/Customfiles/EQP001/2026/x/report.txt")
    assert(files.status == 500)
    assert(files.text.startsWith("java.lang.NumberFormatException: For input string: \"x\""))
    h.httpProbe.expectNoMsg(300.millis)
    h.emailProbe.expectNoMsg(300.millis)
  }

  test("KNOWN-ISSUE 8: 없는 경로는 404 와 함께 전체 라우트 목록을 보낸다, 대소문자도 구분한다") {
    val nope = h.get("/nope")
    assert(nope.status == 404)
    assert(nope.text.contains("Requesting \"GET /nope\" on servlet \"\" but only have:"))
    assert(nope.text.contains("GET /ARS/Customfiles/:eqpid/:year/:month/:fname"))
    assert(h.get("/ars/popup/PHOTO/MODEL-A/RM_CPU").status == 404)
  }

  test("POST 전용 경로를 GET 으로 부르면 405") {
    val resp = h.get("/EmailNotify")
    assert(resp.status == 405)
    assert(resp.header("Allow").contains("POST"))
  }

  test("CORS: Origin 을 그대로 돌려주고 자격 증명을 허용한다, Origin 이 없으면 헤더도 없다") {
    val withOrigin = h.get("/ARS/Popup/PHOTO/MODEL-A/RM_CPU", "Origin" -> "http://a.example")
    assert(withOrigin.header("Access-Control-Allow-Origin").contains("http://a.example"))
    assert(withOrigin.header("Access-Control-Allow-Credentials").contains("true"))
    val noOrigin = h.get("/ARS/Popup/PHOTO/MODEL-A/RM_CPU")
    assert(noOrigin.header("Access-Control-Allow-Origin").isEmpty)
  }

  test("CORS 사전 요청(OPTIONS)은 405 지만 CORS 헤더는 붙는다") {
    val resp = h.options("/EmailNotify", "Origin" -> "http://a.example",
      "Access-Control-Request-Method" -> "POST", "Access-Control-Request-Headers" -> "Content-Type")
    assert(resp.status == 405)
    assert(resp.header("Allow").contains("POST"))
    assert(resp.header("Access-Control-Allow-Origin").contains("http://a.example"))
    assert(resp.header("Access-Control-Allow-Headers").contains("Content-Type"))
  }

  test("KNOWN-ISSUE 8: 액터가 10초 안에 응답하지 않으면 500 과 예외 내용을 보낸다") {
    h.reply.set(Responder.NoReply)
    val resp = h.get("/ARS/Popup/PHOTO/MODEL-A/RM_CPU")
    assert(resp.status == 500)
    assert(resp.text.startsWith("akka.pattern.AskTimeoutException: Ask timed out on"))
  }
}
```

`src/test/scala/com/sec/eeg/ars/endpoint/BootstrapLifecycleSpec.scala`:

```scala
package com.sec.eeg.ars.endpoint

import com.sec.eeg.ars.testkit.RoutingHarness
import org.scalatest.FunSuite

class BootstrapLifecycleSpec extends FunSuite {
  test("KNOWN-ISSUE 7: 운영 설정의 부트스트랩 이름 \"ScalatraBootstrap\" 으로는 Jetty 가 기동하지 않는다") {
    // Master.WebServiceStart 가 쓰는 값과 같다
    val server = RoutingHarness.newServer("ScalatraBootstrap")
    try {
      val e = intercept[AssertionError](server.start())
      assert(e.getMessage.contains("No lifecycle class found!"))
    } finally server.stop()
  }

  test("부트스트랩 클래스는 패키지 com.sec.eeg.ars 안에 있다") {
    assert(classOf[com.sec.eeg.ars.ScalatraBootstrap].getName == "com.sec.eeg.ars.ScalatraBootstrap")
    intercept[ClassNotFoundException](Class.forName("ScalatraBootstrap"))
  }
}
```

- [ ] **Step 2: 실패를 확인한다**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && mvn -B -o test 2>&1 | grep -E 'error:|BUILD'
```
기대: `not found: type RoutingHarness` 컴파일 오류

- [ ] **Step 3: 구현한다**

`src/test/scala/com/sec/eeg/ars/testkit/RoutingHarness.scala`:

```scala
package com.sec.eeg.ars.testkit

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference

import akka.actor.ActorSystem
import akka.testkit.TestProbe
import com.sec.eeg.ars.actor.Master
import org.apache.http.client.methods.{HttpGet, HttpOptions, HttpPost, HttpRequestBase}
import org.apache.http.entity.ByteArrayEntity
import org.apache.http.impl.client.{CloseableHttpClient, HttpClients}
import org.apache.http.util.EntityUtils
import org.eclipse.jetty.server.{Server, ServerConnector}
import org.eclipse.jetty.webapp.WebAppContext
import org.scalatra.servlet.ScalatraListener

import scala.concurrent.Await
import scala.concurrent.duration._

/**
 * HttpEndPoint 를 운영(Master.WebServiceStart)과 같은 방식으로 띄운다: Jetty WebAppContext + ScalatraListener + ScalatraBootstrap.
 * 다른 점: 포트는 임의, 부트스트랩 이름은 패키지까지 적는다 (운영 값 "ScalatraBootstrap" 은 KNOWN-ISSUE 7 로 기동 실패).
 * HttpWorker·EmailWorker 자리에는 받은 메시지를 기록하고 reply 로 응답하는 Responder 를 둔다.
 */
final class RoutingHarness {
  implicit val system: ActorSystem = ActorSystem("routing")
  val masterProbe = TestProbe()
  val httpProbe = TestProbe()
  val emailProbe = TestProbe()
  val reply = new AtomicReference[Any](RoutingHarness.DefaultReply)
  private val client: CloseableHttpClient = HttpClients.createDefault()
  private var server: Server = _
  private var port = 0

  def start(): Unit = {
    system.actorOf(StubMaster.props(masterProbe.ref, Map(
      "HttpWorker" -> Responder.props(httpProbe.ref, reply),
      "EmailWorker" -> Responder.props(emailProbe.ref, reply))), "Master")
    ActorKit.awaitActor(system, "/user/Master/HttpWorker")
    ActorKit.awaitActor(system, "/user/Master/EmailWorker")
    Master.system = system // ScalatraBootstrap 이 이 전역 값을 쓴다
    server = RoutingHarness.newServer(RoutingHarness.BootstrapClassName)
    server.start()
    port = server.getConnectors()(0).asInstanceOf[ServerConnector].getLocalPort
  }

  def stop(): Unit = {
    client.close()
    if (server != null) server.stop()
    Await.ready(system.terminate(), 10.seconds)
  }

  /** 테스트 사이에 남은 메시지를 비우고 응답을 기본값으로 되돌린다 */
  def reset(): Unit = {
    reply.set(RoutingHarness.DefaultReply)
    Seq(masterProbe, httpProbe, emailProbe).foreach(p => p.receiveWhile(max = 200.millis, idle = 50.millis) { case m => m })
  }

  def url(path: String): String = s"http://127.0.0.1:$port$path"

  def get(path: String, headers: (String, String)*): RoutingHarness.Response = execute(new HttpGet(url(path)), headers)

  def post(path: String, body: Array[Byte], contentType: Option[String], headers: (String, String)*): RoutingHarness.Response = {
    val req = new HttpPost(url(path))
    req.setEntity(new ByteArrayEntity(body))
    contentType.foreach(ct => req.setHeader("Content-Type", ct))
    execute(req, headers)
  }

  def options(path: String, headers: (String, String)*): RoutingHarness.Response = execute(new HttpOptions(url(path)), headers)

  private def execute(req: HttpRequestBase, headers: Seq[(String, String)]): RoutingHarness.Response = {
    headers.foreach { case (k, v) => req.setHeader(k, v) }
    val resp = client.execute(req)
    try {
      val body = Option(resp.getEntity).map(e => EntityUtils.toByteArray(e)).getOrElse(Array.empty[Byte])
      RoutingHarness.Response(resp.getStatusLine.getStatusCode, body,
        resp.getAllHeaders.map(h => h.getName.toLowerCase -> h.getValue).toMap)
    } finally resp.close()
  }
}

object RoutingHarness {
  val DefaultReply = """{"result":"Success","message":""}"""
  val BootstrapClassName = "com.sec.eeg.ars.ScalatraBootstrap"

  final case class Response(status: Int, body: Array[Byte], headers: Map[String, String]) {
    def text: String = new String(body, UTF_8)

    def header(name: String): Option[String] = headers.get(name.toLowerCase)
  }

  /** Master.WebServiceStart 와 같은 구성의 Jetty (포트 0 = 임의 포트) */
  def newServer(lifeCycleClass: String): Server = {
    val server = new Server(0)
    val context = new WebAppContext()
    context.setContextPath("/")
    context.setResourceBase(Files.createTempDirectory("hws-routing").toFile.getAbsolutePath)
    context.setInitParameter(ScalatraListener.LifeCycleKey, lifeCycleClass)
    context.addEventListener(new ScalatraListener)
    server.setHandler(context)
    server
  }
}
```

- [ ] **Step 4: 통과를 확인한다 (10초 무응답 케이스 때문에 약 15초 더 걸린다)**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && mvn -B -o test 2>&1 | grep -E 'Tests: |\*\*\* FAILED|ABORTED|error:|BUILD' && mvn -B -o test -Pgolden 2>&1 | grep -E 'Tests: |\*\*\* FAILED|ABORTED|error:|BUILD'
```
기대: 기본 `Tests: succeeded 84, failed 0`, `-Pgolden` `Tests: succeeded 93, failed 0`

- [ ] **Step 5: 커밋**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && git status --short   # 이 Task 의 파일만 보여야 한다
git add src/test/scala/com/sec/eeg/ars/testkit/RoutingHarness.scala \
        src/test/scala/com/sec/eeg/ars/endpoint/HttpEndPointRoutingSpec.scala \
        src/test/scala/com/sec/eeg/ars/endpoint/BootstrapLifecycleSpec.scala
git commit -q -F - <<'EOF'
라우팅 계층: HttpEndPoint 16개 라우트·응답 형식·오류 응답·CORS

KNOWN-ISSUE 7 (부트스트랩 이름 "ScalatraBootstrap" 로는 Jetty 기동 실패)과
KNOWN-ISSUE 8 (오류 응답의 스택 트레이스·예외 내용·라우트 목록 노출)을 고정한다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git log --oneline -1
```


## Task 9: 골든 — 메일 4종 (SendEmail·RTM·복구·스크립트, 38개)

RMS·ARSAgent·CommandServer·InterfaceServer·AgentWatchdog 가 부르는 메일 경로다. `SendEmail` 의 기본 페이로드는 RMS 계약 파일(`rms:legacy|rendered|grouped`)을 직접 읽는다.

**Files:**
- Create: `src/test/scala/com/sec/eeg/ars/golden/MailGoldenSpec.scala`, `src/test/scala/com/sec/eeg/ars/testkit/GoldenSuite.scala`, `src/test/resources/golden/_fixtures/email-base.mongo.json`, `recovery-wrapper.mongo.json`, `_fixtures/payloads/rtm.json`, `recovery.json`, `script.json`
- Create: 케이스 폴더와 `case.json` (아래 스크립트), 기록 후 각 케이스의 `expected.txt`

**Interfaces:**
- Consumes: `GoldenSuite` (Task 9), `GoldenCase`, `GoldenRunner`, `_files/favicon.png`

- [ ] **Step 1: 메시지 폴더마다 테스트를 등록하는 기반 스위트를 만든다**

`src/test/scala/com/sec/eeg/ars/testkit/GoldenSuite.scala`:

```scala
package com.sec.eeg.ars.testkit

import org.scalatest.FunSuite

/** golden/<message>/<케이스>/ 마다 Golden 태그 테스트를 하나씩 등록한다. case.json 은 테스트 안에서 읽는다. */
abstract class GoldenSuite(messages: String*) extends FunSuite {
  for (message <- messages; dir <- GoldenCase.dirs(message))
    test(s"$message/${dir.getName}", Golden) {
      val c = GoldenCase.load(dir)
      GoldenFile.check(c.expectedFile, GoldenRunner.run(c)).foreach(m => fail(m))
    }
}
```

- [ ] **Step 2: fixture 와 케이스를 만든다**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && bash <<'SCRIPT'
G=src/test/resources/golden
mkdir -p $G/_fixtures/payloads
cat > $G/_fixtures/email-base.mongo.json <<'EOF'
{
  "EMAIL_TEMPLATE_REPOSITORY": [
    {"process": "PHOTO", "model": "MODEL-A", "code": "RESOURCE_MONITOR", "subcode": "CPU_CRITICAL", "title": "자원 경보(정확)",
     "html": "<p>[exact] host=@Hostname proc=@Process model=@Model ip=@IP line=@Line code=@CODE sev=@Severity cur=@CurrentValue link=http://@HttpWebServerAddress/x snap=@__snapshot__</p>"},
    {"process": "PHOTO", "model": "MODEL-A", "code": "RESOURCE_MONITOR", "subcode": "_", "title": "자원 경보(subcode 대체)",
     "html": "<p>[subcode-fallback] host=@Hostname line=@Line code=@CODE sdwt=@Sdwt ip=@IP</p>"},
    {"process": "PHOTO", "model": "MODEL-A", "code": "_", "subcode": "_", "title": "공통 경보(code 대체)",
     "html": "<p>[code-fallback] host=@Hostname code=@CODE</p>"},
    {"process": "PHOTO", "model": "MODEL-A", "code": "SC_RESTART", "subcode": "_", "title": "재시작 스크립트",
     "html": "<p>[script] host=@Hostname ip=@IP line=@Line code=@CODE out=@Out</p>"}
  ],
  "EMAIL_RECIPIENTS": [
    {"app": "ARS", "code": "RESOURCE_MONITOR", "process": "PHOTO", "model": "MODEL-A", "line": "L1", "emailCategory": "EMAIL-TEST-EXACT"},
    {"app": "ARS", "code": "RESOURCE_MONITOR", "process": "PHOTO", "model": "MODEL-A", "line": "all", "emailCategory": "EMAIL-TEST-LINEALL"},
    {"app": "ARS", "code": "RESOURCE_MONITOR", "process": "PHOTO", "model": "all", "line": "all", "emailCategory": "EMAIL-TEST-MODELALL"},
    {"app": "ARS", "code": "RESOURCE_MONITOR", "process": "all", "model": "all", "line": "all", "emailCategory": "EMAIL-TEST-ALL"},
    {"app": "ARS", "code": "SC_RESTART", "process": "PHOTO", "model": "MODEL-A", "line": "L1", "emailCategory": "EMAIL-TEST-SCRIPT"}
  ],
  "EQP_LINE_MAP": [
    {"line": "L1", "lineDesc": "1라인"}
  ],
  "EQP_INFO": [
    {"eqpId": "EQP001", "category": "SDWT-A", "emailcategory": "EMAIL-TEST-EQPINFO"}
  ]
}
EOF
cat > $G/_fixtures/recovery-wrapper.mongo.json <<'EOF'
{
  "EMAIL_TEMPLATE_REPOSITORY": [
    {"process": "all", "model": "all", "code": "RecoveryDefault", "subcode": "_", "title": "복구 래퍼",
     "html": "<div class=\"wrap\">@contents</div><p>proc=@Process model=@Model eqp=@Eqpid line=@Line sdwt=@Sdwt snap=@__snapshot__</p>"}
  ]
}
EOF
cat > $G/_fixtures/payloads/rtm.json <<'EOF'
{"process": "PHOTO", "model": "MODEL-A", "line": "L1", "eqpid": "EQP001", "code": "RESOURCE_MONITOR", "variables": {"Severity": "MAJOR", "CurrentValue": "88.0"}}
EOF
cat > $G/_fixtures/payloads/recovery.json <<'EOF'
{"hostname": "EQP001", "process": "PHOTO", "line": "L1", "model": "MODEL-A", "scname": "SC_RESTART", "title": "[EARS][복구] EQP001 SC_RESTART", "body": "<b>복구 완료</b> 결과=@Result", "variables": {"Result": "OK"}}
EOF
cat > $G/_fixtures/payloads/script.json <<'EOF'
{"success": true, "hostname": "EQP001", "ip": "10.0.0.99", "process": "PHOTO", "line": "L1", "model": "MODEL-A", "scname": "SC_RESTART", "output": "restarted", "variables": {"Out": "재시작 완료"}}
EOF
mk() { mkdir -p "$G/$1"; printf '%s\n' "$2" > "$G/$1/case.json"; }
# ---- SendEmail (19) ----
mk SendEmail/legacy-exact '{"message":"SendEmail","base":"rms:legacy","fixtures":["email-base"]}'
mk SendEmail/legacy-subcode-fallback '{"message":"SendEmail","base":"rms:legacy","set":{"subcode":"MEM_WARN"},"fixtures":["email-base"]}'
mk SendEmail/legacy-subcode-empty '{"message":"SendEmail","base":"rms:legacy","set":{"subcode":""},"fixtures":["email-base"]}'
mk SendEmail/legacy-code-fallback '{"message":"SendEmail","base":"rms:legacy","set":{"code":"DISK_MONITOR","subcode":""},"fixtures":["email-base"]}'
mk SendEmail/legacy-no-template '{"message":"SendEmail","base":"rms:legacy","set":{"process":"ETCH"},"fixtures":["email-base"]}'
mk SendEmail/legacy-line-unmapped '{"message":"SendEmail","base":"rms:legacy","set":{"line":"L9"},"fixtures":["email-base"]}'
mk SendEmail/legacy-no-category '{"message":"SendEmail","base":"rms:legacy","set":{"hostname":"EQP999","code":"DISK_MONITOR","subcode":""},"fixtures":["email-base"]}'
mk SendEmail/legacy-variable-dollar '{"message":"SendEmail","base":"rms:legacy","set":{"variables":{"Severity":"$100 \\1 원"}},"fixtures":["email-base"]}'
mk SendEmail/legacy-hostname-dollar '{"message":"SendEmail","base":"rms:legacy","set":{"hostname":"EQP$1"},"fixtures":["email-base"],"knownIssue":"KNOWN-ISSUE 6: hostname 의 $ 를 정규식 그룹 참조로 해석해 치환 중 예외가 난다"}'
mk SendEmail/legacy-snapshot '{"message":"SendEmail","base":"rms:legacy","set":{"variables":{"Severity":"CRITICAL","__snapshot__":{"$file":"_files/favicon.png"}}},"fixtures":["email-base"]}'
mk SendEmail/legacy-app-nonars '{"message":"SendEmail","base":"rms:legacy","set":{"app":"RMS"},"fixtures":["email-base"],"knownIssue":"KNOWN-ISSUE 4: 템플릿 조회 키에 app 이 없어 다른 앱도 같은 템플릿을 쓴다"}'
mk SendEmail/rendered-contract '{"message":"SendEmail","base":"rms:rendered","fixtures":["email-base"]}'
mk SendEmail/rendered-address-token '{"message":"SendEmail","base":"rms:rendered","set":{"renderedBody":"<p>link=http://@HttpWebServerAddress/d raw=&#64;HttpWebServerAddress host=@Hostname</p>"},"fixtures":["email-base"]}'
mk SendEmail/rendered-no-template-needed '{"message":"SendEmail","base":"rms:rendered","set":{"process":"ETCH"},"fixtures":["email-base"]}'
mk SendEmail/rendered-grouped '{"message":"SendEmail","base":"rms:grouped","fixtures":["email-base"]}'
mk SendEmail/rendered-model-fallback '{"message":"SendEmail","base":"rms:rendered","set":{"model":"MODEL-B","line":"L9"},"fixtures":["email-base"]}'
mk SendEmail/rendered-subcode-empty '{"message":"SendEmail","base":"rms:rendered","set":{"subcode":""},"fixtures":["email-base"]}'
mk SendEmail/invalid-json '{"message":"SendEmail","bodyRaw":"{not json","fixtures":["email-base"]}'
mk SendEmail/missing-variables '{"message":"SendEmail","base":"rms:legacy","unset":["variables"],"fixtures":["email-base"]}'
# ---- SendEmailForRTM (5) ----
mk SendEmailForRTM/rtm-basic '{"message":"SendEmailForRTM","base":"rtm","fixtures":["email-base"]}'
mk SendEmailForRTM/rtm-code-fallback '{"message":"SendEmailForRTM","base":"rtm","set":{"code":"DISK_MONITOR"},"fixtures":["email-base"]}'
mk SendEmailForRTM/rtm-no-template '{"message":"SendEmailForRTM","base":"rtm","set":{"process":"ETCH"},"fixtures":["email-base"]}'
mk SendEmailForRTM/rtm-no-category '{"message":"SendEmailForRTM","base":"rtm","set":{"eqpid":"EQP999","code":"DISK_MONITOR"},"fixtures":["email-base"]}'
mk SendEmailForRTM/rtm-missing-eqpid '{"message":"SendEmailForRTM","base":"rtm","unset":["eqpid"],"fixtures":["email-base"]}'
# ---- SendRecoveryEmail (6) ----
mk SendRecoveryEmail/recovery-with-wrapper '{"message":"SendRecoveryEmail","base":"recovery","fixtures":["email-base","recovery-wrapper"]}'
mk SendRecoveryEmail/recovery-without-wrapper '{"message":"SendRecoveryEmail","base":"recovery","fixtures":["email-base"]}'
mk SendRecoveryEmail/recovery-snapshot '{"message":"SendRecoveryEmail","base":"recovery","set":{"variables":{"Result":"OK","__snapshot__":{"$file":"_files/favicon.png"}}},"fixtures":["email-base","recovery-wrapper"]}'
mk SendRecoveryEmail/recovery-no-category '{"message":"SendRecoveryEmail","base":"recovery","set":{"hostname":"EQP999","scname":"SC_UNKNOWN"},"fixtures":["email-base","recovery-wrapper"]}'
mk SendRecoveryEmail/recovery-contents-dollar '{"message":"SendRecoveryEmail","base":"recovery","set":{"body":"<b>비용 $100</b> \\1"},"fixtures":["email-base","recovery-wrapper"]}'
mk SendRecoveryEmail/recovery-wrapper-read-once '{"message":"SendRecoveryEmail","base":"recovery","fixtures":["email-base"],"mongoAfterStart":{"EMAIL_TEMPLATE_REPOSITORY":[{"process":"all","model":"all","code":"RecoveryDefault","subcode":"_","title":"복구 래퍼","html":"<div class=\"wrap\">@contents</div>"}]},"knownIssue":"KNOWN-ISSUE 5: RecoveryDefault 를 액터 시작 때 한 번만 읽어, 시작 뒤에 넣은 래퍼가 쓰이지 않는다"}'
# ---- ScriptResult (8) ----
P='{"process":"PHOTO","eqpModel":"MODEL-A","scname":"SC_RESTART","property":'
mk ScriptResult/script-success-sent "{\"message\":\"ScriptResult\",\"base\":\"script\",\"fixtures\":[\"email-base\"],\"mongo\":{\"SC_PROPERTY\":[${P}{\"DoNotSendEmailWhenSuccess\":false,\"DoNotSendEmailWhenFail\":false}}]}}"
mk ScriptResult/script-fail-sent "{\"message\":\"ScriptResult\",\"base\":\"script\",\"set\":{\"success\":false},\"fixtures\":[\"email-base\"],\"mongo\":{\"SC_PROPERTY\":[${P}{\"DoNotSendEmailWhenSuccess\":false,\"DoNotSendEmailWhenFail\":false}}]}}"
mk ScriptResult/script-success-disabled "{\"message\":\"ScriptResult\",\"base\":\"script\",\"fixtures\":[\"email-base\"],\"mongo\":{\"SC_PROPERTY\":[${P}{\"DoNotSendEmailWhenSuccess\":true,\"DoNotSendEmailWhenFail\":false}}]}}"
mk ScriptResult/script-fail-disabled "{\"message\":\"ScriptResult\",\"base\":\"script\",\"set\":{\"success\":false},\"fixtures\":[\"email-base\"],\"mongo\":{\"SC_PROPERTY\":[${P}{\"DoNotSendEmailWhenSuccess\":false,\"DoNotSendEmailWhenFail\":true}}]}}"
mk ScriptResult/script-no-property-doc '{"message":"ScriptResult","base":"script","fixtures":["email-base"]}'
mk ScriptResult/script-property-missing-key "{\"message\":\"ScriptResult\",\"base\":\"script\",\"fixtures\":[\"email-base\"],\"mongo\":{\"SC_PROPERTY\":[${P}{}}]}}"
mk ScriptResult/script-no-template '{"message":"ScriptResult","base":"script","set":{"scname":"SC_UNKNOWN","process":"ETCH"},"fixtures":["email-base"]}'
mk ScriptResult/script-no-category '{"message":"ScriptResult","base":"script","set":{"scname":"SC_NOCAT","hostname":"EQP999"},"fixtures":["email-base"]}'
SCRIPT
```

확인: `find src/test/resources/golden -name case.json | wc -l` → 누적 `38`

- [ ] **Step 3: 스위트를 만든다**

`src/test/scala/com/sec/eeg/ars/golden/MailGoldenSpec.scala`:

```scala
package com.sec.eeg.ars.golden

import com.sec.eeg.ars.testkit.GoldenSuite

/** 메일 4종 (RMS·ARSAgent·CommandServer·InterfaceServer·AgentWatchdog 가 부르는 경로) */
class MailGoldenSpec extends GoldenSuite("SendEmail", "SendEmailForRTM", "SendRecoveryEmail", "ScriptResult")
```

- [ ] **Step 4: 골든 파일이 없어 실패하는지 확인한다 (RED)**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && mvn -B -o test -Pgolden -Dsuites=com.sec.eeg.ars.golden.MailGoldenSpec 2>&1 | grep -E 'Tests: ' ; find src/test/resources/golden -name actual.txt | wc -l
```
기대: `Tests: succeeded 0, failed 38` 그리고 actual.txt `38`개 (모두 `골든 파일이 없다`)

- [ ] **Step 5: 기록한다**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && mvn -B -o test -Pgolden -Dgolden.update=true -Dsuites=com.sec.eeg.ars.golden.MailGoldenSpec 2>&1 | grep -E 'Tests: '
```
기대: `Tests: succeeded 38, failed 0`, actual.txt 는 모두 지워진다

- [ ] **Step 6: 기록을 검토한다 — 아래 표와 다르면 받아들이지 말고 원인을 찾는다**

각 `expected.txt` 를 열어 표의 값과 맞는지 본다. 표는 2026-10-06 검증 실행의 기록이다. 다르면 (1) case.json 오타 (2) fixture 차이 (3) 실제로 코드가 다르게 동작 순으로 확인하고, (3) 이면 계획 작성자에게 알린다.

| 케이스 | 응답 | Redis (카테고리 · 제목) | DB·파일 변경 | 확인할 점 |
|---|---|---|---|---|
| `SendEmail/invalid-json` | Fail: Unexpected character ('n' (code 110)): was expecting double… | 없음 | 없음 | Jackson 파싱 오류 메시지로 Fail |
| `SendEmail/legacy-app-nonars` | Success | `EMAIL-TEST-EXACT` · `[RMS][자원 경보(정확)][EQP001][RESOURCE_MONITOR-CPU_CRITICAL]` | 없음 | KNOWN-ISSUE 4: project=RMS 인데 ARS 템플릿을 씀 |
| `SendEmail/legacy-code-fallback` | Success | `EMAIL-TEST-EQPINFO` · `[EARS][공통 경보(code 대체)][EQP001][DISK_MONITOR]` | 없음 | code `_` 템플릿. 수신자가 없어 EQP_INFO 의 `EMAIL-TEST-EQPINFO` |
| `SendEmail/legacy-exact` | Success | `EMAIL-TEST-EXACT` · `[EARS][자원 경보(정확)][EQP001][RESOURCE_MONITOR-CPU_CRITICAL]` | 없음 | 모든 토큰 치환: `line=1라인`, `code=RESOURCE_MONITOR-CPU_CRITICAL`, `cur=96.5`, `link=http://hws.golden:8080/x`. 스냅샷이 없어 `snap=` 은 빈칸 |
| `SendEmail/legacy-hostname-dollar` | Fail: No group 1 | 없음 | 없음 | KNOWN-ISSUE 6: `No group 1` 로 Fail |
| `SendEmail/legacy-line-unmapped` | Success | `EMAIL-TEST-LINEALL` · `[EARS][자원 경보(정확)][EQP001][RESOURCE_MONITOR-CPU_CRITICAL]` | 없음 | `line=L9` 그대로, 수신자 2단계 `EMAIL-TEST-LINEALL` |
| `SendEmail/legacy-no-category` | Fail: There is no email category | 없음 | 없음 | 수신자도 EQP_INFO 도 없어 Fail |
| `SendEmail/legacy-no-template` | Fail: There is no email template | 없음 | 없음 | 템플릿이 없어 Fail |
| `SendEmail/legacy-snapshot` | Success | `EMAIL-TEST-EXACT` · `[EARS][자원 경보(정확)][EQP001][RESOURCE_MONITOR-CPU_CRITICAL]` | emailsnapshot +1 | 본문 링크와 emailsnapshot 행이 같은 `<T1>`. 변수를 덮어써서 `cur=@CurrentValue` 가 남음 |
| `SendEmail/legacy-subcode-empty` | Success | `EMAIL-TEST-EXACT` · `[EARS][자원 경보(subcode 대체)][EQP001][RESOURCE_MONITOR]` | 없음 | subcode 가 비어 `code=RESOURCE_MONITOR` (접미사 없음) |
| `SendEmail/legacy-subcode-fallback` | Success | `EMAIL-TEST-EXACT` · `[EARS][자원 경보(subcode 대체)][EQP001][RESOURCE_MONITOR-MEM_WARN]` | 없음 | subcode `_` 템플릿, `code=RESOURCE_MONITOR-MEM_WARN`, `sdwt=SDWT-A` |
| `SendEmail/legacy-variable-dollar` | Success | `EMAIL-TEST-EXACT` · `[EARS][자원 경보(정확)][EQP001][RESOURCE_MONITOR-CPU_CRITICAL]` | 없음 | 변수 값 `$100 \1 원` 이 글자 그대로 들어감 |
| `SendEmail/missing-variables` | Fail: No usable value for variables | 없음 | 없음 | `No usable value for variables` 로 Fail |
| `SendEmail/rendered-address-token` | Success | `EMAIL-TEST-EXACT` · `[EARS][[EARS] CPU CRITICAL - EQP001][EQP001][RESOURCE_MONITOR-CPU_CRI…` | 없음 | `@HttpWebServerAddress` 만 치환, `&#64;…` 와 `@Hostname` 은 그대로 |
| `SendEmail/rendered-contract` | Success | `EMAIL-TEST-EXACT` · `[EARS][[EARS] CPU CRITICAL - EQP001][EQP001][RESOURCE_MONITOR-CPU_CRI…` | 없음 | RMS 본문 그대로. 제목 대괄호 겹침 `[EARS][[EARS] …]` (특이 동작) |
| `SendEmail/rendered-grouped` | Success | `EMAIL-PHOTO-ALL-TEAM1` · `[EARS][[EARS] CPU CRITICAL - PHOTO][PHOTO][RESOURCE_MONITOR-CPU_CRITI…` | 없음 | 수신자 직접 지정 `EMAIL-PHOTO-ALL-TEAM1`, 헤드라인 `PHOTO` |
| `SendEmail/rendered-model-fallback` | Success | `EMAIL-TEST-MODELALL` · `[EARS][[EARS] CPU CRITICAL - EQP001][EQP001][RESOURCE_MONITOR-CPU_CRI…` | 없음 | 수신자 3단계 `EMAIL-TEST-MODELALL` |
| `SendEmail/rendered-no-template-needed` | Success | `EMAIL-TEST-ALL` · `[EARS][[EARS] CPU CRITICAL - EQP001][EQP001][RESOURCE_MONITOR-CPU_CRI…` | 없음 | process=ETCH 라 템플릿이 없어도 Success, 수신자 4단계 `EMAIL-TEST-ALL` |
| `SendEmail/rendered-subcode-empty` | Success | `EMAIL-TEST-EXACT` · `[EARS][[EARS] CPU CRITICAL - EQP001][EQP001][RESOURCE_MONITOR]` | 없음 | Redis 코드 칸이 `RESOURCE_MONITOR` |
| `SendEmailForRTM/rtm-basic` | Success | `EMAIL-TEST-EXACT` · `[EARS][자원 경보(subcode 대체)][EQP001][RESOURCE_MONITOR]` | 없음 | subcode `_` 템플릿, `ip=@IP` 가 치환되지 않고 남음 (특이 동작) |
| `SendEmailForRTM/rtm-code-fallback` | Success | `EMAIL-TEST-EQPINFO` · `[EARS][공통 경보(code 대체)][EQP001][DISK_MONITOR]` | 없음 | code `_` 템플릿, EQP_INFO 수신자 |
| `SendEmailForRTM/rtm-missing-eqpid` | Fail: No usable value for eqpid | 없음 | 없음 | `No usable value for eqpid` 로 Fail |
| `SendEmailForRTM/rtm-no-category` | Fail: There is no email category | 없음 | 없음 |  |
| `SendEmailForRTM/rtm-no-template` | Fail: There is no email template | 없음 | 없음 |  |
| `SendRecoveryEmail/recovery-contents-dollar` | Success | `EMAIL-TEST-SCRIPT` · `[EARS][복구] EQP001 SC_RESTART` | 없음 | 본문의 `$100 \1` 이 글자 그대로 |
| `SendRecoveryEmail/recovery-no-category` | Fail: There is no email category | 없음 | 없음 |  |
| `SendRecoveryEmail/recovery-snapshot` | Success | `EMAIL-TEST-SCRIPT` · `[EARS][복구] EQP001 SC_RESTART` | emailsnapshot +1 | 래퍼의 `snap=` 에 `<T1>` 링크, emailsnapshot 행 `<T1>` |
| `SendRecoveryEmail/recovery-with-wrapper` | Success | `EMAIL-TEST-SCRIPT` · `[EARS][복구] EQP001 SC_RESTART` | 없음 | 래퍼 적용, `snap=@__snapshot__` 이 남음 (특이 동작). 제목에 project 접두사 없음 |
| `SendRecoveryEmail/recovery-without-wrapper` | Success | `EMAIL-TEST-SCRIPT` · `[EARS][복구] EQP001 SC_RESTART` | 없음 | 래퍼 없이 본문만 (`결과=OK`) |
| `SendRecoveryEmail/recovery-wrapper-read-once` | Success | `EMAIL-TEST-SCRIPT` · `[EARS][복구] EQP001 SC_RESTART` | 없음 | KNOWN-ISSUE 5: 시작 뒤 넣은 래퍼가 쓰이지 않음. mongo 구역 변경 없음 |
| `ScriptResult/script-fail-disabled` | <0 bytes sha256:e3b0c44298fc> | 없음 | 없음 | 발송 끔 → 빈 바이트 응답, Redis 없음 |
| `ScriptResult/script-fail-sent` | Success | `EMAIL-TEST-SCRIPT` · `[[EARS][Script 실패]][재시작 스크립트][EQP001]SC_RESTART` | 없음 | 제목 `[[EARS][Script 실패]]…` |
| `ScriptResult/script-no-category` | Fail: There is no email category | 없음 | 없음 |  |
| `ScriptResult/script-no-property-doc` | Success | `EMAIL-TEST-SCRIPT` · `[[EARS][Script 성공]][재시작 스크립트][EQP001]SC_RESTART` | 없음 | SC_PROPERTY 문서가 없어도 예외를 삼키고 발송 |
| `ScriptResult/script-no-template` | Fail: There is no email template | 없음 | 없음 |  |
| `ScriptResult/script-property-missing-key` | Success | `EMAIL-TEST-SCRIPT` · `[[EARS][Script 성공]][재시작 스크립트][EQP001]SC_RESTART` | 없음 | 속성 키가 없어도(MatchError) 예외를 삼키고 발송 |
| `ScriptResult/script-success-disabled` | <0 bytes sha256:e3b0c44298fc> | 없음 | 없음 | 발송 끔 → 빈 바이트 응답, Redis 없음 |
| `ScriptResult/script-success-sent` | Success | `EMAIL-TEST-SCRIPT` · `[[EARS][Script 성공]][재시작 스크립트][EQP001]SC_RESTART` | 없음 | 제목 `[[EARS][Script 성공]][재시작 스크립트][EQP001]SC_RESTART:` (특이 동작) |

- [ ] **Step 7: 통과와 반복 실행 결과가 같은지 확인한다**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && for i in 1 2; do mvn -B -o test -Pgolden 2>&1 | grep -E 'Tests: '; done
find src/test/resources/golden -name 'expected.*' -exec shasum {} + | sort > /tmp/hws-golden-1.sha
mvn -B -o test -Pgolden -Dgolden.update=true >/dev/null 2>&1
find src/test/resources/golden -name 'expected.*' -exec shasum {} + | sort > /tmp/hws-golden-2.sha
diff /tmp/hws-golden-1.sha /tmp/hws-golden-2.sha && echo "갱신 모드 후 변경 없음"
```
기대: 두 번 모두 `Tests: succeeded 131, failed 0`, 그리고 `갱신 모드 후 변경 없음`

- [ ] **Step 8: 커밋**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && git status --short   # 이 Task 의 파일만 보여야 한다
git add src/test/resources/golden \
        src/test/scala/com/sec/eeg/ars/golden/MailGoldenSpec.scala \
        src/test/scala/com/sec/eeg/ars/testkit/GoldenSuite.scala
git commit -q -F - <<'EOF'
골든: 메일 4종 38개 (SendEmail·SendEmailForRTM·SendRecoveryEmail·ScriptResult)

KNOWN-ISSUE 4·5·6 과 특이 동작(제목 대괄호 겹침, @IP·@__snapshot__ 잔존)을 현재 동작으로 기록한다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git log --oneline -1
```


## Task 10: 골든 — 팝업·이미지 조회 (10개)

ARSAgent ErrorNotifier 가 부르는 팝업 조회와, 메일·팝업 본문의 `<img>` 가 부르는 이미지 조회다.

**Files:**
- Create: `src/test/scala/com/sec/eeg/ars/golden/LookupGoldenSpec.scala`, `src/test/resources/golden/_fixtures/lookup.mongo.json`
- Create: 케이스 폴더와 `case.json` (아래 스크립트), 기록 후 각 케이스의 `expected.txt`

**Interfaces:**
- Consumes: `GoldenSuite` (Task 9), `GoldenCase`, `GoldenRunner`, `_files/favicon.png`

- [ ] **Step 1: fixture 와 케이스를 만든다**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && bash <<'SCRIPT'
G=src/test/resources/golden
cat > $G/_fixtures/lookup.mongo.json <<'EOF'
{
  "POPUP_TEMPLATE_REPOSITORY": [
    {"process": "PHOTO", "model": "MODEL-A", "code": "RM_CPU", "html": "<div>팝업 RM_CPU</div>", "noSendEmail": true},
    {"process": "PHOTO", "model": "MODEL-A", "code": "RM_MEM", "html": "<div>팝업 RM_MEM</div>"},
    {"process": "PHOTO", "model": "MODEL-A", "code": "RM_DUP", "html": "<div>중복 1</div>"},
    {"process": "PHOTO", "model": "MODEL-A", "code": "RM_DUP", "html": "<div>중복 2</div>"}
  ],
  "EMAIL_IMAGE_REPOSITORY": [
    {"prefix": "ARS_PHOTO_MODEL-A_RM_CPU__", "name": "logo.png", "body": {"$file": "_files/favicon.png"}},
    {"prefix": "ARS_PHOTO_MODEL-A_RM_CPU__", "name": "dup.png", "body": {"$file": "_files/favicon.png"}},
    {"prefix": "ARS_PHOTO_MODEL-A_RM_CPU__", "name": "dup.png", "body": {"$file": "_files/favicon.png"}}
  ]
}
EOF
mk() { mkdir -p "$G/$1"; printf '%s\n' "$2" > "$G/$1/case.json"; }
pp() { printf '{"message":"%s","params":{"process":"PHOTO","model":"MODEL-A","code":"%s"},"fixtures":["lookup"]}' "$1" "$2"; }
mk PopupContent/found "$(pp PopupContent RM_CPU)"
mk PopupContent/not-found "$(pp PopupContent RM_X)"
mk PopupContent/duplicate "$(pp PopupContent RM_DUP)"
mk PopupContentV2/found-true "$(pp PopupContentV2 RM_CPU)"
mk PopupContentV2/found-missing-flag "$(pp PopupContentV2 RM_MEM)"
mk PopupContentV2/not-found "$(pp PopupContentV2 RM_X)"
mk PopupContentV2/duplicate "$(pp PopupContentV2 RM_DUP)"
mk EmailImage/found '{"message":"EmailImage","params":{"prefix":"ARS_PHOTO_MODEL-A_RM_CPU__","fname":"logo.png"},"fixtures":["lookup"]}'
mk EmailImage/not-found '{"message":"EmailImage","params":{"prefix":"ARS_PHOTO_MODEL-A_RM_CPU__","fname":"none.png"},"fixtures":["lookup"]}'
mk EmailImage/duplicate '{"message":"EmailImage","params":{"prefix":"ARS_PHOTO_MODEL-A_RM_CPU__","fname":"dup.png"},"fixtures":["lookup"]}'
SCRIPT
```

확인: `find src/test/resources/golden -name case.json | wc -l` → 누적 `48`

- [ ] **Step 2: 스위트를 만든다**

`src/test/scala/com/sec/eeg/ars/golden/LookupGoldenSpec.scala`:

```scala
package com.sec.eeg.ars.golden

import com.sec.eeg.ars.testkit.GoldenSuite

/** 팝업·이미지 조회 (ARSAgent ErrorNotifier, 메일·팝업 본문의 <img>) */
class LookupGoldenSpec extends GoldenSuite("PopupContent", "PopupContentV2", "EmailImage")
```

- [ ] **Step 3: 골든 파일이 없어 실패하는지 확인한다 (RED)**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && mvn -B -o test -Pgolden -Dsuites=com.sec.eeg.ars.golden.LookupGoldenSpec 2>&1 | grep -E 'Tests: ' ; find src/test/resources/golden -name actual.txt | wc -l
```
기대: `Tests: succeeded 0, failed 10` 그리고 actual.txt `10`개 (모두 `골든 파일이 없다`)

- [ ] **Step 4: 기록한다**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && mvn -B -o test -Pgolden -Dgolden.update=true -Dsuites=com.sec.eeg.ars.golden.LookupGoldenSpec 2>&1 | grep -E 'Tests: '
```
기대: `Tests: succeeded 10, failed 0`, actual.txt 는 모두 지워진다

- [ ] **Step 5: 기록을 검토한다 — 아래 표와 다르면 받아들이지 말고 원인을 찾는다**

각 `expected.txt` 를 열어 표의 값과 맞는지 본다. 표는 2026-10-06 검증 실행의 기록이다. 다르면 (1) case.json 오타 (2) fixture 차이 (3) 실제로 코드가 다르게 동작 순으로 확인하고, (3) 이면 계획 작성자에게 알린다.

| 케이스 | 응답 | Redis (카테고리 · 제목) | DB·파일 변경 | 확인할 점 |
|---|---|---|---|---|
| `PopupContent/duplicate` | <0 bytes sha256:e3b0c44298fc> | 없음 | 없음 |  |
| `PopupContent/found` | <div>팝업 RM_CPU</div> | 없음 | 없음 |  |
| `PopupContent/not-found` | <0 bytes sha256:e3b0c44298fc> | 없음 | 없음 |  |
| `PopupContentV2/duplicate` | <0 bytes sha256:e3b0c44298fc> | 없음 | 없음 |  |
| `PopupContentV2/found-missing-flag` | <div>팝업 RM_MEM</div>,false | 없음 | 없음 |  |
| `PopupContentV2/found-true` | <div>팝업 RM_CPU</div>,true | 없음 | 없음 |  |
| `PopupContentV2/not-found` | <0 bytes sha256:e3b0c44298fc> | 없음 | 없음 |  |
| `EmailImage/duplicate` | <0 bytes sha256:e3b0c44298fc> | 없음 | 없음 |  |
| `EmailImage/found` | <286 bytes sha256:99d69510d1da> | 없음 | 없음 | favicon 과 같은 해시 |
| `EmailImage/not-found` | <0 bytes sha256:e3b0c44298fc> | 없음 | 없음 |  |

- [ ] **Step 6: 통과와 반복 실행 결과가 같은지 확인한다**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && for i in 1 2; do mvn -B -o test -Pgolden 2>&1 | grep -E 'Tests: '; done
find src/test/resources/golden -name 'expected.*' -exec shasum {} + | sort > /tmp/hws-golden-1.sha
mvn -B -o test -Pgolden -Dgolden.update=true >/dev/null 2>&1
find src/test/resources/golden -name 'expected.*' -exec shasum {} + | sort > /tmp/hws-golden-2.sha
diff /tmp/hws-golden-1.sha /tmp/hws-golden-2.sha && echo "갱신 모드 후 변경 없음"
```
기대: 두 번 모두 `Tests: succeeded 141, failed 0`, 그리고 `갱신 모드 후 변경 없음`

- [ ] **Step 7: 커밋**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && git status --short   # 이 Task 의 파일만 보여야 한다
git add src/test/resources/golden \
        src/test/scala/com/sec/eeg/ars/golden/LookupGoldenSpec.scala
git commit -q -F - <<'EOF'
골든: 팝업·이미지 조회 10개 (PopupContent·PopupContentV2·EmailImage)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git log --oneline -1
```


## Task 11: 골든 — 템플릿 가져오기 (13개)

서버 폴더의 메일·팝업 템플릿을 MongoDB 로 가져오고 폴더를 `_workdone` 으로 옮기는 경로다. 입력 폴더는 케이스 폴더의 `files/email/…`, `files/popup/…` 에 있고, 실행기가 임시 폴더로 복사해 쓴다. 폴더 이름의 `^` 때문에 스크립트는 `bash` 로 돌린다.

**Files:**
- Create: `src/test/scala/com/sec/eeg/ars/golden/TemplateImportGoldenSpec.scala`
- Create: 케이스 폴더와 `case.json` (아래 스크립트), 기록 후 각 케이스의 `expected.txt`

**Interfaces:**
- Consumes: `GoldenSuite` (Task 9), `GoldenCase`, `GoldenRunner`, `_files/favicon.png`

- [ ] **Step 1: fixture 와 케이스를 만든다**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && bash <<'SCRIPT'
G=src/test/resources/golden
mk() { mkdir -p "$G/$1"; printf '%s\n' "$2" > "$G/$1/case.json"; }
# ---- LoadEmailTemplate (8): 입력 폴더는 files/email/ 아래 ----
LE=LoadEmailTemplate
mk $LE/import-basic '{"message":"LoadEmailTemplate","mongo":{"EMAIL_IMAGE_REPOSITORY":[{"prefix":"ARS_PHOTO_MODEL-A_RM_CPU__","name":"old.png","body":{"$file":"_files/favicon.png"}}]}}'
D="$G/$LE/import-basic/files/email/ARS^PHOTO^MODEL-A^RM_CPU"; mkdir -p "$D"; printf '%s' '<p><img src="@logo.png"> host=@Hostname</p>' > "$D/CPU-Alert.html"; cp $G/_files/favicon.png "$D/logo.png"
mk $LE/import-subcode '{"message":"LoadEmailTemplate"}'
D="$G/$LE/import-subcode/files/email/ARS^PHOTO^MODEL-A^RM_CPU^CRITICAL"; mkdir -p "$D"; printf '%s' '<p>critical @Hostname</p>' > "$D/Alert.html"
mk $LE/import-indext-prefix '{"message":"LoadEmailTemplate"}'
D="$G/$LE/import-indext-prefix/files/email/ARS^PHOTO^MODEL-A^RM_MEM"; mkdir -p "$D"; printf '%s' '<p>memory</p>' > "$D/indext-Memory.html"
mk $LE/import-skip-invalid '{"message":"LoadEmailTemplate"}'
B="$G/$LE/import-skip-invalid/files/email"; mkdir -p "$B/BAD^ONLY^THREE" "$B/^PHOTO^MODEL-A^RM_X" "$B/_workdone"; printf '%s' '<p>a</p>' > "$B/BAD^ONLY^THREE/a.html"; printf '%s' '<p>b</p>' > "$B/^PHOTO^MODEL-A^RM_X/b.html"; printf '%s' 'keep' > "$B/_workdone/keep.txt"
mk $LE/import-no-html '{"message":"LoadEmailTemplate"}'
D="$G/$LE/import-no-html/files/email/ARS^PHOTO^MODEL-A^RM_NOHTML"; mkdir -p "$D"; printf '%s' 'no html here' > "$D/readme.txt"
mk $LE/import-replace-workdone '{"message":"LoadEmailTemplate"}'
B="$G/$LE/import-replace-workdone/files/email"; mkdir -p "$B/_workdone/ARS^PHOTO^MODEL-A^RM_CPU" "$B/ARS^PHOTO^MODEL-A^RM_CPU"; printf '%s' '<p>old</p>' > "$B/_workdone/ARS^PHOTO^MODEL-A^RM_CPU/Old.html"; printf '%s' '<p>new</p>' > "$B/ARS^PHOTO^MODEL-A^RM_CPU/New.html"
mk $LE/import-missing-location '{"message":"LoadEmailTemplate","config":{"EmailTemplateImportLocation":"missing-dir"}}'
mk $LE/import-app-overwrite '{"message":"LoadEmailTemplate","mongo":{"EMAIL_TEMPLATE_REPOSITORY":[{"process":"PHOTO","model":"MODEL-A","code":"RM_CPU","subcode":"_","title":"ARS 기존","html":"<p>ARS</p>"}]},"knownIssue":"KNOWN-ISSUE 4: 갱신 키(process, model, code, subcode)에 app 이 없어 다른 앱 템플릿을 덮어쓴다"}'
D="$G/$LE/import-app-overwrite/files/email/RMS^PHOTO^MODEL-A^RM_CPU"; mkdir -p "$D"; printf '%s' '<p>RMS</p>' > "$D/RMS-New.html"
# ---- LoadPopupTemplate (5): 입력 폴더는 files/popup/ 아래 ----
LP=LoadPopupTemplate
mk $LP/popup-import-basic '{"message":"LoadPopupTemplate"}'
D="$G/$LP/popup-import-basic/files/popup/PHOTO^MODEL-A^RM_CPU"; mkdir -p "$D"; printf '%s' '<div><img src="@pic.png"> 팝업</div>' > "$D/index.html"; cp $G/_files/favicon.png "$D/pic.png"
mk $LP/popup-import-bom '{"message":"LoadPopupTemplate"}'
D="$G/$LP/popup-import-bom/files/popup/PHOTO^MODEL-A^RM_BOM"; mkdir -p "$D"; printf '\xef\xbb\xbf%s' '<div>BOM 제거</div>' > "$D/index.html"
mk $LP/popup-import-no-index '{"message":"LoadPopupTemplate"}'
D="$G/$LP/popup-import-no-index/files/popup/PHOTO^MODEL-A^RM_MAIN"; mkdir -p "$D"; printf '%s' '<div>main</div>' > "$D/main.html"
mk $LP/popup-import-invalid-name '{"message":"LoadPopupTemplate"}'
D="$G/$LP/popup-import-invalid-name/files/popup/PHOTO^MODEL-A"; mkdir -p "$D"; printf '%s' '<div>x</div>' > "$D/index.html"
mk $LP/popup-import-missing-location '{"message":"LoadPopupTemplate","config":{"PopupTemplateImportLocation":"missing-dir"}}'
SCRIPT
```

확인: `find src/test/resources/golden -name case.json | wc -l` → 누적 `61`

- [ ] **Step 2: 스위트를 만든다**

`src/test/scala/com/sec/eeg/ars/golden/TemplateImportGoldenSpec.scala`:

```scala
package com.sec.eeg.ars.golden

import com.sec.eeg.ars.testkit.GoldenSuite

/** 서버 폴더의 메일·팝업 템플릿을 MongoDB 로 가져오기 */
class TemplateImportGoldenSpec extends GoldenSuite("LoadEmailTemplate", "LoadPopupTemplate")
```

- [ ] **Step 3: 골든 파일이 없어 실패하는지 확인한다 (RED)**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && mvn -B -o test -Pgolden -Dsuites=com.sec.eeg.ars.golden.TemplateImportGoldenSpec 2>&1 | grep -E 'Tests: ' ; find src/test/resources/golden -name actual.txt | wc -l
```
기대: `Tests: succeeded 0, failed 13` 그리고 actual.txt `13`개 (모두 `골든 파일이 없다`)

- [ ] **Step 4: 기록한다**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && mvn -B -o test -Pgolden -Dgolden.update=true -Dsuites=com.sec.eeg.ars.golden.TemplateImportGoldenSpec 2>&1 | grep -E 'Tests: '
```
기대: `Tests: succeeded 13, failed 0`, actual.txt 는 모두 지워진다

- [ ] **Step 5: 기록을 검토한다 — 아래 표와 다르면 받아들이지 말고 원인을 찾는다**

각 `expected.txt` 를 열어 표의 값과 맞는지 본다. 표는 2026-10-06 검증 실행의 기록이다. 다르면 (1) case.json 오타 (2) fixture 차이 (3) 실제로 코드가 다르게 동작 순으로 확인하고, (3) 이면 계획 작성자에게 알린다.

| 케이스 | 응답 | Redis (카테고리 · 제목) | DB·파일 변경 | 확인할 점 |
|---|---|---|---|---|
| `LoadEmailTemplate/import-app-overwrite` | Success | 없음 | EMAIL_TEMPLATE_REPOSITORY +1 −1, 파일 −1 +1 | KNOWN-ISSUE 4: RMS 폴더가 ARS 문서를 덮어씀 (제목 `RMS-New`) |
| `LoadEmailTemplate/import-basic` | Success | 없음 | EMAIL_IMAGE_REPOSITORY +1 −1, EMAIL_TEMPLATE_REPOSITORY +1, 파일 −2 +2 | old.png 삭제·logo.png 등록, 본문 `@logo.png` → `http://@HttpWebServerAddress/ARS/EmailImage/ARS_PHOTO_MODEL-A_RM_CPU__/logo.png`, 제목 `CPU-Alert`, `_workdone` 으로 이동 |
| `LoadEmailTemplate/import-indext-prefix` | Success | 없음 | EMAIL_TEMPLATE_REPOSITORY +1, 파일 −1 +1 | 제목 `-Memory` (특이 동작) |
| `LoadEmailTemplate/import-missing-location` | Success | 없음 | 없음 | 폴더가 없어도 Success (특이 동작) |
| `LoadEmailTemplate/import-no-html` | Success | 없음 | 없음 | html 이 없어 가져오지도 옮기지도 않음 |
| `LoadEmailTemplate/import-replace-workdone` | Success | 없음 | EMAIL_TEMPLATE_REPOSITORY +1, 파일 −2 +1 | `_workdone` 의 옛 파일을 지우고 새 폴더를 옮김 |
| `LoadEmailTemplate/import-skip-invalid` | Success | 없음 | 없음 | 잘못된 이름과 `_workdone` 을 건너뛰어 아무것도 바뀌지 않음 |
| `LoadEmailTemplate/import-subcode` | Success | 없음 | EMAIL_TEMPLATE_REPOSITORY +1, 파일 −1 +1 | subcode `CRITICAL` |
| `LoadPopupTemplate/popup-import-basic` | Success | 없음 | EMAIL_IMAGE_REPOSITORY +1, POPUP_TEMPLATE_REPOSITORY +1, 파일 −2 +2 | 이미지 prefix `popup_PHOTO_MODEL-A_RM_CPU`, 본문 링크 치환 |
| `LoadPopupTemplate/popup-import-bom` | Success | 없음 | POPUP_TEMPLATE_REPOSITORY +1, 파일 −1 +1 | BOM 이 제거된 html |
| `LoadPopupTemplate/popup-import-invalid-name` | Success | 없음 | 없음 |  |
| `LoadPopupTemplate/popup-import-missing-location` | Failed: There is no directory: missing-dir | 없음 | 없음 | `There is no directory: missing-dir` 로 Failed |
| `LoadPopupTemplate/popup-import-no-index` | Success | 없음 | 없음 |  |

- [ ] **Step 6: 통과와 반복 실행 결과가 같은지 확인한다**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && for i in 1 2; do mvn -B -o test -Pgolden 2>&1 | grep -E 'Tests: '; done
find src/test/resources/golden -name 'expected.*' -exec shasum {} + | sort > /tmp/hws-golden-1.sha
mvn -B -o test -Pgolden -Dgolden.update=true >/dev/null 2>&1
find src/test/resources/golden -name 'expected.*' -exec shasum {} + | sort > /tmp/hws-golden-2.sha
diff /tmp/hws-golden-1.sha /tmp/hws-golden-2.sha && echo "갱신 모드 후 변경 없음"
```
기대: 두 번 모두 `Tests: succeeded 154, failed 0`, 그리고 `갱신 모드 후 변경 없음`

- [ ] **Step 7: 커밋**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && git status --short   # 이 Task 의 파일만 보여야 한다
git add src/test/resources/golden \
        src/test/scala/com/sec/eeg/ars/golden/TemplateImportGoldenSpec.scala
git commit -q -F - <<'EOF'
골든: 템플릿 가져오기 13개 (LoadEmailTemplate·LoadPopupTemplate)

KNOWN-ISSUE 4 (갱신 키에 app 없음)와 특이 동작(indext- 접두사, 폴더 없어도 Success)을 기록한다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git log --oneline -1
```


## Task 12: 골든 — Cassandra 경로 (이력·첨부·스냅샷, 21개)

실행 이력(`historylog`), 첨부 파일(`customfiles`), 메일 스냅샷(`emailsnapshot`) 경로다. 운영 스키마는 키스페이스 `ars` 하나이고 첨부 조회는 `year` 컬럼을 쓴다 (`3e5c765` 의 수정이 이 스키마와 맞는다).

**Files:**
- Create: `src/test/scala/com/sec/eeg/ars/golden/CassandraPathGoldenSpec.scala`, `src/test/resources/golden/_fixtures/payloads/history.json`, `customfile.json`
- Create: 케이스 폴더와 `case.json` (아래 스크립트), 기록 후 각 케이스의 `expected.txt`

**Interfaces:**
- Consumes: `GoldenSuite` (Task 9), `GoldenCase`, `GoldenRunner`, `_files/favicon.png`, `_files/favicon.tif`

- [ ] **Step 1: fixture 와 케이스를 만든다**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && bash <<'SCRIPT'
G=src/test/resources/golden
cat > $G/_fixtures/payloads/history.json <<'EOF'
{"hostname": "EQP001", "step": 1, "txn": 1700000000001, "text": "1단계 확인", "image": "SU1BR0U=", "refimage": "UkVG", "imageWidth": 300, "imageHeight": 200, "refimageWidth": 150, "refimageHeight": 100}
EOF
cat > $G/_fixtures/payloads/customfile.json <<'EOF'
{"hostname": "EQP001", "year": 2026, "month": 7, "fname": "report.txt", "contents": "aGVsbG8="}
EOF
mk() { mkdir -p "$G/$1"; printf '%s\n' "$2" > "$G/$1/case.json"; }
K1='KNOWN-ISSUE 1: JDK 8 ImageIO 에 TIFF 리더가 없어 변환 결과가 null 이 되고 저장이 실패한다'
K2='KNOWN-ISSUE 2: URL 로 받은 값을 CQL 문자열에 그대로 붙여 쿼리가 깨진다 (주입 위험)'
K3='KNOWN-ISSUE 3: 이력 HTML 을 이스케이프하지 않고 저장·출력한다'
# ---- AddHistory (4) ----
mk AddHistory/add-history-basic '{"message":"AddHistory","base":"history"}'
mk AddHistory/add-history-script-text "{\"message\":\"AddHistory\",\"base\":\"history\",\"set\":{\"text\":\"<script>alert(1)</script>\"},\"knownIssue\":\"$K3\"}"
mk AddHistory/add-history-invalid-json '{"message":"AddHistory","bodyRaw":"{oops"}'
mk AddHistory/add-history-missing-image '{"message":"AddHistory","base":"history","unset":["image"]}'
# ---- QueryHistory (4) ----
mk QueryHistory/query-history-steps '{"message":"QueryHistory","params":{"eqpid":"EQP001","txn":"7"},"cassandra":{"historylog":[{"eqpid":"EQP001","txn":7,"step":2,"body":"<b>2단계</b>"},{"eqpid":"EQP001","txn":7,"step":1,"body":"<b>1단계</b>"}]}}'
mk QueryHistory/query-history-empty '{"message":"QueryHistory","params":{"eqpid":"EQP001","txn":"8"}}'
mk QueryHistory/query-history-unescaped "{\"message\":\"QueryHistory\",\"params\":{\"eqpid\":\"EQP001\",\"txn\":\"7\"},\"cassandra\":{\"historylog\":[{\"eqpid\":\"EQP001\",\"txn\":7,\"step\":1,\"body\":\"<script>alert(1)</script>\"}]},\"knownIssue\":\"$K3\"}"
mkdir -p $G/QueryHistory/query-history-injection && cat > $G/QueryHistory/query-history-injection/case.json <<EOF
{"message":"QueryHistory","params":{"eqpid":"x' OR eqpid='EQP001","txn":"7"},"knownIssue":"$K2"}
EOF
# ---- SaveCustomsFile (7) ----
mk SaveCustomsFile/save-text-file '{"message":"SaveCustomsFile","base":"customfile"}'
mk SaveCustomsFile/save-png '{"message":"SaveCustomsFile","base":"customfile","set":{"fname":"photo.png","contents":{"$file":"_files/favicon.png"}}}'
mk SaveCustomsFile/save-tif-lowercase "{\"message\":\"SaveCustomsFile\",\"base\":\"customfile\",\"set\":{\"fname\":\"scan.tif\",\"contents\":{\"\$file\":\"_files/favicon.tif\"}},\"knownIssue\":\"$K1\"}"
mk SaveCustomsFile/save-tif-uppercase "{\"message\":\"SaveCustomsFile\",\"base\":\"customfile\",\"set\":{\"fname\":\"SCAN.TIF\",\"contents\":{\"\$file\":\"_files/favicon.tif\"}},\"knownIssue\":\"$K1\"}"
mk SaveCustomsFile/save-tiff "{\"message\":\"SaveCustomsFile\",\"base\":\"customfile\",\"set\":{\"fname\":\"scan.tiff\",\"contents\":{\"\$file\":\"_files/favicon.tif\"}},\"knownIssue\":\"$K1\"}"
mk SaveCustomsFile/save-no-extension '{"message":"SaveCustomsFile","base":"customfile","set":{"fname":"README"}}'
mk SaveCustomsFile/save-null-fname '{"message":"SaveCustomsFile","base":"customfile","set":{"fname":null}}'
# ---- CustomFiles (3) ----
CF='"cassandra":{"customfiles":[{"eqpid":"EQP001","year":2026,"month":7,"fname":"report.txt","body":"0x68656c6c6f"}]}'
mk CustomFiles/customfiles-found "{\"message\":\"CustomFiles\",\"params\":{\"eqpid\":\"EQP001\",\"year\":\"2026\",\"month\":\"7\",\"fname\":\"report.txt\"},$CF}"
mk CustomFiles/customfiles-not-found "{\"message\":\"CustomFiles\",\"params\":{\"eqpid\":\"EQP001\",\"year\":\"2026\",\"month\":\"7\",\"fname\":\"other.txt\"},$CF}"
mkdir -p $G/CustomFiles/customfiles-injection && cat > $G/CustomFiles/customfiles-injection/case.json <<EOF
{"message":"CustomFiles","params":{"eqpid":"EQP001","year":"2026","month":"7","fname":"a'b"},$CF,"knownIssue":"$K2"}
EOF
# ---- SnapShotImage (3) ----
SS='"cassandra":{"emailsnapshot":[{"eqpid":"EQP001","timestamp":1700000000000,"body":{"$file":"_files/favicon.png"}}]}'
mk SnapShotImage/snapshot-found "{\"message\":\"SnapShotImage\",\"params\":{\"eqpid\":\"EQP001\",\"crtime\":\"1700000000000\"},$SS}"
mk SnapShotImage/snapshot-not-found "{\"message\":\"SnapShotImage\",\"params\":{\"eqpid\":\"EQP001\",\"crtime\":\"1700000000999\"},$SS}"
mk SnapShotImage/snapshot-bad-crtime "{\"message\":\"SnapShotImage\",\"params\":{\"eqpid\":\"EQP001\",\"crtime\":\"abc\"},$SS,\"knownIssue\":\"$K2\"}"
SCRIPT
```

확인: `find src/test/resources/golden -name case.json | wc -l` → 누적 `82`

- [ ] **Step 2: 스위트를 만든다**

`src/test/scala/com/sec/eeg/ars/golden/CassandraPathGoldenSpec.scala`:

```scala
package com.sec.eeg.ars.golden

import com.sec.eeg.ars.testkit.GoldenSuite

/** Cassandra 를 쓰는 경로: 실행 이력, 첨부 파일, 메일 스냅샷 */
class CassandraPathGoldenSpec extends GoldenSuite("AddHistory", "QueryHistory", "SaveCustomsFile", "CustomFiles", "SnapShotImage")
```

- [ ] **Step 3: 골든 파일이 없어 실패하는지 확인한다 (RED)**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && mvn -B -o test -Pgolden -Dsuites=com.sec.eeg.ars.golden.CassandraPathGoldenSpec 2>&1 | grep -E 'Tests: ' ; find src/test/resources/golden -name actual.txt | wc -l
```
기대: `Tests: succeeded 0, failed 21` 그리고 actual.txt `21`개 (모두 `골든 파일이 없다`)

- [ ] **Step 4: 기록한다**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && mvn -B -o test -Pgolden -Dgolden.update=true -Dsuites=com.sec.eeg.ars.golden.CassandraPathGoldenSpec 2>&1 | grep -E 'Tests: '
```
기대: `Tests: succeeded 21, failed 0`, actual.txt 는 모두 지워진다

- [ ] **Step 5: 기록을 검토한다 — 아래 표와 다르면 받아들이지 말고 원인을 찾는다**

각 `expected.txt` 를 열어 표의 값과 맞는지 본다. 표는 2026-10-06 검증 실행의 기록이다. 다르면 (1) case.json 오타 (2) fixture 차이 (3) 실제로 코드가 다르게 동작 순으로 확인하고, (3) 이면 계획 작성자에게 알린다.

| 케이스 | 응답 | Redis (카테고리 · 제목) | DB·파일 변경 | 확인할 점 |
|---|---|---|---|---|
| `AddHistory/add-history-basic` | Success | 없음 | historylog +1 | 본문 `<table bgColor=#308cfc>…<strong>1.1단계 확인</strong>…` |
| `AddHistory/add-history-invalid-json` | Failed: Unexpected character ('o' (code 111)): was expecting double… | 없음 | 없음 |  |
| `AddHistory/add-history-missing-image` | Failed: No usable value for image | 없음 | 없음 |  |
| `AddHistory/add-history-script-text` | Success | 없음 | historylog +1 | KNOWN-ISSUE 3: `<script>` 가 그대로 저장 |
| `QueryHistory/query-history-empty` | ActionResult(status=500, body=There is no data) | 없음 | 없음 |  |
| `QueryHistory/query-history-injection` | Failed: line 1:46 mismatched input 'OR' expecting EOF (...historylo… | 없음 | 없음 | KNOWN-ISSUE 2: CQL 구문 오류 메시지로 Failed |
| `QueryHistory/query-history-steps` | 이력 HTML | 없음 | 없음 | Twirl HTML 안에 `<b>1단계</b><b>2단계</b>` (step 순서) |
| `QueryHistory/query-history-unescaped` | 이력 HTML | 없음 | 없음 | KNOWN-ISSUE 3: `<script>alert(1)</script>` 가 그대로 |
| `SaveCustomsFile/save-no-extension` | Success | 없음 | customfiles +1 |  |
| `SaveCustomsFile/save-null-fname` | Failed: Invalid null value in condition for column fname | 없음 | 없음 | Cassandra 오류 메시지로 Failed |
| `SaveCustomsFile/save-png` | Success | 없음 | customfiles +1 | 변환 없이 원본 저장 |
| `SaveCustomsFile/save-text-file` | Success | 없음 | customfiles +1 |  |
| `SaveCustomsFile/save-tif-lowercase` | Failed (message null) | 없음 | 없음 | KNOWN-ISSUE 1: 저장 실패, 행 없음 |
| `SaveCustomsFile/save-tif-uppercase` | Failed (message null) | 없음 | 없음 | KNOWN-ISSUE 1: 대문자도 실패 (3e5c765 부터) |
| `SaveCustomsFile/save-tiff` | Failed (message null) | 없음 | 없음 | KNOWN-ISSUE 1: 저장 실패 |
| `CustomFiles/customfiles-found` | <5 bytes sha256:2cf24dba5fb0> | 없음 | 없음 |  |
| `CustomFiles/customfiles-injection` | <0 bytes sha256:e3b0c44298fc> | 없음 | 없음 | KNOWN-ISSUE 2: 쿼리가 깨져 빈 응답 |
| `CustomFiles/customfiles-not-found` | <0 bytes sha256:e3b0c44298fc> | 없음 | 없음 |  |
| `SnapShotImage/snapshot-bad-crtime` | <0 bytes sha256:e3b0c44298fc> | 없음 | 없음 | KNOWN-ISSUE 2: 쿼리가 깨져 빈 응답 |
| `SnapShotImage/snapshot-found` | <286 bytes sha256:99d69510d1da> | 없음 | 없음 |  |
| `SnapShotImage/snapshot-not-found` | <0 bytes sha256:e3b0c44298fc> | 없음 | 없음 |  |

- [ ] **Step 6: 통과와 반복 실행 결과가 같은지 확인한다**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && for i in 1 2; do mvn -B -o test -Pgolden 2>&1 | grep -E 'Tests: '; done
find src/test/resources/golden -name 'expected.*' -exec shasum {} + | sort > /tmp/hws-golden-1.sha
mvn -B -o test -Pgolden -Dgolden.update=true >/dev/null 2>&1
find src/test/resources/golden -name 'expected.*' -exec shasum {} + | sort > /tmp/hws-golden-2.sha
diff /tmp/hws-golden-1.sha /tmp/hws-golden-2.sha && echo "갱신 모드 후 변경 없음"
```
기대: 두 번 모두 `Tests: succeeded 175, failed 0`, 그리고 `갱신 모드 후 변경 없음`

- [ ] **Step 7: 커밋**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && git status --short   # 이 Task 의 파일만 보여야 한다
git add src/test/resources/golden \
        src/test/scala/com/sec/eeg/ars/golden/CassandraPathGoldenSpec.scala
git commit -q -F - <<'EOF'
골든: Cassandra 경로 21개 (AddHistory·QueryHistory·SaveCustomsFile·CustomFiles·SnapShotImage)

KNOWN-ISSUE 1 (TIFF 저장 실패, 대문자 포함), 2 (CQL 주입), 3 (이력 HTML 미이스케이프)를 기록한다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git log --oneline -1
```


## Task 13: 문서와 완료 점검

**Files:**
- Create: `docs/testing.md`
- Modify: `docs/plans/2026-10-06-golden-master-test-design.md` (상태 줄)

- [ ] **Step 1: `docs/testing.md` 를 쓴다**

`docs/testing.md`:

````markdown
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
````

- [ ] **Step 2: 완료 기준 1·2 — 처음부터 다시 돌려 수치를 확인한다**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && mvn -B -o clean test 2>&1 | grep -E 'Tests: |BUILD'
for i in 1 2; do mvn -B -o test -Pgolden 2>&1 | grep -E 'Tests: |BUILD'; done
```
기대: 기본 `Tests: succeeded 84, failed 0`, `-Pgolden` 두 번 모두 `Tests: succeeded 175, failed 0`

- [ ] **Step 3: 완료 기준 3·4·8 — 케이스 수, KNOWN-ISSUE 머리말, 갱신 모드 무변경**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && find src/test/resources/golden -name case.json | wc -l
grep -rh '^# KNOWN-ISSUE' src/test/resources/golden --include=expected.txt | sed -E 's/^# KNOWN-ISSUE: (KNOWN-ISSUE [0-9]+).*/\1/' | sort | uniq -c
mvn -B -o test -Pgolden -Dgolden.update=true >/dev/null 2>&1; git status --short src/test/resources/golden; find src/test/resources/golden -name 'actual.*' | wc -l
```
기대: `82`; KNOWN-ISSUE 1 `3`, 2 `3`, 3 `2`, 4 `2`, 5 `1`, 6 `1` (7·8 은 `BootstrapLifecycleSpec`·`HttpEndPointRoutingSpec` 이 고정); git status 출력 없음; actual 파일 `0`

- [ ] **Step 4: 완료 기준 5·6 — 운영 코드 무변경, 배포 lib**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && git diff 3e5c765 -- src/main | wc -l
mvn -B -o -q clean package -DskipTests && ls target/lib | wc -l && ls target/lib | grep -cE 'scalatest|scalactic|testkit|mockito|byte-buddy|objenesis'; ls target/lib | grep scala-reflect
```
기대: `0`; lib `142`, 테스트 jar `0`, `scala-reflect-2.11.8.jar`

- [ ] **Step 5: 완료 기준 7 — 공유 데이터 격리 (다른 세션이 같은 DB 를 쓰지 않을 때 한 번)**

```bash
count() { docker exec mongodb-44 mongo --quiet EARS --eval '["EMAIL_TEMPLATE_REPOSITORY","EMAIL_RECIPIENTS","EQP_INFO","EMAIL_IMAGE_REPOSITORY","POPUP_TEMPLATE_REPOSITORY","EQP_AUTO_RECOVERY","EQP_LINE_MAP","SC_PROPERTY","EMAIL_NOTIFICATION_META"].forEach(c=>print(c+"="+db.getCollection(c).countDocuments({})))'; for t in emailsnapshot historylog customfiles snapshot snapshotlist; do printf 'ars.%s=' $t; docker exec my-cassandra-server cqlsh -u cassandra -p cassandra -e "SELECT count(*) FROM ars.$t;" 2>/dev/null | awk 'NR==4{gsub(/ /,""); print}'; done; }
count > /tmp/hws-before.txt; cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && mvn -B -o test -Pgolden >/dev/null 2>&1; count > /tmp/hws-after.txt; diff /tmp/hws-before.txt /tmp/hws-after.txt && echo "격리 확인: 전후 같음"
```
기대: `격리 확인: 전후 같음`

- [ ] **Step 6: 설계 문서 상태를 갱신한다**

설계 문서의 `- 상태:` 줄을 완료 날짜로 바꾼다:

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && sed -i '' "s|^- 상태: .*|- 상태: 구현 완료 ($(date +%Y-%m-%d), 브랜치 test/golden-master). 실행·갱신 방법은 docs/testing.md|" docs/plans/2026-10-06-golden-master-test-design.md && grep -n '^- 상태:' docs/plans/2026-10-06-golden-master-test-design.md
```
기대: `- 상태: 구현 완료 (오늘 날짜, 브랜치 test/golden-master). …` 한 줄

- [ ] **Step 7: 커밋**

```bash
cd /Users/hyunkyungmin/Developer/ARS/HttpWebServer-golden && git status --short   # 이 Task 의 파일만 보여야 한다
git add docs/testing.md \
        docs/plans/2026-10-06-golden-master-test-design.md
git commit -q -F - <<'EOF'
테스트 문서: 실행·갱신 방법, KNOWN-ISSUE 목록, 특이 동작

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git log --oneline -1
```

- [ ] **Step 8: 마무리 보고**

- 브랜치 `test/golden-master` 의 커밋 목록(`git log --oneline 3e5c765..HEAD`)과 완료 기준 1~9 결과를 보고한다.
- push·PR 은 하지 않는다. `3e5c765` 가 아직 push 전이라 PR 방식은 사용자와 정한다.
- 이 저장소에는 백로그 접두사 선언이 없다. KNOWN-ISSUE 1·7·8 등을 백로그에 올릴지는 ARS 루트 조율 세션(`ARS-`, `--where HttpWebServer`)에 넘긴다고 보고한다.
