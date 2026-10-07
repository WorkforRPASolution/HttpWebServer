package com.sec.eeg.ars.actor

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.{Files, Paths}
import akka.actor.Actor
import com.datastax.driver.core.exceptions.DriverException
import com.datastax.driver.core.{Session => _, _}
import com.mongodb.BasicDBObject
import com.mongodb.client.model.UpdateOptions
import com.sec.eeg.ars.data._
import com.typesafe.config.Config
import org.apache.commons.io.FileUtils
import org.bson.{BsonDocument, Document}
import org.joda.time.format.ISODateTimeFormat
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.slf4j.LoggerFactory
import sun.misc.BASE64Decoder

import scala.collection.JavaConverters._
import scala.concurrent.Await

case class SendEmail(body: String)
case class SendEmailForRTM(body: String)
case class SendRecoveryEmail(body: String)
case class LoadEmailTemplate()
case class LoadPopupTemplate()
case class EmailImage(prefix:String, fname:String)
case class SnapShotImage(eqpid:String, crtime:String)
case class CustomFiles(eqpid:String, year: Int, month: Int, fname: String)
case class ScriptResult(body: String)
case class PopupContent(process: String, model: String, code: String)
case class PopupContentV2(process: String, model: String, code: String)

class EmailWorker(conf: Config, cassandraConnection: Cluster) extends Actor {
  implicit val formats = DefaultFormats
  private val log = LoggerFactory.getLogger(classOf[EmailWorker])
  var session : com.datastax.driver.core.Session = _
  var emailsnapshot_ps : PreparedStatement = _
  val UTF8_BOM = "\uFEFF"

  var defaultEmailBodyTemplate : String = ""

  private def saveSnapshot(eqpid: String, img: String) : Long = {
    var ret : Long = 0
    try {
      val crtime = System.currentTimeMillis()
      val decoder = new BASE64Decoder
      val imageByte = decoder.decodeBuffer(img)
      log.debug(s"Image size: ${imageByte.length}")
      val boundStatement = new BoundStatement(emailsnapshot_ps)
      val result = session.execute(boundStatement.bind(eqpid, Long.box(crtime), ByteBuffer.wrap(imageByte)))
      log.info(s"new email snapshot inserted: ${eqpid}-${crtime}")
      ret = crtime
    }
    catch {
      case ex : Throwable =>
        log.error(s"saveSnapshot failed", ex)
        ret = 0
    }

    return ret
  }

  private def getRecoveryEmailBody() : String = {
    var retTPL = ""
    try{
      val emailTempl_table = ServiceConfig.database.getCollection("EMAIL_TEMPLATE_REPOSITORY")
      val search = new BasicDBObject().append("process","all").append("model","all").append("code","RecoveryDefault").append("subcode","_")
      val searchRet = emailTempl_table.find(search).asScala
      if (searchRet.size == 1) {
        retTPL = searchRet.head.getString("html")
      }
    }
    catch {
      case ex : Throwable =>
    }

    return retTPL
  }

  private def getEmailBody(app: String, process: String, model: String, code: String, subcode: String) : (String,String) = {
    var retTPL : (String,String) = null
    try {
      val emailTempl_table = ServiceConfig.database.getCollection("EMAIL_TEMPLATE_REPOSITORY")
      val search1 = new BasicDBObject().append("process",process).append("model",model).append("code",code).append("subcode",subcode)
      val searchRet1 = emailTempl_table.find(search1).asScala
      if (searchRet1.size == 1) {
        retTPL = (searchRet1.head.getString("title"), searchRet1.head.getString("html"))
      } else {
        val search2 = new BasicDBObject().append("process",process).append("model",model).append("code",code).append("subcode","_")
        val searchRet2 = emailTempl_table.find(search2).asScala
        if (searchRet2.size == 1) {
          retTPL = (searchRet2.head.getString("title"), searchRet2.head.getString("html"))
        } else {
          val search3 = new BasicDBObject().append("process","all").append("model","all").append("code","_").append("subcode","_")
          val searchRet3 = emailTempl_table.find(search3).asScala
          if (searchRet3.size == 1) {
            retTPL = (searchRet3.head.getString("title"), searchRet3.head.getString("html"))
          }
        }
      }
    }
    catch {
      case ex : Throwable =>
    }

    return retTPL
  }

  def getLineDescription(line: String) : String = {
    var lineDesc = ""
    val lineMap_table = ServiceConfig.database.getCollection("EQP_LINE_MAP")
    val search = new BasicDBObject().append("line",line)
    val searchRet = lineMap_table.find(search).asScala
    if(searchRet.size == 1){
      lineDesc = searchRet.head.getString("lineDesc")
    }else{
      lineDesc = line
    }
    lineDesc
  }

