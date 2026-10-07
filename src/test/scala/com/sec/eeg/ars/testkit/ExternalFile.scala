package com.sec.eeg.ars.testkit

import java.io.File

/** 저장소 밖(ARS 형제 폴더)의 파일을 찾는다. mvn 은 user.dir=프로젝트 루트로 테스트를 돌린다. */
object ExternalFile {
  def find(rel: String): File = {
    val candidates = Seq(".", "..", "../..").map(p => new File(s"$p/$rel"))
    candidates.find(_.exists()).getOrElse(throw new IllegalStateException(
      s"외부 파일을 찾지 못했다: $rel (user.dir=${sys.props("user.dir")}). 찾아본 경로: " +
        candidates.map(_.getAbsolutePath).mkString(", ")))
  }

  def rmsContract: File = find("ResourceMonitorServer/tests/data/akka_email_contract.json")

  def cassandraSchema: File = find("docker/cassandra/ars-schema.cql")
}
