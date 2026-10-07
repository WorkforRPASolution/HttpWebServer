package com.sec.eeg.ars.testkit

import com.mongodb.client.MongoClients
import org.bson.Document
import org.scalatest.FunSuite

class MongoGoldenSpec extends FunSuite {
  test("접속할 수 없으면 무엇을 확인할지 알려 주며 빨리 실패한다") {
    val url = "mongodb://localhost:1/?serverSelectionTimeoutMS=500"
    val client = MongoClients.create(url)
    try {
      val t0 = System.currentTimeMillis()
      val e = intercept[IllegalStateException](MongoGolden.ping(client.getDatabase("x"), url))
      assert(e.getMessage.contains("mongodb-44"))
      assert(System.currentTimeMillis() - t0 < 5000)
    } finally client.close()
  }

  test("테스트 DB 이름은 HWS_GOLDEN 이다", Golden) {
    assert(MongoGolden.database.getName == "HWS_GOLDEN")
  }

  test("비우고, 넣고, 전후 차이를 본다", Golden) {
    MongoGolden.reset()
    MongoGolden.seed(Map("C" -> Seq("""{"k": "v", "n": 1}""")))
    val before = MongoGolden.snapshot()
    assert(before == Map("C" -> Seq("""{"k":"v","n":1}""")))
    MongoGolden.database.getCollection("C").insertOne(Document.parse("""{"k": "새것"}"""))
    assert(Diff.render(before, MongoGolden.snapshot()) == Seq("C", """  + {"k":"새것"}"""))
    MongoGolden.reset()
    assert(MongoGolden.snapshot().isEmpty)
  }
}
