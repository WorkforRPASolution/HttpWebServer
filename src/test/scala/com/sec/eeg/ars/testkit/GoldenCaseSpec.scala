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
