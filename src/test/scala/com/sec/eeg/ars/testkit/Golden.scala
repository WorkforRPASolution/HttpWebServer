package com.sec.eeg.ars.testkit

import org.scalatest.Tag

/** OrbStack의 Mongo·Cassandra가 필요한 테스트. 기본 `mvn test`에서는 빠지고 `-Pgolden`에서만 돈다. */
object Golden extends Tag("com.sec.eeg.ars.testkit.Golden")
