package com.sec.eeg.ars.testkit

/**
 * HttpWebServer 테스트는 JDK 8에서만 의미가 있다. JDK 8의 ImageIO에는 TIFF 리더가 없어서
 * 운영과 같은 결과(TIFF 변환 실패)가 나오는 것은 JDK 8뿐이다.
 * JDK 9 이상에서는 scalac 2.11.8이 컴파일 단계에서 먼저 실패하므로, 이 검사는 IDE 등에서
 * 다른 JDK로 테스트만 돌리는 경우를 막는다.
 */
object JdkGuard {
  def require8(): Unit = {
    val v = System.getProperty("java.version")
    if (!v.startsWith("1.8"))
      throw new IllegalStateException(
        s"HttpWebServer 테스트는 JDK 8에서만 실행한다 (현재 java.version=$v). " +
          "export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home 후 다시 실행한다.")
  }
}
