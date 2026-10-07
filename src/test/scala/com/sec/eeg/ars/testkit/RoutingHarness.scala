package com.sec.eeg.ars.testkit

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference

import akka.actor.ActorSystem
import akka.testkit.TestProbe
import com.sec.eeg.ars.actor.Master
import org.apache.http.client.methods.{HttpGet, HttpOptions, HttpPost, HttpRequestBase}
import org.apache.http.entity.ByteArrayEntity
import org.apache.http.impl.client.{CloseableHttpClient, HttpClients}
import org.apache.http.util.EntityUtils
import org.eclipse.jetty.server.{Server, ServerConnector}
import org.eclipse.jetty.webapp.WebAppContext
import org.scalatra.servlet.ScalatraListener

import scala.concurrent.Await
import scala.concurrent.duration._

/**
 * HttpEndPoint 를 운영(Master.WebServiceStart)과 같은 방식으로 띄운다: Jetty WebAppContext + ScalatraListener + ScalatraBootstrap.
 * 다른 점: 포트는 임의다.
 * HttpWorker·EmailWorker 자리에는 받은 메시지를 기록하고 reply 로 응답하는 Responder 를 둔다.
 */
final class RoutingHarness {
  implicit val system: ActorSystem = ActorSystem("routing")
  val masterProbe = TestProbe()
  val httpProbe = TestProbe()
  val emailProbe = TestProbe()
  val reply = new AtomicReference[Any](RoutingHarness.DefaultReply)
  private val client: CloseableHttpClient = HttpClients.createDefault()
  private var server: Server = _
  private var port = 0

  def start(): Unit = {
    system.actorOf(StubMaster.props(masterProbe.ref, Map(
      "HttpWorker" -> Responder.props(httpProbe.ref, reply),
      "EmailWorker" -> Responder.props(emailProbe.ref, reply))), "Master")
    ActorKit.awaitActor(system, "/user/Master/HttpWorker")
    ActorKit.awaitActor(system, "/user/Master/EmailWorker")
    Master.system = system // ScalatraBootstrap 이 이 전역 값을 쓴다
    server = RoutingHarness.newServer(RoutingHarness.BootstrapClassName)
    server.start()
    port = server.getConnectors()(0).asInstanceOf[ServerConnector].getLocalPort
  }

  def stop(): Unit = {
    client.close()
    if (server != null) server.stop()
    Await.ready(system.terminate(), 10.seconds)
  }

  /** 테스트 사이에 남은 메시지를 비우고 응답을 기본값으로 되돌린다 */
  def reset(): Unit = {
    reply.set(RoutingHarness.DefaultReply)
    Seq(masterProbe, httpProbe, emailProbe).foreach(p => p.receiveWhile(max = 200.millis, idle = 50.millis) { case m => m })
  }

  def url(path: String): String = s"http://127.0.0.1:$port$path"

  def get(path: String, headers: (String, String)*): RoutingHarness.Response = execute(new HttpGet(url(path)), headers)

  def post(path: String, body: Array[Byte], contentType: Option[String], headers: (String, String)*): RoutingHarness.Response = {
    val req = new HttpPost(url(path))
    req.setEntity(new ByteArrayEntity(body))
    contentType.foreach(ct => req.setHeader("Content-Type", ct))
    execute(req, headers)
  }

  def options(path: String, headers: (String, String)*): RoutingHarness.Response = execute(new HttpOptions(url(path)), headers)

  private def execute(req: HttpRequestBase, headers: Seq[(String, String)]): RoutingHarness.Response = {
    headers.foreach { case (k, v) => req.setHeader(k, v) }
    val resp = client.execute(req)
    try {
      val body = Option(resp.getEntity).map(e => EntityUtils.toByteArray(e)).getOrElse(Array.empty[Byte])
      RoutingHarness.Response(resp.getStatusLine.getStatusCode, body,
        resp.getAllHeaders.map(h => h.getName.toLowerCase -> h.getValue).toMap)
    } finally resp.close()
  }
}

object RoutingHarness {
  val DefaultReply = """{"result":"Success","message":""}"""
  val BootstrapClassName = "ScalatraBootstrap" // Master.WebServiceStart 와 같은 값

  final case class Response(status: Int, body: Array[Byte], headers: Map[String, String]) {
    def text: String = new String(body, UTF_8)

    def header(name: String): Option[String] = headers.get(name.toLowerCase)
  }

  /** Master.WebServiceStart 와 같은 구성의 Jetty (포트 0 = 임의 포트) */
  def newServer(lifeCycleClass: String): Server = {
    val server = new Server(0)
    val context = new WebAppContext()
    context.setContextPath("/")
    context.setResourceBase(Files.createTempDirectory("hws-routing").toFile.getAbsolutePath)
    context.setInitParameter(ScalatraListener.LifeCycleKey, lifeCycleClass)
    context.addEventListener(new ScalatraListener)
    server.setHandler(context)
    server
  }
}
