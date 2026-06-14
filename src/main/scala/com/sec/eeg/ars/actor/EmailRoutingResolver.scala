package com.sec.eeg.ars.actor

/**
 * 그룹 경보 메일의 (수신자 카테고리, 제목 헤드라인) 결정을 EmailWorker.SendEmail
 * 에서 떼어낸 순수 함수. EmailBodyResolver 와 같은 의도로, 이 판단을 액터 / Mongo /
 * Redis 없이 단위 테스트할 수 있게 분리했다.
 *
 * - 수신자 카테고리: RMS가 payload `emailCategory` 로 완성 카테고리를 직접 지정하면
 *   그대로 쓰고(역산 생략), 없으면 `derivedCategory`(= getEmailCategory 역산)로 폴백.
 * - 제목 헤드라인: payload `displayId`(그룹 식별자)가 있으면 그것을, 없으면 hostname
 *   (= 대표 eqpId)을 쓴다.
 *
 * 두 필드 모두 None/빈문자열이면 현행 동작(역산 + hostname 헤드라인)과 동일하다.
 */
object EmailRoutingResolver {

  /**
   * @param emailCategory   RMS가 직접 지정한 수신자 카테고리. 있으면 역산을 건너뛴다.
   * @param displayId       제목 헤드라인에 hostname 대신 쓸 그룹 식별자.
   * @param hostname        대표 eqpId. displayId 미지정 시 헤드라인 기본값.
   * @param derivedCategory by-name. `emailCategory`가 비어있을 때만 평가되므로,
   *                        직접 지정 모드에서는 getEmailCategory(Mongo 조회)가 호출되지 않는다.
   * @return (수신자 카테고리, 제목 헤드라인)
   */
  def resolve(
      emailCategory: Option[String],
      displayId: Option[String],
      hostname: String,
      derivedCategory: => String
  ): (String, String) = {
    val category = emailCategory.filter(_.nonEmpty).getOrElse(derivedCategory)
    val headline = displayId.filter(_.nonEmpty).getOrElse(hostname)
    (category, headline)
  }
}