  //3.0.8
  def updateAutoRecovery(process: String, model: String, eqpid: String, line: String, code: String, txn: Long, params: Map[String,String], triggeredBy: String, status: String) : Unit = {
    try {
      val retry = "0/0/0"
      val create_date = ISODateTimeFormat.dateTime().print(txn)

      val recovery_table = ServiceConfig.database.getCollection("EQP_AUTO_RECOVERY")

      val params_doc = params.foldLeft(new Document())((doc, e) => doc.append(e._1, e._2))
      val newDoc = new Document().append("process", process).append("model",model).append("line",line).append("eqpid",eqpid).append("txn_seq",txn)
        .append("status",status).append("ears_code",code).append("retry",retry).append("trigger_by",triggeredBy).append("create_date",create_date).append("params",params_doc)
      recovery_table.insertOne(newDoc)
    } catch {
      case ex: Throwable =>
        log.error(s"Insert EQP_AUTO_RECOVERY failed", ex)
    }
  }

  override def preStart() : Unit = {
    session = cassandraConnection.connect("ars")
    val query = "insert into emailsnapshot(eqpid,timestamp,body) values(?,?,?)"
    emailsnapshot_ps = session.prepare(query)

    defaultEmailBodyTemplate = getRecoveryEmailBody()

    log.info("EmailWorker PreStart")
  }

  override def postStop() : Unit = {
    if (session != null){
      session.close()
    }
    log.info("EmailWorker postStop")
  }

  def getEmailNotificationCategory(process: String, model: String, code: String) : Int = {
    try {
      val emailNotificationMeta_table = ServiceConfig.database.getCollection("EMAIL_NOTIFICATION_META")
      val search = new BasicDBObject().append("process",process).append("model",model).append("code",code)
      val searchRet = emailNotificationMeta_table.find(search).asScala
      if (searchRet.size == 1) {
        searchRet.head.getLong("category").toInt
      } else {
        -1
      }
    } catch {
      case ex : Throwable =>
        log.warn(s"getEmailNotificationCategory failed: ${process}, ${model}, ${code}")
        -2
    }
  }

  def getEmailCategory(process: String, model: String, eqpid: String, code: String, line: String) : String = {
    var _emailCategory = ""

    try {
      val emailRecip_table = ServiceConfig.database.getCollection("EMAIL_RECIPIENTS")

      val search = new BasicDBObject().append("app","ARS").append("code",code)
      val projection = new BasicDBObject().append("emailCategory",true).append("process",true).append("model",true).append("line",true).append("_id",false)
      val searchRet = emailRecip_table.find(search).projection(projection).asScala

      val searchResult1 = searchRet.find(x => {x.getString("process") == process && x.getString("model") == model && x.getString("line") == line})
      val searchResult2 = searchRet.find(x => {x.getString("process") == process && x.getString("model") == model && x.getString("line") == "all"})
      //3.0.8-model, process all case 추가
      val searchResult3 = searchRet.find(x => {x.getString("process") == process && x.getString("model") == "all" && x.getString("line") == "all"})
      val searchResult4 = searchRet.find(x => {x.getString("process") == "all" && x.getString("model") == "all" && x.getString("line") == "all"})

      if (searchResult1.size == 1) {
        _emailCategory = searchResult1.head.getString("emailCategory")
      }
      else if (searchResult2.size == 1) {
        _emailCategory = searchResult2.head.getString("emailCategory")
      }
      else if (searchResult3.size == 1) {
        _emailCategory = searchResult3.head.getString("emailCategory")
      }
      else if (searchResult4.size == 1) {
        _emailCategory = searchResult4.head.getString("emailCategory")
      }
      else {
        val eqpinfo_table = ServiceConfig.database.getCollection("EQP_INFO")
        val search = new BasicDBObject().append("eqpId",eqpid)
        val projection = new BasicDBObject().append("emailcategory",true).append("_id",false)
        val searchRet = eqpinfo_table.find(search).projection(projection).limit(1).asScala
        if (searchRet.size == 1) {
          _emailCategory = searchRet.head.getString("emailcategory")
        }
      }
      log.info(s"email recipients category: ${_emailCategory}")

    } catch {
      case ex : Throwable =>
        log.warn(s"getEmailCategory failed: ${process}, ${model}, ${code}, ${ex.getMessage}")
    }
    _emailCategory
  }

