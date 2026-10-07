package com.sec.eeg.ars.endpoint

import com.sec.eeg.ars.testkit.RoutingHarness
import org.eclipse.jetty.webapp.WebAppContext
import org.scalatest.FunSuite
import org.scalatra.LifeCycle

class BootstrapLifecycleSpec extends FunSuite {
  test("운영 설정의 부트스트랩 이름 \"ScalatraBootstrap\" 으로 Jetty 가 기동한다") {
    // Master.WebServiceStart 가 쓰는 값과 같다
    val server = RoutingHarness.newServer("ScalatraBootstrap")
    try {
      server.start()
      assert(server.isStarted)
      assert(server.getHandler.asInstanceOf[WebAppContext].isAvailable)
    } finally server.stop()
  }

  test("부트스트랩 클래스는 사내 원본처럼 기본 패키지에 있다") {
    assert(classOf[LifeCycle].isAssignableFrom(Class.forName("ScalatraBootstrap")))
    intercept[ClassNotFoundException](Class.forName("com.sec.eeg.ars.ScalatraBootstrap"))
  }
}
