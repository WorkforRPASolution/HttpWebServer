package com.sec.eeg.ars.actor

import java.nio.ByteBuffer

import akka.actor.Actor
import com.datastax.driver.core.exceptions.DriverException
import com.datastax.driver.core.{BoundStatement, Cluster, PreparedStatement, Session}
import com.sec.eeg.ars.data.{ARSHttpDataFormat, CustomFilesFormat, HttpResponse, JsonInterfaces}
import com.sec.eeg.ars.utils.ImageUtils
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods.parse
import org.scalatra.InternalServerError
import org.slf4j.LoggerFactory
import sun.misc.BASE64Decoder

case class AddHistory(body: String)
case class SaveCustomsFile(body: String)
case class QueryHistory(eqpid: String, txn: Long)

class HttpWorker(cassandraConnection: Cluster) extends Actor {
  implicit val formats = DefaultFormats
  private val log = LoggerFactory.getLogger(classOf[HttpWorker])
  var session : Session = _
  var addHistory_ps : PreparedStatement = _
  var addCustomFile_ps : PreparedStatement = _

  override def preStart() : Unit = {
    session = cassandraConnection.connect("ars")
    val query = "insert into historylog(eqpid,txn,step,body) values(?,?,?,?)"
    val add_custom_query = "insert into customfiles(eqpid,year,month,fname,body) values(?,?,?,?,?)"
    addHistory_ps = session.prepare(query)
    addCustomFile_ps = session.prepare(add_custom_query)
    log.info(s"HttpWorker PreStart")
  }

  override def postStop() : Unit = {
    if (session != null) {
      session.close()
    }
    log.info(s"HttpWorker postStop")
  }

  override def receive : Receive = {
    case SaveCustomsFile(body) =>
      try{
        val data = parse(body)
        val conv = data.extract[CustomFilesFormat]

        val decoder = new BASE64Decoder

        //conv.fname 이 null 및 확장자가 없을 경우에 대해 대응 코드
        //val fileExt = conv.fname.substring(conv.fname.lastIndexOf("."),conv.fname.length)
        val fileNameOrg = Option(conv.fname).getOrElse("")
        val dotIndex = fileNameOrg.lastIndexOf(".")
        val fileExt =
          fileNameOrg.split('.').lastOption match {
            case Some(ext) if fileNameOrg.contains(".") => "." + ext.toLowerCase
            case _ => ""
          }

        var fileByte = decoder.decodeBuffer(conv.contents)
        var fileName = conv.fname
        if(fileExt == ".tif" || fileExt == ".tiff"){
          fileByte = ImageUtils.convertTiffBytesToJpegBytes(fileByte)
          fileName = fileNameOrg.substring(0, dotIndex) + ".jpeg"
        }

        log.info(s"file size: ${fileByte.length}")
        val boundStatement = new BoundStatement(addCustomFile_ps)
        val result = session.execute(boundStatement.bind(conv.hostname,Int.box(conv.year),Int.box(conv.month),fileName,ByteBuffer.wrap(fileByte)))

        sender() ! JsonInterfaces.toJson(HttpResponse("Success", ""))
      }
      catch {
        case ex: DriverException => log.warn(s"Cassandra driver exception:${ex.getMessage}")
          sender() ! JsonInterfaces.toJson(HttpResponse("Failed", ex.getMessage))
          throw ex
        case ex: Throwable => log.warn(s"Exception:${ex.getMessage}")
          sender() ! JsonInterfaces.toJson(HttpResponse("Failed", ex.getMessage))
      }

    case AddHistory(body) =>
      try{
        val data = parse(body)
        val conv = data.extract[ARSHttpDataFormat]
        val addString = s"""<table bgColor=#308cfc><tbody><tr><th><strong>${conv.step}.${conv.text}</strong><img width="15" height="15" src="data:Image/png;base64,${conv.refimage}" class="magnify" border="2" data-magnifyto="${conv.refimageWidth}" data-magnifyby="${conv.refimageHeight}" /></th></tr><tr><td bgColor=white height=100 align=center><img src="data:Image/png;base64,${conv.image}" class="magnify" border="2" width="150" max-height="100" data-magnifyto="${conv.imageWidth}" data-magnifyby="${conv.imageHeight}" /></td></tr></tbody></table><br>"""

        val boundStatement = new BoundStatement(addHistory_ps)
        val result = session.execute(boundStatement.bind(conv.hostname,Long.box(conv.txn),Int.box(conv.step),addString))

        sender() ! JsonInterfaces.toJson(HttpResponse("Success", ""))
      }
      catch {
        case ex: DriverException => log.warn(s"Cassandra driver exception:${ex.getMessage}")
          sender() ! JsonInterfaces.toJson(HttpResponse("Failed", ex.getMessage))
          throw ex
        case ex: Throwable => log.warn(s"Exception:${ex.getMessage}")
          sender() ! JsonInterfaces.toJson(HttpResponse("Failed", ex.getMessage))
      }

    case QueryHistory(eqpid,txn) =>
      try{
        val query = s"select body from historylog where eqpid = '${eqpid}' and txn = ${txn};"
        var retString = ""
        val result = session.execute(query)
        while (result.iterator().hasNext){
          val data = result.iterator().next()
          retString += data.getString("body")
        }
        if(retString == "")
          sender() ! InternalServerError("There is no data")
        else
          sender() ! html.history.render(retString)
      }
      catch {
        case ex: DriverException => log.warn(s"Cassandra driver exception:${ex.getMessage}")
          sender() ! JsonInterfaces.toJson(HttpResponse("Failed", ex.getMessage))
          throw ex
        case ex: Throwable => log.warn(s"Exception:${ex.getMessage}")
          sender() ! JsonInterfaces.toJson(HttpResponse("Failed", ex.getMessage))
      }

    case _ =>
  }
}
