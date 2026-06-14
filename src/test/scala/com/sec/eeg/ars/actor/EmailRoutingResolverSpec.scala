package com.sec.eeg.ars.actor

import org.scalatest.{FlatSpec, Matchers}

/**
 * 그룹 경보 메일의 수신자 카테고리/제목 헤드라인 선택을 EmailWorker.SendEmail 에서
 * 떼어낸 순수 스펙 (액터 / Mongo / Redis 없음). 두 갈래를 검증한다:
 *   - 수신자 카테고리: emailCategory(payload 직접지정)가 있으면 그대로 사용하고,
 *     없으면(None/빈문자열) by-name `derivedCategory`(= getEmailCategory 역산)로 폴백.
 *     emailCategory 가 있으면 derivedCategory 는 평가조차 되지 않는다(Mongo 조회 생략).
 *   - 제목 헤드라인: displayId(그룹 식별자)가 있으면 그것을, 없으면 hostname 을 쓴다.
 */
class EmailRoutingResolverSpec extends FlatSpec with Matchers {

  private val hostname = "EQP001"

  "EmailRoutingResolver" should
    "use emailCategory directly and NOT evaluate the derived category" in {
    var derivedEvaluated = false
    val (category, _) = EmailRoutingResolver.resolve(
      emailCategory = Some("EMAIL-PHOTO-ALL-TEAM1"),
      displayId = None,
      hostname = hostname,
      derivedCategory = { derivedEvaluated = true; throw new RuntimeException("getEmailCategory must not be called") }
    )
    derivedEvaluated should be(false)
    category should be("EMAIL-PHOTO-ALL-TEAM1")
  }

  it should "fall back to the derived category when emailCategory is None" in {
    val (category, _) = EmailRoutingResolver.resolve(
      emailCategory = None,
      displayId = None,
      hostname = hostname,
      derivedCategory = "EMAIL-DERIVED"
    )
    category should be("EMAIL-DERIVED")
  }

  it should "fall back to the derived category when emailCategory is an empty string" in {
    val (category, _) = EmailRoutingResolver.resolve(
      emailCategory = Some(""),
      displayId = None,
      hostname = hostname,
      derivedCategory = "EMAIL-DERIVED"
    )
    category should be("EMAIL-DERIVED")
  }

  it should "use displayId as the headline when present" in {
    val (_, headline) = EmailRoutingResolver.resolve(
      emailCategory = None,
      displayId = Some("MODEL-A"),
      hostname = hostname,
      derivedCategory = "EMAIL-DERIVED"
    )
    headline should be("MODEL-A")
  }

  it should "use hostname as the headline when displayId is None" in {
    val (_, headline) = EmailRoutingResolver.resolve(
      emailCategory = None,
      displayId = None,
      hostname = hostname,
      derivedCategory = "EMAIL-DERIVED"
    )
    headline should be(hostname)
  }

  it should "use hostname as the headline when displayId is an empty string" in {
    val (_, headline) = EmailRoutingResolver.resolve(
      emailCategory = None,
      displayId = Some(""),
      hostname = hostname,
      derivedCategory = "EMAIL-DERIVED"
    )
    headline should be(hostname)
  }
}
