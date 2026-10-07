package com.sec.eeg.ars.testkit

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

import org.scalatest.FunSuite

class GoldenFileSpec extends FunSuite {
  private def tempDir(): File = Files.createTempDirectory("golden-file-spec").toFile

  private def write(f: File, s: String): Unit = Files.write(f.toPath, s.getBytes(UTF_8))

  private def read(f: File): String = new String(Files.readAllBytes(f.toPath), UTF_8)

  test("같으면 None 이고, 남아 있던 actual 파일을 지운다") {
    val d = tempDir()
    val exp = new File(d, "expected.txt")
    val act = new File(d, "actual.txt")
    write(exp, "a\n한글\n")
    write(act, "old")
    assert(GoldenFile.check(exp, "a\n한글\n", update = false).isEmpty)
    assert(!act.exists())
  }

  test("다르면 actual 파일을 쓰고 첫 차이를 알려 준다") {
    val d = tempDir()
    val exp = new File(d, "expected.txt")
    write(exp, "a\nb\nc\n")
    val msg = GoldenFile.check(exp, "a\nX\nc\n", update = false)
    assert(msg.isDefined)
    assert(msg.get.contains("첫 차이: 2번째 줄"))
    assert(msg.get.contains("-    2| b"))
    assert(msg.get.contains("+    2| X"))
    assert(read(new File(d, "actual.txt")) == "a\nX\nc\n")
  }

  test("expected 가 없으면 실패하고 actual 을 남긴다") {
    val d = tempDir()
    val msg = GoldenFile.check(new File(d, "expected.txt"), "new", update = false)
    assert(msg.exists(_.contains("골든 파일이 없다")))
    assert(read(new File(d, "actual.txt")) == "new\n")
  }

  test("갱신 모드는 expected 를 쓰고 actual 을 지운다") {
    val d = tempDir()
    val exp = new File(d, "expected.txt")
    val act = new File(d, "actual.txt")
    write(act, "stale")
    assert(GoldenFile.check(exp, "fresh", update = true).isEmpty)
    assert(read(exp) == "fresh\n")
    assert(!act.exists())
  }

  test("CRLF 와 끝 줄바꿈 차이는 같은 것으로 본다") {
    val d = tempDir()
    val exp = new File(d, "expected.txt")
    write(exp, "a\r\nb")
    assert(GoldenFile.check(exp, "a\nb\n", update = false).isEmpty)
  }

  test("expected.html 의 실제 출력은 actual.html 에 쓴다") {
    val d = tempDir()
    GoldenFile.check(new File(d, "expected.html"), "<p>x</p>", update = false)
    assert(new File(d, "actual.html").exists())
  }
}
