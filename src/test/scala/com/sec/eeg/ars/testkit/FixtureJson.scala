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
