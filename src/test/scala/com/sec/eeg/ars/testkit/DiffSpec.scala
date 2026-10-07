package com.sec.eeg.ars.testkit

import org.scalatest.FunSuite

class DiffSpec extends FunSuite {
  test("이름별로 삭제·추가를 그리고, 같은 문서는 개수대로 비교한다") {
    val before = Map("A" -> Seq("x", "x", "y"), "B" -> Seq("k"))
    val after = Map("A" -> Seq("x", "z"), "B" -> Seq("k"), "C" -> Seq("n"))
    assert(Diff.render(before, after) == Seq("A", "  - x", "  - y", "  + z", "C", "  + n"))
  }

  test("변화가 없으면 빈 목록") {
    assert(Diff.render(Map("A" -> Seq("x")), Map("A" -> Seq("x"))).isEmpty)
  }
}
