package com.sec.eeg.ars.data

import org.json4s.DefaultFormats

case class ARSHttpDataFormat(hostname: String, step: Int, txn: Long, text: String, image: String, refimage: String, imageWidth: Int, imageHeight: Int, refimageWidth: Int, refimageHeight: Int)

// renderedBody/title (Option C, additive): when RMS pre-renders the full HTML
// body + subject it sends them here; Akka uses them directly (only the
// @HttpWebServerAddress token is substituted — D2). Option[String] so the
// historical 9-field payload (no keys) still extracts cleanly as None/None
// (json4s DefaultFormats: missing optional field → None, no MappingException).
case class EmailHttpDataFormat(hostname: String, ip: String, app: String, process: String, model: String, line: String, code: String, subcode: String, variables: Map[String,String], renderedBody: Option[String] = None, title: Option[String] = None)

case class RecoveryEmailHttpDataFormat(hostname: String, process: String, line: String, model: String, scname: String, title: String, body: String, variables: Map[String,String])

case class EARSRTMEmailHttpDataFormat(process: String, model: String, line: String, eqpid: String, code: String, variables: Map[String,String])

case class HttpResponse(result: String, message: String)

case class ScriptResultFormat(success: Boolean, hostname: String, ip: String, process: String, line: String, model: String, scname: String, output: String, variables: Map[String,String])

case class CustomFilesFormat(hostname: String, year: Int, month: Int, fname: String, contents: String)

object JsonInterfaces {
  implicit val formats = DefaultFormats
  import org.json4s.JsonDSL._
  import org.json4s.jackson.JsonMethods._

  def toJson(sc: HttpResponse) : String = {
    val json =
      ("result" -> sc.result) ~
        ("message" -> sc.message)
    compact(render(json))
  }
}
