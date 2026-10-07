package com.sec.eeg.ars.testkit

import org.scalatest.FunSuite

/** golden/<message>/<케이스>/ 마다 Golden 태그 테스트를 하나씩 등록한다. case.json 은 테스트 안에서 읽는다. */
abstract class GoldenSuite(messages: String*) extends FunSuite {
  for (message <- messages; dir <- GoldenCase.dirs(message))
    test(s"$message/${dir.getName}", Golden) {
      val c = GoldenCase.load(dir)
      GoldenFile.check(c.expectedFile, GoldenRunner.run(c)).foreach(m => fail(m))
    }
}
