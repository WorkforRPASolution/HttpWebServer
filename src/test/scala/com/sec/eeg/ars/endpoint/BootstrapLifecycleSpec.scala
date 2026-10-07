package com.sec.eeg.ars.endpoint

import com.sec.eeg.ars.testkit.RoutingHarness
import org.scalatest.FunSuite

class BootstrapLifecycleSpec extends FunSuite {
  test("KNOWN-ISSUE 7: 운영 설정의 부트스트랩 이름 \"ScalatraBootstrap\" 으로는 Jetty 가 기동하지 않는다") {
    // Master.WebServiceStart 가 쓰는 값과 같다
    val server = RoutingHarness.newServer("ScalatraBootstrap")
    try {
      val e = intercept[AssertionError](server.start())
      assert(e.getMessage.contains("No lifecycle class found!"))
    } finally server.stop()
  }

  test("부트스트랩 클래스는 패키지 com.sec.eeg.ars 안에 있다") {
    assert(classOf[com.sec.eeg.ars.ScalatraBootstrap].getName == "com.sec.eeg.ars.ScalatraBootstrap")
    intercept[ClassNotFoundException](Class.forName("ScalatraBootstrap"))
  }
}
