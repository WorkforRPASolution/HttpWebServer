package com.sec.eeg.ars.testkit

import org.scalatest.FunSuite

class GoldenTagSpec extends FunSuite {
  test("pom 의 golden.update 값이 테스트 JVM 까지 전달된다") {
    assert(sys.props.get("golden.update").isDefined)
  }

  test("이 테스트는 -Pgolden 일 때만 실행된다", Golden) {
    assert(true)
  }
}
