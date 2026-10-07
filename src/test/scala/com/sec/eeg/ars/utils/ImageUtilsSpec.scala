package com.sec.eeg.ars.utils

import java.io.{ByteArrayInputStream, File}
import java.nio.file.Files

import com.sec.eeg.ars.testkit.{GoldenFile, JdkGuard}
import javax.imageio.ImageIO
import org.scalatest.FunSuite

class ImageUtilsSpec extends FunSuite {
  private def sample(name: String): Array[Byte] = Files.readAllBytes(new File(GoldenFile.Root, s"_files/$name").toPath)

  test("KNOWN-ISSUE 1: JDK 8 ImageIO 에는 TIFF 리더가 없어 정상 TIFF 도 null 이 된다") {
    JdkGuard.require8()
    assert(!ImageIO.getReaderFormatNames.map(_.toLowerCase).contains("tiff"))
    assert(ImageUtils.convertTiffBytesToJpegBytes(sample("favicon.tif")) == null)
  }

  test("PNG 는 같은 크기의 JPEG 로 바뀌고 결과는 매번 같다") {
    val png = sample("favicon.png")
    val out = ImageUtils.convertTiffBytesToJpegBytes(png)
    assert(out != null)
    assert(out(0) == 0xFF.toByte && out(1) == 0xD8.toByte)
    val img = ImageIO.read(new ByteArrayInputStream(out))
    assert(img.getWidth == 48 && img.getHeight == 48)
    assert(java.util.Arrays.equals(out, ImageUtils.convertTiffBytesToJpegBytes(png)))
  }

  test("깨진 바이트와 빈 배열은 null") {
    assert(ImageUtils.convertTiffBytesToJpegBytes(Array[Byte](1, 2, 3)) == null)
    assert(ImageUtils.convertTiffBytesToJpegBytes(Array.empty[Byte]) == null)
  }
}
