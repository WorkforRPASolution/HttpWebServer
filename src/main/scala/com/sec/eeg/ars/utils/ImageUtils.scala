package com.sec.eeg.ars.utils

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

import javax.imageio.ImageIO
import org.slf4j.LoggerFactory

object ImageUtils {
  private val logger = LoggerFactory.getLogger(ImageUtils.toString)

  def convertTiffBytesToJpegBytes(bytes: Array[Byte]): Array[Byte] = {
    try {
      val is = new ByteArrayInputStream(bytes)
      val tiffImage = ImageIO.read(is)
      val jpegImage = new BufferedImage(tiffImage.getWidth, tiffImage.getHeight, BufferedImage.TYPE_INT_RGB)
      jpegImage.createGraphics().drawImage(tiffImage, 0, 0, Color.WHITE, null)
      val baos = new ByteArrayOutputStream()
      ImageIO.write(jpegImage, "jpeg", baos)
      baos.toByteArray
    }
    catch {
      case ex : Exception => logger.error(s"Exception: ${ex}")
        logger.error("stack trace", ex)
        null
    }
  }
}
