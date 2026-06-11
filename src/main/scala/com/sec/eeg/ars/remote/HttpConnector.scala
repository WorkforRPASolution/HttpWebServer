package com.sec.eeg.ars.remote

import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.{CloseableHttpResponse, HttpGet}
import org.apache.http.impl.client.HttpClientBuilder
import org.slf4j.LoggerFactory

object HttpConnector {
  private val logger = LoggerFactory.getLogger(HttpConnector.toString)

  def SendShutdown() : Unit = {
    val _requestConfig = RequestConfig.custom().setSocketTimeout(5000).setConnectTimeout(5000).build()
    val httpClient = HttpClientBuilder.create().setDefaultRequestConfig(_requestConfig).build()

    try {
      val get = new HttpGet(s"http://127.0.0.1:8000/Shutdown")

      var response: CloseableHttpResponse = null
      response = httpClient.execute(get)
      logger.info(s"Response code : ${response.getStatusLine.getStatusCode}")
    }
    catch {
      case ex : Exception => logger.error(s"Exception: ${ex}")
    }
    finally {
      if (httpClient != null)
        httpClient.close()
    }
  }
}
