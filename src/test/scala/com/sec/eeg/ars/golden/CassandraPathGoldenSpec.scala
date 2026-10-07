package com.sec.eeg.ars.golden

import com.sec.eeg.ars.testkit.GoldenSuite

/** Cassandra 를 쓰는 경로: 실행 이력, 첨부 파일, 메일 스냅샷 */
class CassandraPathGoldenSpec extends GoldenSuite("AddHistory", "QueryHistory", "SaveCustomFile", "CustomFiles", "SnapShotImage")
