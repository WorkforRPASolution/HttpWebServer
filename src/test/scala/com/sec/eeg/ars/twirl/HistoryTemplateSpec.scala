package com.sec.eeg.ars.twirl

import java.io.File

import com.sec.eeg.ars.testkit.GoldenFile
import org.scalatest.FunSuite

class HistoryTemplateSpec extends FunSuite {
  test("이력 화면 HTML (KNOWN-ISSUE 3: 본문을 이스케이프하지 않는다)") {
    val body = html.history.render("<table><tr><td>1단계</td></tr></table><script>alert(1)</script>").body
    assert(body.contains("<script>alert(1)</script>"))
    GoldenFile.check(new File(GoldenFile.Root, "HistoryTemplate/expected.html"), body).foreach(m => fail(m))
  }
}
