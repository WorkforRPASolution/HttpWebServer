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