  //3.0.8-Script(CBS) 기준정보가  SC_PROPERTY 에 추가됨에 따라 Mail 발송여부를 DB 에서 판단
  def isScriptResultEmailDisabled(process: String, model: String, scname: String, isSuccess: Boolean) : Boolean = {
    try {
      val scPropertyTable = ServiceConfig.database.getCollection("SC_PROPERTY")
      val search = new BasicDBObject().append("process",process).append("eqpModel",model).append("scname",scname)
      val jsonString = scPropertyTable.find(search).asScala.head.toJson
      val json = parse(jsonString)
      if(isSuccess){
        json \ "property" \ "DoNotSendEmailWhenSuccess" match {
          case JBool(true) => return true
          case JBool(false) => return false
        }
      }
      else {
        json \ "property" \ "DoNotSendEmailWhenFail" match {
          case JBool(true) => return true
          case JBool(false) => return false
        }
      }
      false
    }catch {
      case ex : Throwable =>
        log.warn(s"getScProperty failed: ${process}, ${model}, ${scname}, ${ex.getMessage}")
        false
    }
  }

  //3.0.8-category(분임조)
  def getSdwt(eqpid: String) : String = {
    try {
      var retSdwt = ""
      val eqpinfo_table = ServiceConfig.database.getCollection("EQP_INFO")
      val search = new BasicDBObject().append("eqpId", eqpid)
      val projection = new BasicDBObject().append("category", true).append("_id", false)
      val searchRet = eqpinfo_table.find(search).projection(projection).limit(1).asScala

      if (searchRet.size == 1) {
        retSdwt = searchRet.head.getString("category")
        log.info(s"eqpid category: ${retSdwt}")
      }
      retSdwt
    } catch {
      case ex : Throwable =>
        log.warn(s"getSdwt failed: $eqpid, ${ex.getMessage}")
        ""
    }
  }

