package com.sec.eeg.ars.actor

/**
 * 이메일 알림(EmailNotify)으로 보낼 (제목, 본문)을 결정하는 순수 함수 모음.
 * 원래 `EmailWorker.SendEmail` 안에 있던 분기 로직을 떼어내, Option C 판단
 * 부분만 액터 / Mongo / Redis / Cassandra 없이 단위 테스트할 수 있게 만들었다.
 *
 * Option C란: RMS(ResourceMonitorServer, Python)가 HTML 본문과 제목을 미리
 * 완성해서 렌더링한 경우(`renderedBody`가 있는 경우)를 말한다. 이때 Akka는
 * 그 본문과 제목을 그대로 사용하고, 인프라 서버 주소 토큰만 한 군데 치환한다.
 * DB에서 이메일 템플릿을 조회하지 않으므로, 템플릿 행이 없어서 발송이 실패하는
 * 일이 생기지 않는다. `renderedBody`가 없으면(None) 기존 템플릿 방식을 그대로 쓴다.
 */
object EmailBodyResolver {

  /** 미리 렌더링된 본문 안에서 Akka가 유일하게 치환하는 토큰.
   *  RMS는 장비/지표 데이터에 들어 있는 `@HttpWebServerAddress` 글자는
   *  치환되지 않도록 미리 무력화(`&#64;HttpWebServerAddress`로 렌더링)한다.
   *  따라서 본문에 남아 있는 이 토큰은 전부 의도적으로 넣은 인프라 링크다. */
  val ReservedAddressToken = "@HttpWebServerAddress"

  /**
   * @param renderedBody RMS가 미리 렌더링한 HTML 본문. 있으면 Option C 경로로 처리.
   * @param title        RMS가 보내준 제목. renderedBody 모드에서는 RMS가 항상
   *                     제목을 함께 보내준다(단일 출처). 별도 대체값이 없다.
   * @param publicAddr   `@HttpWebServerAddress` 토큰을 대체할 실제 서버 주소.
   * @param legacy       기존 템플릿 방식으로 구한 (제목, 본문). by-name 파라미터라
   *                     `renderedBody`가 None일 때만 평가된다. 즉 renderedBody
   *                     모드에서는 `getEmailBody`가 호출되지 않는다.
   * @return EmailingAgent로 넘길 (제목, 본문).
   */
  def resolve(
      renderedBody: Option[String],
      title: Option[String],
      publicAddr: String,
      legacy: => (String, String)
  ): (String, String) = renderedBody match {
    case Some(b) =>
      (title.getOrElse(""), b.replaceAllLiterally(ReservedAddressToken, publicAddr))
    case None =>
      legacy
  }
}
