package com.sec.eeg.ars.testkit

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8

import com.sec.eeg.ars.actor.EmailFormat
import org.scalatest.FunSuite

class GoldenRenderSpec extends FunSuite {
  private val c = GoldenCase(new File("x/y"), "M/c", "M", None, Map.empty, Map.empty, Map.empty, Map.empty, Map.empty, Some("설명"))

  test("응답 종류별 표기") {
    assert(GoldenRender.replyText("""{"a":1}""") == """{"a":1}""")
    assert(GoldenRender.replyText("hello".getBytes(UTF_8)) == "<5 bytes sha256:2cf24dba5fb0>")
    assert(GoldenRender.replyText(html.history.render("<b>x</b>")).contains("<b>x</b>"))
    assert(GoldenRender.replyText(org.scalatra.InternalServerError("There is no data")) == "ActionResult(status=500, body=There is no data)")
  }

  test("구역 순서와 KNOWN-ISSUE 머리말") {
    val text = GoldenRender.render(c, "ok", Seq(EmailFormat("ARS", "CAT", "[t]:<p>본문</p>")), Seq("C", "  + {}"), Nil, Nil)
    assert(text ==
      """# case: M/c
        |# KNOWN-ISSUE: 설명
        |== reply ==
        |ok
        |== redis ==
        |EmailFormat(project=ARS, category=CAT)
        |[t]:<p>본문</p>
        |== mongo ==
        |C
        |  + {}
        |== cassandra ==
        |(변경 없음)
        |== files ==
        |(변경 없음)
        |""".stripMargin)
  }

  test("스냅샷 시각은 작은 것부터 <T1>, <T2>") {
    val t = "link/1790000000002 row 1790000000001 again 1790000000002"
    assert(GoldenRender.normalizeTimes(t, Seq(1790000000002L, 1790000000001L)) == "link/<T2> row <T1> again <T2>")
  }
}
