package com.sec.eeg.ars.testkit

import com.mongodb.client.{MongoClient, MongoClients, MongoDatabase}
import org.bson.Document

import scala.collection.JavaConverters._

/** OrbStack mongodb-44 위의 테스트 전용 DB(HWS_GOLDEN). 다른 DB는 건드리지 않는다. */
object MongoGolden {
  val DbName = "HWS_GOLDEN"
  val DefaultUrl = "mongodb://localhost:27017/?serverSelectionTimeoutMS=3000"
  lazy val url: String = sys.env.getOrElse("HWS_GOLDEN_MONGO_URL", DefaultUrl)

  private lazy val client: MongoClient = MongoClients.create(url)

  lazy val database: MongoDatabase = {
    val db = client.getDatabase(DbName)
    ping(db, url)
    db
  }

  /** 접속을 확인하고, 실패하면 무엇을 확인할지 알려 주는 예외를 던진다 */
  def ping(db: MongoDatabase, url: String): Unit =
    try db.runCommand(new Document("ping", 1))
    catch {
      case e: Exception => throw new IllegalStateException(
        s"Mongo 에 접속하지 못했다 ($url). OrbStack 의 mongodb-44 컨테이너가 떠 있는지 확인한다 (docker ps). 원인: ${e.getMessage}", e)
    }

  private def guarded: MongoDatabase = {
    require(database.getName == DbName, s"테스트 DB 이름이 $DbName 이 아니다: ${database.getName}")
    database
  }

  def reset(): Unit = guarded.drop()

  /** 컬렉션 -> Extended JSON 문서 문자열들 */
  def seed(collections: Map[String, Seq[String]]): Unit =
    collections.foreach { case (name, docs) =>
      if (docs.nonEmpty) guarded.getCollection(name).insertMany(docs.map(d => Document.parse(d)).asJava)
    }

  /** 컬렉션 -> 정렬된 문서 표기 (_id 제외) */
  def snapshot(): Map[String, Seq[String]] = {
    val db = guarded
    db.listCollectionNames().asScala.toSeq.map { name =>
      name -> db.getCollection(name).find().asScala.toSeq.map { d =>
        d.remove("_id")
        Canonical.value(d)
      }.sorted
    }.toMap
  }
}
