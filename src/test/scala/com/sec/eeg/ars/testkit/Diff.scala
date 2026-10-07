package com.sec.eeg.ars.testkit

/** 이름(컬렉션·테이블)별 문서 목록 두 벌의 차이를 "- 문서" / "+ 문서" 줄로 그린다. 같은 문서가 여러 개여도 개수대로 비교한다. */
object Diff {
  def render(before: Map[String, Seq[String]], after: Map[String, Seq[String]]): Seq[String] =
    (before.keySet ++ after.keySet).toSeq.sorted.flatMap { name =>
      val b = before.getOrElse(name, Nil)
      val a = after.getOrElse(name, Nil)
      val removed = (b diff a).sorted
      val added = (a diff b).sorted
      if (removed.isEmpty && added.isEmpty) Nil
      else name +: (removed.map("  - " + _) ++ added.map("  + " + _))
    }
}
