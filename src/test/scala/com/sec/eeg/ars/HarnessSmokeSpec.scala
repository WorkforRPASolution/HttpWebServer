package com.sec.eeg.ars

import org.scalatest.{FlatSpec, Matchers}

/**
 * P5-0 harness bootstrap smoke test. Proves the ScalaTest test harness
 * (scala-maven-plugin testCompile + scalatest-maven-plugin) actually compiles
 * and runs `src/test/scala` specs via `mvn test`. P5 replaces/augments this
 * with EmailHttpDataFormatSpec (json4s parsing) and EmailBodyResolverSpec.
 */
class HarnessSmokeSpec extends FlatSpec with Matchers {

  "the ScalaTest harness" should "compile and execute a spec" in {
    1 should be(1)
  }
}
