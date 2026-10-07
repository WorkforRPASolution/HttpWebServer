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
