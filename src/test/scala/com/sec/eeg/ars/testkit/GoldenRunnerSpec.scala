package com.sec.eeg.ars.testkit

import com.sec.eeg.ars.data.ServiceConfig
import org.scalatest.FunSuite

class GoldenRunnerSpec extends FunSuite {
  test("팝업 조회 한 건을 실행해 정해진 형식으로 그린다", Golden) {
    val c = GoldenCase.load(TempCase.dir("PopupContent", "selftest",
      """{"message":"PopupContent","params":{"process":"P","model":"M","code":"C"},
        |"mongo":{"POPUP_TEMPLATE_REPOSITORY":[{"process":"P","model":"M","code":"C","html":"<div>셀프테스트</div>"}]}}""".stripMargin))
    assert(GoldenRunner.run(c) ==
      """# case: PopupContent/selftest
        |== reply ==
        |<div>셀프테스트</div>
        |== redis ==
        |(변경 없음)
        |== mongo ==
        |(변경 없음)
        |== cassandra ==
        |(변경 없음)
        |== files ==
        |(변경 없음)
        |""".stripMargin)
  }

  test("스냅샷이 붙은 메일: 테스트 키스페이스에 저장되고 링크와 행의 시각이 같은 <T1> 이 된다", Golden) {
    val c = GoldenCase.load(TempCase.dir("SendEmail", "selftest",
      """{"message":"SendEmail","base":"rms:legacy",
        |"set":{"variables":{"__snapshot__":{"$file":"_files/favicon.png"}}},
        |"mongo":{
        |  "EMAIL_TEMPLATE_REPOSITORY":[{"process":"PHOTO","model":"MODEL-A","code":"RESOURCE_MONITOR","subcode":"CPU_CRITICAL","title":"T","html":"<p>snap=@__snapshot__</p>"}],
        |  "EMAIL_RECIPIENTS":[{"app":"ARS","code":"RESOURCE_MONITOR","process":"PHOTO","model":"MODEL-A","line":"L1","emailCategory":"EMAIL-TEST-1"}]}}""".stripMargin))
    val text = GoldenRunner.run(c)
    assert(text.contains("EmailFormat(project=ARS, category=EMAIL-TEST-1)"))
    assert(text.contains("[EARS][T][EQP001][RESOURCE_MONITOR-CPU_CRITICAL]:<p>snap=http://hws.golden:8080/ARS/SnapShotImage/EQP001/<T1></p>"))
    assert(text.contains("""  + {"body":"<286 bytes sha256:99d69510d1da>","eqpid":"EQP001","timestamp":<T1>}"""))
  }

  test("실행 뒤 ServiceConfig 값을 되돌린다", Golden) {
    val before = ServiceConfigState.capture()
    ServiceConfig.ServicePublicAddress = "before:1"
    try {
      GoldenRunner.run(GoldenCase.load(TempCase.dir("PopupContent", "restore",
        """{"message":"PopupContent","params":{"process":"P","model":"M","code":"X"}}""")))
      assert(ServiceConfig.ServicePublicAddress == "before:1")
    } finally before.restore()
  }
}
