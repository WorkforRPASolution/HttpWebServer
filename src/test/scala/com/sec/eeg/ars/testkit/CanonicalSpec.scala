package com.sec.eeg.ars.testkit

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8

import org.bson.Document
import org.bson.types.Binary
import org.scalatest.FunSuite

class CanonicalSpec extends FunSuite {
  test("객체 키를 정렬하고 중첩 구조를 유지한다") {
    val d = Document.parse("""{"b": 1, "a": {"d": 2, "c": [3, "x"]}}""")
    assert(Canonical.value(d) == """{"a":{"c":[3,"x"],"d":2},"b":1}""")
  }

  test("바이너리는 길이와 sha256 앞 12자리로 줄인다") {
    val hello = "hello".getBytes(UTF_8)
    assert(Canonical.bytes(hello) == "<5 bytes sha256:2cf24dba5fb0>")
    assert(Canonical.value(hello) == "\"<5 bytes sha256:2cf24dba5fb0>\"")
    assert(Canonical.value(new Binary(hello)) == Canonical.value(hello))
    assert(Canonical.value(ByteBuffer.wrap(hello)) == Canonical.value(hello))
    assert(Canonical.bytes(Array.empty[Byte]) == "<0 bytes sha256:e3b0c44298fc>")
  }

  test("문자열은 JSON 규칙대로 이스케이프하고 한글은 그대로 둔다") {
    assert(Canonical.quote("한글 \"q\" \\ \n\t") == "\"한글 \\\"q\\\" \\\\ \\n\\t\"")
  }

  test("null, 숫자, 불리언, 날짜") {
    assert(Canonical.value(null) == "null")
    assert(Canonical.value(java.lang.Long.valueOf(1700000000000L)) == "1700000000000")
    assert(Canonical.value(java.lang.Boolean.TRUE) == "true")
    assert(Canonical.value(new java.util.Date(0L)) == "\"1970-01-01T00:00:00.000Z\"")
  }
}
