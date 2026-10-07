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
