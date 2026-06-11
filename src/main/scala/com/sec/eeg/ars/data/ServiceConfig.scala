package com.sec.eeg.ars.data

import java.io.FileReader
import java.util.Properties

import com.mongodb.client.MongoDatabase
import com.typesafe.config.Config
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.language.postfixOps

object ServiceConfig {
  private val logger = LoggerFactory.getLogger(ServiceConfig.toString)
  val root_dir = System.getProperty("EEG_BASE")
  val tmp_dir = System.getProperty("java.io.tmpdir")
  val AkkaSystem = "EARSSystem"
  val serviceName = "HttpWebServer"
  val ZookeeperNodePath : String = "/EARS/HttpWebServer"
  var ZookeeperQuorum : String = ""
  var MyServiceAddress : String = ""
  var ServicePublicAddress : String = ""
  var CassandraAddress: String = _
  var CassandraAuthenticationEnable = true
  var RedisSentinelConnection : String = ""
  val HttpWebServerRedisKey: String = "HttpWebServerInfo"
  var EmailTemplateImportLocation: String = _
  var PopupTemplateImportLocation: String = _
  var EmailWorkerCount = 5
  var HttpWorkerCount = 5
  var Version = "unknown"
  var IamLeader = false
  var DatabaseTimeout : FiniteDuration = 1 minutes
  var RedisPingInterval : FiniteDuration = null

  var HttpPort : Int = 8080
  var VirtualPublicIpAddress = ""

  var conf : Config = _
  var MongoDBUrl = ""

  var database : MongoDatabase = null

  def load: Boolean = {
    val parser = new JSONParser
    val confjson = parser.parse(new FileReader(root_dir + s"/conf/${serviceName}/${serviceName}.json")).asInstanceOf[JSONObject]

    val _httpPort = System.getenv("HTTP_PORT")
    if(_httpPort == null || _httpPort.trim == ""){
      System.exit(1)
    }
    HttpPort = _httpPort.toInt

    try{
      ZookeeperQuorum = confjson.get("ZookeeperQuorum").asInstanceOf[String]
    }catch {
      case ex : Throwable =>
        if(ZookeeperQuorum == null || ZookeeperQuorum.trim == ""){
          System.exit(4)
        }
    }

    try{
      VirtualPublicIpAddress = confjson.get("VirtualPublicIpAddress").asInstanceOf[String]
    }catch {
      case ex : Throwable =>
        if(VirtualPublicIpAddress == null || VirtualPublicIpAddress.trim == ""){
          System.exit(1)
        }
    }

    val _MyServiceAddress = System.getenv("HOST_IP")
    if(_MyServiceAddress == null || _MyServiceAddress.trim == ""){
      System.exit(5)
    }

    MyServiceAddress = s"${_MyServiceAddress}:${HttpPort}"
    ServicePublicAddress = s"${VirtualPublicIpAddress}:${HttpPort}"

    try{
      val _RedisPingInterval = Duration(confjson.get("RedisPingInterval").asInstanceOf[String])
      if(_RedisPingInterval != null){
        RedisPingInterval = FiniteDuration(_RedisPingInterval.length, _RedisPingInterval.unit)
      }
    }
    catch {
      case _ : Throwable => RedisPingInterval = 20 seconds
    }

    try{
      RedisSentinelConnection = confjson.get("RedisSentinelConnection").asInstanceOf[String]
    }catch {
      case ex : Throwable =>
        if(RedisSentinelConnection == null || RedisSentinelConnection.trim == ""){
          System.exit(6)
        }
    }

    try{
      CassandraAddress = confjson.get("CassandraAddress").asInstanceOf[String]
    }catch {
      case ex : Throwable =>
        if(CassandraAddress == null || CassandraAddress.trim == ""){
          System.exit(7)
        }
    }

    try {
      EmailWorkerCount = confjson.get("EmailWorkerCount").asInstanceOf[Long].toInt
      if(EmailWorkerCount == 0)
        EmailWorkerCount = 5
    } catch {
      case _ : Throwable =>
      EmailWorkerCount = 5
    }

    try {
      HttpWorkerCount = confjson.get("HttpWorkerCount").asInstanceOf[Long].toInt
      if(HttpWorkerCount == 0)
        HttpWorkerCount = 5
    } catch {
      case _ : Throwable =>
        HttpWorkerCount = 5
    }

    EmailTemplateImportLocation = confjson.get("EmailTemplateImportLocation").asInstanceOf[String]
    if(EmailTemplateImportLocation == null || EmailTemplateImportLocation.trim == "")
      throw new Exception("EmailTemplateImportLocation config is empty")

    PopupTemplateImportLocation = confjson.get("PopupTemplateImportLocation").asInstanceOf[String]
    if(PopupTemplateImportLocation == null || PopupTemplateImportLocation.trim == "")
      throw new Exception("PopupTemplateImportLocation config is empty")

    try{
      CassandraAuthenticationEnable = confjson.get("CassandraAuthenticationEnable").asInstanceOf[Boolean]
    }catch {
      case _ : Throwable =>
    }

    MongoDBUrl = confjson.get("MongoDBUrl").asInstanceOf[String]
    if(MongoDBUrl == null || MongoDBUrl.trim == ""){
      throw new Exception("Invalid MongoDB Url")
    }

    getVersion

    true
  }

  def getVersion = {
    val is = this.getClass.getClassLoader().getResourceAsStream("META-INF/maven/com.sec.eeg.ars/HttpWebServer/pom.properties")

    if (is != null) {
      val p = new Properties()
      p.load(is)
      Version = p.getProperty("version", "")
    }
    if (Version == null || Version == "") {
      val aPackage = getClass.getPackage
      if (aPackage != null) {
        Version = aPackage.getImplementationVersion
        if (Version == null || Version == "") {
          Version = aPackage.getSpecificationVersion
        }
      }
    }
    if (Version == null)
      Version = "unknown"

    logger.info(s"program release version: ${Version}")
  }
}
