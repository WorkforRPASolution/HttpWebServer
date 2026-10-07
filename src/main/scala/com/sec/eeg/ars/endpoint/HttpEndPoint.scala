package com.sec.eeg.ars.endpoint

import akka.actor.ActorSystem
import akka.util.Timeout
import com.sec.eeg.ars.actor.Master.ShutDown
import com.sec.eeg.ars.actor._
import org.scalatra._
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.language.postfixOps

import scala.concurrent.{ExecutionContext, Future}

class HttpEndPoint(system:ActorSystem) extends ScalatraServlet with FutureSupport with CorsSupport {
  private val log = LoggerFactory.getLogger(classOf[HttpEndPoint])
  override protected implicit def executor: ExecutionContext = system.dispatcher
  import _root_.akka.pattern.ask
  implicit val defaultTimeout = Timeout(10 seconds)
  override def asyncTimeout = 10 seconds

  override def initialize(config: ConfigT): Unit = {
    config.context.setInitParameter(CorsSupport.AllowedOriginsKey,"*")
    config.context.setInitParameter(CorsSupport.AllowedHeadersKey,"*")
    config.context.setInitParameter(CorsSupport.AllowedMethodsKey,"*")
    super.initialize(config)
  }

  post("/ARS/AppendHistory") {
    new AsyncResult() {
      override val is: Future[_] = {
        log.info("AddHistory request received")
        system.actorSelection("/user/Master/HttpWorker") ? AddHistory(request.body)
      }
    }
  }

  post("/ARS/LoadEmailTemplate") {
    new AsyncResult() {
      override val is: Future[_] = {
        val addr = request.getRemoteAddr
        log.info(s"LoadEmailTemplate request received: ${addr}")
        system.actorSelection("/user/Master/EmailWorker") ? LoadEmailTemplate()
      }
    }
  }

  post("/ARS/LoadPopupTemplate") {
    new AsyncResult() {
      override val is: Future[_] = {
        val addr = request.getRemoteAddr
        log.info(s"LoadPopupTemplate request received: ${addr}")
        system.actorSelection("/user/Master/EmailWorker") ? LoadPopupTemplate()
      }
    }
  }

  get("/ARS/Popup/:process/:model/:code") {
    new AsyncResult() {
      override val is: Future[_] = {
        log.info(s"PopupContent request received")
        system.actorSelection("/user/Master/EmailWorker") ? PopupContent(params("process"),params("model"),params("code"))
      }
    }
  }

  //3.0.8
  get("/ARS/v2/Popup/:process/:model/:code") {
    new AsyncResult() {
      override val is: Future[_] = {
        log.info(s"PopupContentV2 request received")
        system.actorSelection("/user/Master/EmailWorker") ? PopupContentV2(params("process"),params("model"),params("code"))
      }
    }
  }

  get("/ARS/EmailImage/:prefix/:fname") {
    new AsyncResult() {
      override val is: Future[_] = {
        log.info(s"EmailImage request received")
        system.actorSelection("/user/Master/EmailWorker") ? EmailImage(params("prefix"),params("fname"))
      }
    }
  }

  get("/ARS/SnapShotImage/:eqpid/:crtime") {
    new AsyncResult() {
      override val is: Future[_] = {
        log.info(s"SnapShotImage request received")
        system.actorSelection("/user/Master/EmailWorker") ? SnapShotImage(params("eqpid"),params("crtime"))
      }
    }
  }

  // 하위 호환용 별칭 경로: 과거에 발송된 메일의 스냅샷 링크는 소문자 표기
  // (/ARS/SnapshotImage/...)로 되어 있다. URL 경로는 대소문자를 구분하므로
  // 위 SnapShotImage 경로만으로는 옛 링크가 404가 된다. 같은 핸들러로 연결해
  // 기존 메일과 신규 메일 링크를 모두 처리한다.
  get("/ARS/SnapshotImage/:eqpid/:crtime") {
    new AsyncResult() {
      override val is: Future[_] = {
        log.info(s"SnapshotImage (legacy alias) request received")
        system.actorSelection("/user/Master/EmailWorker") ? SnapShotImage(params("eqpid"),params("crtime"))
      }
    }
  }

  get("/ARS/History/:hostname/:txn") { //EQP_AUTO_RECOVERY unique 추가로 인해 'code' 항목을 추가해야 함. Get하는 Client 도 함께 수정할 필요가 있음
    new AsyncResult() {
      override val is: Future[_] = {
        log.info(s"Query History request received: ${params("hostname")}, ${params("txn")}")
        system.actorSelection("/user/Master/HttpWorker") ? QueryHistory(params("hostname"),params("txn").toLong)
      }
    }
  }

  post("/EmailNotify") {
    new AsyncResult() {
      override val is: Future[_] = {
        log.info("Email request received")
        system.actorSelection("/user/Master/EmailWorker") ? SendEmail(request.body)
      }
    }
  }

  post("/RTM/EmailNotify") {
    new AsyncResult() {
      override val is: Future[_] = {
        log.info("EARS RTM request received")
        system.actorSelection("/user/Master/EmailWorker") ? SendEmailForRTM(request.body)
      }
    }
  }

  post("/RecoveryEmailNotify") {
    new AsyncResult() {
      override val is: Future[_] = {
        log.info("Recovery Email request received")
        system.actorSelection("/user/Master/EmailWorker") ? SendRecoveryEmail(request.body)
      }
    }
  }

  post("/ARS/ScriptResult") {
    new AsyncResult() {
      override val is: Future[_] = {
        log.info("Script Result received")
        system.actorSelection("/user/Master/EmailWorker") ? ScriptResult(request.body)
      }
    }
  }

  post("/ARS/SaveCustomfiles") {
    new AsyncResult() {
      override val is: Future[_] = {
        log.info("SaveCustomfiles request received")
        system.actorSelection("/user/Master/HttpWorker") ? SaveCustomFile(request.body)
      }
    }
  }

  get("/ARS/Customfiles/:eqpid/:year/:month/:fname") {
    new AsyncResult() {
      override val is: Future[_] = {
        log.info("CustomFiles request received")
        system.actorSelection("/user/Master/EmailWorker") ? CustomFiles(params("eqpid"),params("year").toInt,params("month").toInt,params("fname"))
      }
    }
  }

  post("/EARS/kill") {
    system.actorSelection("/user/Master") ! ShutDown()
    Ok()
  }
}
