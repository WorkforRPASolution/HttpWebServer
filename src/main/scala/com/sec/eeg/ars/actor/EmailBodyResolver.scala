package com.sec.eeg.ars.actor

/**
 * Pure selection of the (title, body) to send for an EmailNotify, factored out
 * of `EmailWorker.SendEmail` so the Option C decision is unit-testable without
 * the actor / Mongo / Redis / Cassandra.
 *
 * Option C: when RMS pre-renders the full HTML body + subject (`renderedBody`),
 * Akka uses them directly and substitutes ONLY the infra address token (D2) —
 * it does NOT look up a DB template, so a missing template row cannot fail the
 * send. Otherwise (`renderedBody = None`) the historical template path is used.
 */
object EmailBodyResolver {

  /** The only token Akka still substitutes inside a pre-rendered body (D2).
   *  RMS neutralizes any literal `@HttpWebServerAddress` in equipment/metric
   *  data (renders it as `&#64;HttpWebServerAddress`), so every occurrence left
   *  here is an intentional infra link. */
  val ReservedAddressToken = "@HttpWebServerAddress"

  /**
   * @param renderedBody RMS pre-rendered HTML body, if present (Option C).
   * @param title        RMS-supplied subject. In renderedBody mode RMS ALWAYS
   *                     provides it (D1) — single source of truth, no fallback.
   * @param publicAddr   value substituted for `@HttpWebServerAddress`.
   * @param legacy       by-name (title, body) from the legacy template path;
   *                     evaluated ONLY when `renderedBody` is None, so
   *                     `getEmailBody` is never called in renderedBody mode.
   * @return the (title, body) to ship to the EmailingAgent.
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
