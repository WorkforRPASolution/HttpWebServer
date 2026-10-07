package com.sec.eeg.ars.testkit

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

import com.datastax.driver.core.{Cluster, Session}
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer

import scala.collection.JavaConverters._

/**
 * OrbStack my-cassandra-server 위의 테스트 키스페이스(hws_golden).
 * 스키마는 ARS/docker/cassandra/ars-schema.cql(운영 DESCRIBE 결과)을 직접 읽고 키스페이스 이름만 바꾼다.
 */
object CassandraGolden {
  val Keyspace = "hws_golden"
  lazy val host: String = sys.env.getOrElse("HWS_GOLDEN_CASSANDRA_HOST", "127.0.0.1")
  lazy val port: Int = sys.env.getOrElse("HWS_GOLDEN_CASSANDRA_PORT", "9042").toInt
  // 기본 계정은 ARS/docker/README.md 의 로컬 개발용 HttpWebServer 계정(운영 코드와 같은 값)이다.
  lazy val user: String = sys.env.getOrElse("HWS_GOLDEN_CASSANDRA_USER", "ars")
  lazy val password: String = sys.env.getOrElse("HWS_GOLDEN_CASSANDRA_PASSWORD", "visuallove")

  def openCluster(host: String, port: Int, user: String, password: String): Cluster =
    Cluster.builder().addContactPoint(host).withPort(port).withCredentials(user, password).build()

  /** 접속하고, 실패하면 무엇을 확인할지 알려 주는 예외를 던진다 */
  def connectOrExplain(c: Cluster, where: String): Session =
    try c.connect()
    catch {
      case e: Exception => throw new IllegalStateException(
        s"Cassandra 에 접속하지 못했다 ($where). OrbStack 의 my-cassandra-server 가 떠 있는지, " +
          "인증 설정이 ARS/docker/README.md 의 'Cassandra (compose 밖)' 절과 같은지 확인한다. 원인: " + e.getMessage, e)
    }

  private lazy val cluster: Cluster = openCluster(host, port, user, password)

  /** JVM 당 한 번: 테스트 키스페이스를 지우고 스키마 파일로 다시 만든다 */
  private lazy val admin: Session = {
    val s = connectOrExplain(cluster, s"$host:$port, 사용자 $user")
    require(Keyspace == "hws_golden", s"테스트 키스페이스 이름이 바뀌었다: $Keyspace")
    s.execute(s"DROP KEYSPACE IF EXISTS $Keyspace")
    schemaStatements().foreach(stmt => s.execute(stmt))
    s
  }

  def ensureReady(): Unit = admin

  /** ars-schema.cql 의 문장들을 테스트 키스페이스용으로 바꾼다 */
  def schemaStatements(): Seq[String] = {
    val text = new String(Files.readAllBytes(ExternalFile.cassandraSchema.toPath), UTF_8)
    text.split("\n").filterNot(_.trim.startsWith("--")).mkString("\n")
      .split(";").map(_.trim).filter(_.nonEmpty).toSeq
      .map(_.replace("KEYSPACE IF NOT EXISTS ars", s"KEYSPACE IF NOT EXISTS $Keyspace")
        .replace("TABLE IF NOT EXISTS ars.", s"TABLE IF NOT EXISTS $Keyspace."))
  }

  def tables: Seq[String] = {
    ensureReady()
    cluster.getMetadata.getKeyspace(Keyspace).getTables.asScala.map(_.getName).toSeq.sorted
  }

  /** 모든 테이블의 모든 파티션을 지운다. TRUNCATE 는 공유 컨테이너에 스냅샷을 쌓으므로 쓰지 않는다. */
  def clear(): Unit =
    for (t <- tables) {
      val pk = cluster.getMetadata.getKeyspace(Keyspace).getTable(t).getPartitionKey.asScala.map(_.getName)
      val keys = admin.execute(s"SELECT DISTINCT ${pk.mkString(", ")} FROM $Keyspace.$t").all().asScala
      keys.foreach { k =>
        admin.execute(s"DELETE FROM $Keyspace.$t WHERE " + pk.map(_ + " = ?").mkString(" AND "), pk.map(n => k.getObject(n)): _*)
      }
    }

  /** 테이블 -> INSERT JSON 문자열들 */
  def seed(rows: Map[String, Seq[String]]): Unit =
    rows.foreach { case (t, jsons) => jsons.foreach(j => admin.execute(s"INSERT INTO $Keyspace.$t JSON ?", j)) }

  /** 테이블 -> 정렬된 행 표기 */
  def snapshot(): Map[String, Seq[String]] =
    tables.map { t =>
      t -> admin.execute(s"SELECT * FROM $Keyspace.$t").all().asScala.toSeq.map { r =>
        Canonical.value(r.getColumnDefinitions.asList.asScala.map(d => d.getName -> r.getObject(d.getName)).toMap)
      }.sorted
    }.toMap

  /** emailsnapshot 의 timestamp 값들 (스냅샷 시각 정규화용) */
  def emailSnapshotTimes(): Set[Long] =
    admin.execute(s"SELECT timestamp FROM $Keyspace.emailsnapshot").all().asScala.map(_.getLong("timestamp")).toSet

  /** 운영 코드의 connect("ars") 를 테스트 키스페이스로 돌리는 Cluster (Mockito spy) */
  def redirectingCluster(): Cluster = {
    ensureReady()
    val spy = Mockito.mock(classOf[Cluster],
      Mockito.withSettings().spiedInstance(cluster).defaultAnswer(Mockito.CALLS_REAL_METHODS))
    Mockito.doAnswer(new Answer[Session] {
      def answer(i: InvocationOnMock): Session = cluster.connect(Keyspace)
    }).when(spy).connect("ars")
    spy
  }
}
