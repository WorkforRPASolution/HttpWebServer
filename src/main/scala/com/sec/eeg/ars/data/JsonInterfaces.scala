package com.sec.eeg.ars.data

import org.json4s.DefaultFormats

case class ARSHttpDataFormat(hostname: String, step: Int, txn: Long, text: String, image: String, refimage: String, imageWidth: Int, imageHeight: Int, refimageWidth: Int, refimageHeight: Int)

// renderedBody/title (Option C에서 추가된 필드): RMS가 HTML 본문과 제목을 미리
// 완성해서 렌더링한 경우, 그 결과를 이 필드들에 담아 보낸다. Akka는 이 값을
// 그대로 사용하며 본문 안의 @HttpWebServerAddress 토큰만 치환한다.
// 두 필드를 Option[String]으로 둔 이유: 이 필드가 없던 기존 9개 필드짜리
// 페이로드도 깨지지 않고 None/None으로 파싱되게 하기 위해서다.
// (json4s DefaultFormats는 optional 필드가 없으면 None으로 처리하고
//  MappingException을 던지지 않는다.)
//
// emailCategory/displayId (그룹 경보 라우팅, 동일한 선택 필드 패턴): RMS가 그룹
// 단위 메일을 보낼 때만 채운다.
//   - emailCategory: 있으면 Akka가 수신자 카테고리 역산(getEmailCategory)을 건너뛰고
//     이 값을 그대로 사용한다.
//   - displayId: 있으면 메일 제목 헤드라인 칸에 대표 eqpId(hostname) 대신 그룹
//     식별자(예: 모델명/공정명)를 표시한다.
// 둘 다 None이면(기존 발신자·개별 경보) 현행 동작과 100% 동일하다.
case class EmailHttpDataFormat(hostname: String, ip: String, app: String, process: String, model: String, line: String, code: String, subcode: String, variables: Map[String,String], renderedBody: Option[String] = None, title: Option[String] = None, emailCategory: Option[String] = None, displayId: Option[String] = None)

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
