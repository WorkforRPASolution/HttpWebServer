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
      case "SaveCustomFile" => (HttpWorkerPath, SaveCustomFile(body))
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
