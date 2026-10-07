package com.sec.eeg.ars.testkit

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/** 하네스 자체 테스트용: 임시 폴더에 <message>/<name>/case.json 을 만든다 */
object TempCase {
  def dir(message: String, name: String, json: String): File = {
    val d = new File(Files.createTempDirectory("golden-temp-case").toFile, s"$message/$name")
    d.mkdirs()
    Files.write(new File(d, "case.json").toPath, json.getBytes(UTF_8))
    d
  }
}
