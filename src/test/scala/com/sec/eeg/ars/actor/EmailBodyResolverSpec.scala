package com.sec.eeg.ars.actor

import org.scalatest.{FlatSpec, Matchers}

/**
 * P5-2 — pure spec of the body/title selection factored out of
 * EmailWorker.SendEmail (no actor / Mongo / Redis). Four branches:
 *   1. renderedBody=Some → body = renderedBody with ONLY @HttpWebServerAddress
 *      substituted (D2); title from the `title` field.
 *   2. renderedBody=Some → the legacy template path is NOT evaluated (by-name),
 *      so getEmailBody is never called and "There is no email template" cannot
 *      fire even when no DB template row exists.
 *   3. renderedBody=None → fall back to the legacy (title, body).
 *   4. renderedBody=Some → title is single-sourced from RMS, never from the
 *      legacy template title (D1).
 *
 * The result is destructured into `subject` (not `title`) to avoid the Scala
 * named-argument / assignment ambiguity with the `title =` argument below.
 */
class EmailBodyResolverSpec extends FlatSpec with Matchers {

  private val addr = "ars.example.com:8080"

  "EmailBodyResolver" should
    "use renderedBody, substitute only @HttpWebServerAddress, and take the title from the title field" in {
    val (subject, body) = EmailBodyResolver.resolve(
      renderedBody = Some("<p>link http://@HttpWebServerAddress/d?a=1&b=2</p>"),
      title = Some("[EARS] CPU CRITICAL - EQP001"),
      publicAddr = addr,
      legacy = ("LEGACY TITLE", "LEGACY BODY")
    )
    subject should be("[EARS] CPU CRITICAL - EQP001")
    body should be(s"<p>link http://$addr/d?a=1&b=2</p>")
    body should not include "@HttpWebServerAddress"
    body should not include "LEGACY"
  }

  it should "NOT evaluate the legacy template path when renderedBody is defined" in {
    var legacyEvaluated = false
    val (subject, body) = EmailBodyResolver.resolve(
      renderedBody = Some("<p>ok</p>"),
      title = Some("T"),
      publicAddr = addr,
      legacy = { legacyEvaluated = true; throw new RuntimeException("getEmailBody must not be called") }
    )
    legacyEvaluated should be(false)
    subject should be("T")
    body should be("<p>ok</p>")
  }

  it should "fall back to the legacy (title, body) when renderedBody is None" in {
    val (subject, body) = EmailBodyResolver.resolve(
      renderedBody = None,
      title = None,
      publicAddr = addr,
      legacy = ("LEGACY TITLE", "<p>legacy</p>")
    )
    subject should be("LEGACY TITLE")
    body should be("<p>legacy</p>")
  }

  it should "single-source the title from RMS in renderedBody mode (ignore the legacy title)" in {
    val (subject, _) = EmailBodyResolver.resolve(
      renderedBody = Some("<p>x</p>"),
      title = Some("RMS TITLE"),
      publicAddr = addr,
      legacy = ("LEGACY TITLE", "<p>legacy</p>")
    )
    subject should be("RMS TITLE")
  }
}
