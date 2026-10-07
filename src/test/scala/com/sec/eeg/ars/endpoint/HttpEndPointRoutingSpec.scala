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
    Route("POST", "/ARS/SaveCustomfiles", "http", SaveCustomFile(body)),
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
    val year = h.get("/ARS/Customfiles/EQP001/yy/07/report.txt")
    assert(year.status == 500)
    assert(year.text.startsWith("java.lang.NumberFormatException: For input string: \"yy\""))
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
