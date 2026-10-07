package com.sec.eeg.ars.testkit

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/**
 * 골든 파일 비교기. 평소에는 비교만 하고, 다르면 같은 폴더에 actual.* 를 남기고 실패 메시지를 돌려준다.
 * -Dgolden.update=true 일 때만 expected.* 를 덮어쓴다.
 */
object GoldenFile {
  /** 소스 트리의 골든 폴더. 갱신 모드가 여기에 써야 하므로 target/test-classes 가 아니다. */
  val Root: File = new File(sys.props("user.dir"), "src/test/resources/golden")

  def updateMode: Boolean = sys.props.get("golden.update").contains("true")

  /** None = 일치(또는 갱신함), Some(메시지) = 불일치 */
  def check(expected: File, actualText: String, update: Boolean = updateMode): Option[String] = {
    val actual = normalize(actualText)
    val actualFile = new File(expected.getParentFile, expected.getName.replaceFirst("^expected", "actual"))
    expected.getParentFile.mkdirs()
    if (update) {
      Files.write(expected.toPath, actual.getBytes(UTF_8))
      Files.deleteIfExists(actualFile.toPath)
      None
    } else {
      val exp = if (expected.exists()) Some(normalize(new String(Files.readAllBytes(expected.toPath), UTF_8))) else None
      if (exp.contains(actual)) {
        Files.deleteIfExists(actualFile.toPath)
        None
      } else {
        Files.write(actualFile.toPath, actual.getBytes(UTF_8))
        Some(message(expected, actualFile, exp, actual))
      }
    }
  }

  /** 줄바꿈을 \n 으로 맞추고 끝에 줄바꿈 하나를 보장한다 */
  def normalize(s: String): String = {
    val unix = s.replace("\r\n", "\n")
    if (unix.endsWith("\n")) unix else unix + "\n"
  }

  private def message(expected: File, actualFile: File, exp: Option[String], actual: String): String = {
    val head = exp match {
      case None => s"골든 파일이 없다: ${expected.getPath}"
      case Some(e) => s"골든 파일과 다르다: ${expected.getPath}\n" + diff(e, actual)
    }
    head + s"\n실제 출력: ${actualFile.getPath}\n의도한 변경이면 -Dgolden.update=true 로 갱신한 뒤 git diff 로 검토한다."
  }

  /** 처음으로 다른 줄의 앞뒤를 보여 준다 */
  def diff(expected: String, actual: String): String = {
    val e = expected.split("\n", -1)
    val a = actual.split("\n", -1)
    val first = (0 until math.max(e.length, a.length)).find(i => e.lift(i) != a.lift(i)).getOrElse(0)
    val from = math.max(0, first - 2)
    val to = first + 3
    def show(label: String, lines: Array[String]): String =
      (from until math.min(to, lines.length)).map(i => f"$label ${i + 1}%4d| ${lines(i)}").mkString("\n")
    s"첫 차이: ${first + 1}번째 줄 (기대 ${e.length}줄, 실제 ${a.length}줄)\n${show("-", e)}\n${show("+", a)}"
  }
}
