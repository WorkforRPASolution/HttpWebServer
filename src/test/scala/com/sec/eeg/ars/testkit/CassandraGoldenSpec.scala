package com.sec.eeg.ars.testkit

import org.scalatest.FunSuite

class CassandraGoldenSpec extends FunSuite {
  test("접속할 수 없으면 무엇을 확인할지 알려 준다") {
    val c = CassandraGolden.openCluster("127.0.0.1", 1, "u", "p")
    try {
      val e = intercept[IllegalStateException](CassandraGolden.connectOrExplain(c, "127.0.0.1:1"))
      assert(e.getMessage.contains("my-cassandra-server"))
    } finally c.close()
  }

  test("스키마 파일의 키스페이스 이름만 hws_golden 으로 바꾼다") {
    val stmts = CassandraGolden.schemaStatements()
    assert(stmts.head.startsWith("CREATE KEYSPACE IF NOT EXISTS hws_golden"))
    assert(stmts.tail.nonEmpty && stmts.tail.forall(_.startsWith("CREATE TABLE IF NOT EXISTS hws_golden.")))
    assert(!stmts.exists(_.contains(" ars.")))
  }

  test("테이블은 운영 ars 와 같은 다섯 개다", Golden) {
    assert(CassandraGolden.tables == Seq("customfiles", "emailsnapshot", "historylog", "snapshot", "snapshotlist"))
  }

  test("넣고, 전후 차이를 보고, 비운다", Golden) {
    CassandraGolden.clear()
    CassandraGolden.seed(Map("historylog" -> Seq("""{"eqpid":"EQP001","txn":7,"step":1,"body":"<b>1</b>"}""")))
    val snap = CassandraGolden.snapshot()
    assert(snap("historylog") == Seq("""{"body":"<b>1</b>","eqpid":"EQP001","step":1,"txn":7}"""))
    CassandraGolden.seed(Map("emailsnapshot" -> Seq("""{"eqpid":"EQP001","timestamp":1700000000000,"body":"0x68656c6c6f"}""")))
    assert(Diff.render(snap, CassandraGolden.snapshot()) ==
      Seq("emailsnapshot", """  + {"body":"<5 bytes sha256:2cf24dba5fb0>","eqpid":"EQP001","timestamp":1700000000000}"""))
    assert(CassandraGolden.emailSnapshotTimes() == Set(1700000000000L))
    CassandraGolden.clear()
    assert(CassandraGolden.snapshot().values.forall(_.isEmpty))
  }

  test("운영 코드의 connect(\"ars\") 를 테스트 키스페이스로 돌린다", Golden) {
    val s = CassandraGolden.redirectingCluster().connect("ars")
    try assert(s.getLoggedKeyspace == "hws_golden") finally s.close()
  }
}
