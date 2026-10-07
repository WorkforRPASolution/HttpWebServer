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
