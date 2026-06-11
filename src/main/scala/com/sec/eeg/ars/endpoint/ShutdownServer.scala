package com.sec.eeg.ars.endpoint

import java.net.InetSocketAddress
import com.sec.eeg.ars.actor.Master
import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}

class ShutdownHandler extends HttpHandler{
  override def handle(httpExchange: HttpExchange): Unit = {
    Master.system.actorSelection("/user/Master") ! Master.ShutDown()
    val response = "OK"
    httpExchange.sendResponseHeaders(200, response.length())
    val os = httpExchange.getResponseBody()
    os.write(response.getBytes())
    os.close()
  }
}

object ShutdownServer {
  var server : HttpServer = null
  def start = {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8000), 0)
    server.createContext("/Shutdown", new ShutdownHandler())
    server.setExecutor(null)
    server.start()
  }

  def stop = server.stop(0)
}