  override def receive : Receive = {
    case EmailImage(prefix,fname) =>
      try{
        val emailImage_table = ServiceConfig.database.getCollection("EMAIL_IMAGE_REPOSITORY", classOf[BsonDocument])
        val search = new BasicDBObject().append("prefix",prefix).append("name",fname)
        val searchRet = emailImage_table.find(search).asScala
        if(searchRet.size == 1){
          sender() ! searchRet.head.getBinary("body").getData
        }else{
          sender() ! Array.empty[Byte]
        }
      }
      catch {
        case ex : Throwable => log.warn(s"Get EmailImage failed: ${ex.getMessage}")
          sender() ! Array.empty[Byte]
      }

    case PopupContent(process,model,code) =>
      try{
        val popupTempl_table = ServiceConfig.database.getCollection("POPUP_TEMPLATE_REPOSITORY")
        val search = new BasicDBObject().append("process",process).append("model",model).append("code",code)
        val searchRet = popupTempl_table.find(search).asScala
        if(searchRet.size ==1 ){
          sender() ! searchRet.head.getString("html")
        }else{
          sender() ! Array.empty[Byte]
        }
      }
      catch {
        case ex : Throwable => log.warn(s"Get PopupContent(${process},${model},${code}) failed: ${ex.getMessage}")
          sender() ! Array.empty[Byte]
      }

    case PopupContentV2(process,model,code) =>
      try{
        val popupTempl_table = ServiceConfig.database.getCollection("POPUP_TEMPLATE_REPOSITORY")
        val search = new BasicDBObject().append("process",process).append("model",model).append("code",code)
        val searchRet = popupTempl_table.find(search).asScala
        if(searchRet.size == 1){
          val html = searchRet.head.getString("html")
          val noSendEmail = Option(searchRet.head.getBoolean("noSendEmail")).getOrElse(false)
          sender() ! s"$html,${noSendEmail.toString}"
        }else{
          sender() ! Array.empty[Byte]
        }
      }
      catch {
        case ex : Throwable => log.warn(s"Get PopupContentV2(${process},${model},${code}) failed: ${ex.getMessage}")
          sender() ! Array.empty[Byte]
      }

    case ScriptResult(body) =>
      try{
        val data = parse(body)
        val conv = data.extract[ScriptResultFormat]

        if(isScriptResultEmailDisabled(conv.process, conv.model, conv.scname, conv.success)){
          log.info(s"${conv.process}-${conv.model}-${conv.hostname}, script: ${conv.scname}, success: ${conv.success.toString},,,, mailing is disabled.")
          sender() ! Array.empty[Byte]
        }
        else {
          val category = getEmailNotificationCategory(conv.process, conv.model, conv.scname)
          var mailTitlePrefix = "[EARS]"

          if (conv.success) {
            mailTitlePrefix = s"${mailTitlePrefix}[Script 성공]"
            log.info(s"Script Success - process: ${conv.process}, line: ${conv.line}, model: ${conv.model}, eqpid: ${conv.hostname}, script: ${conv.scname}, category: ${category}")
          } else {
            mailTitlePrefix = s"${mailTitlePrefix}[Script 실패]"
            log.info(s"Script Fail - process: ${conv.process}, line: ${conv.line}, model: ${conv.model}, eqpid: ${conv.hostname}, script: ${conv.scname}, category: ${category}")
          }

          // Update MongoDB - Script Result
          /* Data 다량 발생으로 일단 주석처리
          val curTS = System.currentTimeMillis()
          val status = if (conv.success) "Success" else "Failed"
          val triggeredBy = if(conv.variables.contains("@Trigger")) conv.variables("@Trigger") else "Unknown"

          updateAutoRecovery(conv.process, conv.model, conv.hostname, conv.line, conv.scname, curTS, conv.variables, triggeredBy, status)
          */

          val subcode = "_"

          val emailTemplate = getEmailBody("ARS", conv.process, conv.model, conv.scname, subcode)
          if (emailTemplate != null) {
            var retString = emailTemplate._2
            var emailtitle = emailTemplate._1
            retString = retString.replaceAll("@Hostname", conv.hostname)
            retString = retString.replaceAll("@Process", conv.process)
            retString = retString.replaceAll("@Model", conv.model)
            retString = retString.replaceAll("@IP", conv.ip)
            retString = retString.replaceAll("@Line", getLineDescription(conv.line))
            retString = retString.replaceAll("@CODE", conv.scname)
            if (retString.contains("@Sdwt")) {
              retString = retString.replaceAll("@Sdwt", getSdwt(conv.hostname))
            }
            conv.variables.foreach( p => {
              log.info(s"variables - ${p._1}:${p._2}")
              retString = retString.replaceAll(s"@${p._1}", java.util.regex.Matcher.quoteReplacement(s"${p._2}"))
            })
            retString = retString.replaceAll("@HttpWebServerAddress", s"${ServiceConfig.ServicePublicAddress}")

            val _emailCategory = getEmailCategory(conv.process, conv.model, conv.hostname, conv.scname, conv.line)

            if (_emailCategory != "") {
              log.info(s"Send script result to ${_emailCategory}: ${mailTitlePrefix}[${emailtitle}][${conv.hostname}]${conv.scname}")
              context.actorSelection("/user/Master/RedisActor") ! EmailFormat("ARS", _emailCategory, s"${mailTitlePrefix}[${emailtitle}][${conv.hostname}]${conv.scname}:${retString}")
              sender() ! JsonInterfaces.toJson(HttpResponse("Success", ""))
            }
            else {
              sender() ! JsonInterfaces.toJson(HttpResponse("Fail", "There is no email category"))
            }
          }
          else {
            sender() ! JsonInterfaces.toJson(HttpResponse("Fail", "There is no email template"))
          }
        }
      }
      catch {
        case ex : Throwable => log.warn(s"Send ScriptResult failed: ${ex.getMessage}")
          sender() ! JsonInterfaces.toJson(HttpResponse("Fail", ex.getMessage))
      }

    case CustomFiles(eqpid,year,month,fname) =>
      val query = s"select body from customfiles where eqpid = '${eqpid}' and year = ${year} and month = ${month} and fname = '${fname}';"
      log.info(s"get CustomFiles: ${eqpid}, ${year}, ${month}, ${fname}")
      var retContents : ByteBuffer = null
      try{
        val result = session.execute(query)
        if(result.iterator().hasNext){
          retContents = result.iterator().next().getBytes("body")
        }

        if(retContents == null){
          log.info(s"There is no custom files: ${eqpid}, ${year}, ${month}, ${fname}")
          sender() ! Array.empty[Byte]
        }
        else{
          sender() ! retContents.array()
        }
      }
      catch {
        case ex: DriverException =>
          log.warn(s"Cassandra driver exception:${ex.getMessage}")
          sender() ! Array.empty[Byte]
          throw ex
        case ex: Throwable => log.warn(s"Get CustomFiles failed: ${ex.getMessage}")
          sender() ! Array.empty[Byte]
      }

    case SnapShotImage(eqpid,crtime) =>
      val query = s"select body from emailsnapshot where eqpid = '${eqpid}' and timestamp = ${crtime};"
      log.info(s"get snapshot: ${eqpid}-${crtime}")
      var retImg : ByteBuffer = null
      try{
        val result = session.execute(query)
        if(result.iterator().hasNext){
          retImg = result.iterator().next().getBytes("body")
        }

        if(retImg == null){
          log.info(s"There is no snapshot image: ${eqpid}-${crtime}")
          sender() ! Array.empty[Byte]
        }
        else{
          sender() ! retImg.array()
        }
      }
      catch {
        case ex: DriverException =>
          log.warn(s"Cassandra driver exception:${ex.getMessage}")
          sender() ! Array.empty[Byte]
          throw ex
        case ex: Throwable => log.warn(s"Get SnapShotImage failed: ${ex.getMessage}")
          sender() ! Array.empty[Byte]
      }

    case SendRecoveryEmail(body) =>
      try{
        implicit val formats = org.json4s.DefaultFormats
        val data = parse(body)
        val conv = data.extract[RecoveryEmailHttpDataFormat]
        log.info(s"${conv.hostname},${conv.process},${conv.model},${conv.scname},${conv.title}")

        var retString = defaultEmailBodyTemplate
        if(retString == "")
          retString = conv.body
        else
          retString = retString.replaceAll("@contents", java.util.regex.Matcher.quoteReplacement(conv.body))

        retString = retString.replaceAll("@Process", conv.process)
        retString = retString.replaceAll("@Model", conv.model)
        retString = retString.replaceAll("@Eqpid", conv.hostname)
        retString = retString.replaceAll("@Line", getLineDescription(conv.line))
        if (retString.contains("@Sdwt")) {
          retString = retString.replaceAll("@Sdwt", getSdwt(conv.hostname))
        }

        conv.variables.foreach( p => {
          if (p._1 == "__snapshot__") {
            log.info(s"snapshot received: ${p._1}")
            val crtime = saveSnapshot(conv.hostname, p._2)
            if (crtime != 0) {
              log.info(s"snapshot link: http://${ServiceConfig.ServicePublicAddress}/ARS/SnapShotImage/${conv.hostname}/${crtime}")
              retString = retString.replaceAll("@__snapshot__", s"http://${ServiceConfig.ServicePublicAddress}/ARS/SnapShotImage/${conv.hostname}/${crtime}")
            }
          } else {
            log.info(s"variables - ${p._1}:${p._2}")
            retString = retString.replaceAll(s"@${p._1}", java.util.regex.Matcher.quoteReplacement(s"${p._2}"))
          }
        })

        var _emailCategory = getEmailCategory(conv.process,conv.model,conv.hostname,conv.scname,conv.line)

        if(_emailCategory == ""){
          sender() ! JsonInterfaces.toJson(HttpResponse("Fail", "There is no email category"))
        }
        else{
          log.info(s"SendRecoveryEmail - ${_emailCategory}: ${conv.title}")
          context.actorSelection("/user/Master/RedisActor") ! EmailFormat("ARS", _emailCategory, s"${conv.title}:${retString}")
          sender() ! JsonInterfaces.toJson(HttpResponse("Success", ""))
        }
      }
      catch {
        case ex : Throwable => log.warn(s"SendRecoveryEmail failed: ${ex.getMessage}")
          sender() ! JsonInterfaces.toJson(HttpResponse("Fail", ex.getMessage))
      }

    case SendEmailForRTM(body) =>
      try {
        val data = parse(body)
        val conv = data.extract[EARSRTMEmailHttpDataFormat]

        var category = getEmailNotificationCategory(conv.process, conv.model, conv.code)

        val subcode = "_"
        log.info(s"EmailNotify logging - process: ${conv.process}, line: ${conv.line}, model: ${conv.model}, eqpid: ${conv.eqpid}, app: ARS, code: ${conv.code}, subcode: ${subcode}, category: ${category}")

        val emailTemplate = getEmailBody("ARS", conv.process, conv.model, conv.code, subcode)
        if (emailTemplate != null){
          var retString = emailTemplate._2
          var emailtitle = emailTemplate._1
          retString = retString.replaceAll("@Hostname", conv.eqpid)
          retString = retString.replaceAll("@Process", conv.process)
          retString = retString.replaceAll("@Model", conv.model)
          retString = retString.replaceAll("@Line", getLineDescription(conv.line))
          retString = retString.replaceAll("@CODE", conv.code)
          if (retString.contains("@Sdwt")) {
            retString = retString.replaceAll("@Sdwt", getSdwt(conv.eqpid))
          }
          conv.variables.foreach( p => {
            log.info(s"variables - ${p._1}:${p._2}")
            retString = retString.replaceAll(s"@${p._1}", java.util.regex.Matcher.quoteReplacement(s"${p._2}"))
          })
          retString = retString.replaceAll("@HttpWebServerAddress", s"${ServiceConfig.ServicePublicAddress}")

          var _emailCategory = getEmailCategory(conv.process, conv.model, conv.eqpid, conv.code, conv.line)

          if (_emailCategory != "") {
            val project = "EARS"
            log.info(s"SendEmail - ARS,${_emailCategory}: [${project}][${emailtitle}][${conv.eqpid}]${conv.code}")
            context.actorSelection("/user/Master/RedisActor") ! EmailFormat("ARS", _emailCategory, s"[${project}][${emailtitle}][${conv.eqpid}]${conv.code}:${retString}")
            sender() ! JsonInterfaces.toJson(HttpResponse("Success", ""))
            log.info(s"EmailNotify - process: ${conv.process}, line: ${conv.line}, model: ${conv.model}, eqpid: ${conv.eqpid}, app: ARS, code: ${conv.code}, subcode: ${subcode}, category: ${category}")
          }
          else {
            sender() ! JsonInterfaces.toJson(HttpResponse("Fail", "There is no email category"))
          }
        }
        else {
          sender() ! JsonInterfaces.toJson(HttpResponse("Fail", "There is no email template"))
        }
      }
      catch {
        case ex : Exception =>
          log.error(s"SendEmailForRTM failed: ${ex.getMessage}")
          sender() ! JsonInterfaces.toJson(HttpResponse("Fail", ex.getMessage))
      }

    case SendEmail(body) =>
      try {
        val data = parse(body)
        val conv = data.extract[EmailHttpDataFormat]

        var category = getEmailNotificationCategory(conv.process, conv.model, conv.code)

        log.info(s"EmailNotify logging - process: ${conv.process}, line: ${conv.line}, model: ${conv.model}, eqpid: ${conv.hostname}, app: ${conv.app}, code: ${conv.code}, subcode: ${conv.subcode}, category: ${category}")
        val subcode = if(conv.subcode == "") "_" else conv.subcode

        if (conv.renderedBody.isDefined) {
          // Option C: RMS가 HTML 본문과 제목을 미리 완성해서 렌더링한 경우다.
          // 그 값을 그대로 사용하고 @HttpWebServerAddress 토큰만 치환한다.
          // DB 템플릿을 조회하지 않으므로, 템플릿 행이 없어서 발송이 실패하는 일이 없다.
          // 수신자 라우팅(getEmailCategory)은 기존과 동일하게 유지된다(regression #5).
          val (emailtitle, retString) = EmailBodyResolver.resolve(
            conv.renderedBody, conv.title, ServiceConfig.ServicePublicAddress, ("", ""))
          // codeForRedis는 Redis 메시지에 표시되는 코드 문자열일 뿐이다. 아래의 수신자
          // 라우팅은 conv.code를 직접 사용한다(legacy 분기와 동일).
          val codeForRedis = if (subcode == "_") s"${conv.code}" else s"${conv.code}-${conv.subcode}"
          // 수신자 카테고리: emailCategory 직접지정이 있으면 역산(getEmailCategory) 생략.
          // 제목 헤드라인: displayId(그룹 식별자)가 있으면 hostname 대신 사용.
          val (_emailCategory, headline) = EmailRoutingResolver.resolve(
            conv.emailCategory, conv.displayId, conv.hostname,
            getEmailCategory(conv.process, conv.model, conv.hostname, conv.code, conv.line))
          if (_emailCategory != "") {
            val project = if (conv.app.contains("ARS")) "EARS" else conv.app
            log.info(s"SendEmail(renderedBody) - ${conv.app},${_emailCategory}: [${project}][${emailtitle}][${headline}][${codeForRedis}]")
            context.actorSelection("/user/Master/RedisActor") ! EmailFormat(conv.app, _emailCategory, s"[${project}][${emailtitle}][${headline}][${codeForRedis}]:${retString}")
            sender() ! JsonInterfaces.toJson(HttpResponse("Success", ""))
            log.info(s"EmailNotify(renderedBody) - process: ${conv.process}, line: ${conv.line}, model: ${conv.model}, eqpid: ${conv.hostname}, app: ${conv.app}, code: ${conv.code}, subcode: ${conv.subcode}, category: ${category}")
          }
          else {
            sender() ! JsonInterfaces.toJson(HttpResponse("Fail", "There is no email category"))
          }
        }
        else {
          // ===== legacy 템플릿 경로 — Option C 도입 전 원본 코드와 한 글자도 다르지 않게 유지 =====
          val emailTemplate = getEmailBody(conv.app, conv.process, conv.model, conv.code, subcode)
          if (emailTemplate != null){
            var retString = emailTemplate._2
            var emailtitle = emailTemplate._1
            retString = retString.replaceAll("@Hostname", conv.hostname)
            retString = retString.replaceAll("@Process", conv.process)
            retString = retString.replaceAll("@Model", conv.model)
            retString = retString.replaceAll("@IP", conv.ip)
            retString = retString.replaceAll("@Line", getLineDescription(conv.line))
            if (retString.contains("@Sdwt")) {
              retString = retString.replaceAll("@Sdwt", getSdwt(conv.hostname))
            }
            var code = ""
            if (subcode == "_")
              code = s"${conv.code}"
            else
              code = s"${conv.code}-${conv.subcode}"
            retString = retString.replaceAll("@CODE", s"${code}")
            conv.variables.foreach( p => {
              if (p._1 == "__snapshot__") {
                log.info(s"snapshot received: ${p._1}")
                val crtime = saveSnapshot(conv.hostname, p._2)
                if (crtime != 0) {
                  log.info(s"snapshot link: http://${ServiceConfig.ServicePublicAddress}/ARS/SnapShotImage/${conv.hostname}/${crtime}")
                  retString = retString.replaceAll("@__snapshot__", s"http://${ServiceConfig.ServicePublicAddress}/ARS/SnapShotImage/${conv.hostname}/${crtime}")
                }
              }
              else {
                log.info(s"variables - ${p._1}:${p._2}")
                retString = retString.replaceAll(s"@${p._1}", java.util.regex.Matcher.quoteReplacement(s"${p._2}"))
              }
            })
            retString = retString.replaceAll("@__snapshot__", "")
            retString = retString.replaceAll("@HttpWebServerAddress", s"${ServiceConfig.ServicePublicAddress}")

            // 공유 계약(EmailHttpDataFormat)이므로 legacy 분기에도 동일 적용:
            // emailCategory 직접지정 시 역산 생략, displayId 시 헤드라인 분리.
            val (_emailCategory, headline) = EmailRoutingResolver.resolve(
              conv.emailCategory, conv.displayId, conv.hostname,
              getEmailCategory(conv.process, conv.model, conv.hostname, conv.code, conv.line))

            if (_emailCategory != "") {
              val project = if (conv.app.contains("ARS")) "EARS" else conv.app
              log.info(s"SendEmail - ${conv.app},${_emailCategory}: [${project}][${emailtitle}][${headline}][${code}]")
              context.actorSelection("/user/Master/RedisActor") ! EmailFormat(conv.app, _emailCategory, s"[${project}][${emailtitle}][${headline}][${code}]:${retString}")
              sender() ! JsonInterfaces.toJson(HttpResponse("Success", ""))
              log.info(s"EmailNotify - process: ${conv.process}, line: ${conv.line}, model: ${conv.model}, eqpid: ${conv.hostname}, app: ${conv.app}, code: ${conv.code}, subcode: ${conv.subcode}, category: ${category}")
            }
            else {
              sender() ! JsonInterfaces.toJson(HttpResponse("Fail", "There is no email category"))
            }
          }
          else {
            sender() ! JsonInterfaces.toJson(HttpResponse("Fail", "There is no email template"))
          }
        }
      }
      catch {
        case ex : Throwable => log.warn(s"SendEmail failed: ${ex.getMessage}")
          sender() ! JsonInterfaces.toJson(HttpResponse("Fail", ex.getMessage))
      }

    case LoadEmailTemplate() =>
      val localDir = new File(ServiceConfig.EmailTemplateImportLocation)
      if(localDir.exists() && localDir.isDirectory){
        localDir.listFiles().filter(_.isDirectory).filter(_.getName != "_workdone").foreach(
          d => {
            val dirInfos = d.getName.split('^')
            var app, process, model, code, subcode = "_"
            if((dirInfos.size == 4 || dirInfos.size == 5) && dirInfos(0) != "" && dirInfos(1) != "" && dirInfos(2) != "" && dirInfos(3) != ""){
              app = dirInfos(0)
              process = dirInfos(1)
              model = dirInfos(2)
              code = dirInfos(3)
              if(dirInfos.size == 5)
                subcode = dirInfos(4)

              val filelists = scala.collection.mutable.MutableList.empty[String]
              var htmlfile = d.listFiles.filter(_.isFile).filter(_.getName.endsWith(".html")).map(_.getName).take(1)
              if(htmlfile.length == 1){
                d.listFiles.filter(_.isFile).filter(_.getName != htmlfile.head).foreach(f => {
                  filelists += f.getName
                })
                val readFile = scala.io.Source.fromFile(s"${ServiceConfig.EmailTemplateImportLocation}/${d.getName}/${htmlfile.head}","UTF-8")
                try{
                  var contents = readFile.mkString
                  var htmlfileName = htmlfile.head.substring(0, htmlfile.head.length - 5)
                  if(htmlfileName.startsWith("index-"))
                    htmlfileName = htmlfileName.substring(6)

                  val prefix = s"${app}_${process}_${model}_${code}_${subcode}"

                  val emailImage_table = ServiceConfig.database.getCollection("EMAIL_IMAGE_REPOSITORY")

                  val search1 = new BasicDBObject().append("prefix",prefix)
                  val deleteDoc1 = emailImage_table.deleteMany(search1).getDeletedCount

                  log.info(s"delete row: ${prefix}, ${deleteDoc1}")

                  filelists.foreach( fn => {
                    val readFile = Files.readAllBytes(Paths.get(s"${ServiceConfig.EmailTemplateImportLocation}/${d.getName}/${fn}"))
                    val newDoc = new Document().append("prefix",prefix).append("name",fn).append("body", readFile)
                    emailImage_table.insertOne(newDoc)
                    contents = contents.replaceAll(s"@${fn}", s"http://@HttpWebServerAddress/ARS/EmailImage/${app}_${process}_${model}_${code}_${subcode}/${fn}")
                  })

                  val _update = new Document().append("title",htmlfileName).append("html",contents)
                  val emailTempl_table = ServiceConfig.database.getCollection("EMAIL_TEMPLATE_REPOSITORY")
                  val search2 = new BasicDBObject().append("process",process).append("model",model).append("code",code).append("subcode",subcode)
                  emailTempl_table.updateOne(search2, new Document().append("$set",_update), new UpdateOptions().upsert(true))
                }finally {
                  readFile.close()
                }
                val bfDir = new File(s"${ServiceConfig.EmailTemplateImportLocation}/${d.getName}")
                val afDir = new File(s"${ServiceConfig.EmailTemplateImportLocation}/_workdone/${d.getName}")
                if(afDir.exists())
                  FileUtils.deleteDirectory(afDir)

                FileUtils.moveDirectory(bfDir,afDir)
              }
            }
          }
        )
      }
      sender() ! JsonInterfaces.toJson(HttpResponse("Success", ""))

    case LoadPopupTemplate() =>
      val localDir = new File(ServiceConfig.PopupTemplateImportLocation)
      var retSuccess = "Success"
      var retMessage = ""
      if(localDir.exists() && localDir.isDirectory){
        localDir.listFiles().filter(_.isDirectory).filter(_.getName != "_workdone").foreach(
          d => {
            val dirInfos = d.getName.split('^')
            var process, model, code = ""
            if(dirInfos.size == 3 && dirInfos(0) != "" && dirInfos(1) != "" && dirInfos(2) != ""){
              process = dirInfos(0)
              model = dirInfos(1)
              code = dirInfos(2)

              val filelists = scala.collection.mutable.MutableList.empty[String]
              var htmlfile = d.listFiles.filter(_.isFile).filter(_.getName == "index.html").take(1)
              if(htmlfile.length == 1){
                d.listFiles.filter(_.isFile).filter(_.getName != "index.html").foreach(f => {
                  filelists += f.getName
                })
                val readFile = scala.io.Source.fromFile(s"${ServiceConfig.PopupTemplateImportLocation}/${d.getName}/index.html","UTF-8")
                var contents = readFile.mkString("")
                val prefix = s"popup_${process}_${model}_${code}"

                try{
                  val emailImage_table = ServiceConfig.database.getCollection("EMAIL_IMAGE_REPOSITORY")

                  val search1 = new BasicDBObject().append("prefix",prefix)
                  val deleteDoc1 = emailImage_table.deleteMany(search1).getDeletedCount

                  log.info(s"delete row: ${prefix}, ${deleteDoc1}")

                  filelists.foreach( fn => {
                    val readFile = Files.readAllBytes(Paths.get(s"${ServiceConfig.PopupTemplateImportLocation}/${d.getName}/${fn}"))
                    val newDoc = new Document().append("prefix",prefix).append("name",fn).append("body", readFile)
                    emailImage_table.insertOne(newDoc)
                    contents = contents.replaceAll(s"@${fn}", s"http://@HttpWebServerAddress/ARS/EmailImage/${prefix}/${fn}")
                  })
                  if(contents.startsWith(UTF8_BOM)){
                    contents = contents.substring(1)
                  }
                  log.info(s"contents: ${contents}")

                  val _update = new Document().append("html",contents)
                  val popupTempl_table = ServiceConfig.database.getCollection("POPUP_TEMPLATE_REPOSITORY")
                  val search2 = new BasicDBObject().append("process",process).append("model",model).append("code",code)
                  popupTempl_table.updateOne(search2, new Document().append("$set",_update), new UpdateOptions().upsert(true))
                }finally {
                  readFile.close()
                }
                val bfDir = new File(s"${ServiceConfig.PopupTemplateImportLocation}/${d.getName}")
                val afDir = new File(s"${ServiceConfig.PopupTemplateImportLocation}/_workdone/${d.getName}")
                if(afDir.exists())
                  FileUtils.deleteDirectory(afDir)

                FileUtils.moveDirectory(bfDir,afDir)
              }
            }
          }
        )
      }else{
        retSuccess = "Failed"
        retMessage = s"There is no directory: ${localDir.getName}"
      }
      sender() ! JsonInterfaces.toJson(HttpResponse(retSuccess, retMessage))

    case _ =>
  }
}
