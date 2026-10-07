package com.sec.eeg.ars.testkit

import org.scalatest.FunSuite

class JdkGuardSpec extends FunSuite {
  test("테스트 JVM 은 JDK 8 이다") {
    JdkGuard.require8()
    assert(System.getProperty("java.version").startsWith("1.8"))
  }
}
