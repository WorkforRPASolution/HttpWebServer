package com.sec.eeg.ars.data

import java.io.File

import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.scalatest.{FlatSpec, Matchers}

/**
 * P5-1 — cross-language wire-contract parsing (Option C, json4s only; no actor /
 * Mongo / Redis).
 *
 * RMS owns the contract (single source of truth) and asserts its own
 * `to_payload()` against it (ResourceMonitorServer/tests/unit/test_akka_contract.py).
 * We read the SAME file here — no vendored copy, so there is zero drift between
 * what RMS produces and what Akka parses. The fixture carries two payloads:
 *   - `rendered`: full Option C payload → renderedBody/title = Some(_)
 *   - `legacy`  : the historical 9-field payload → renderedBody/title = None
 * The `None` case proves backward compatibility: a pre-Option-C producer (no
 * renderedBody/title keys) still extracts cleanly with no MappingException.
 */
class EmailHttpDataFormatSpec extends FlatSpec with Matchers {

  private implicit val formats: Formats = DefaultFormats

  private def contractFile: File = {
    val rel = "ResourceMonitorServer/tests/data/akka_email_contract.json"
    // mvn runs with user.dir = HttpWebServer project root; the RMS project is a
    // sibling under ARS/. Search a couple of parents so the test also works when
    // run from the ARS root.
    val candidates = Seq(".", "..", "../..").map(p => new File(s"$p/$rel"))
    candidates.find(_.exists()).getOrElse(
      fail(s"wire-contract fixture not found (user.dir=${sys.props("user.dir")}); tried " +
        candidates.map(_.getAbsolutePath).mkString(", "))
    )
  }

  private lazy val contract: JValue = {
    val src = scala.io.Source.fromFile(contractFile, "UTF-8")
    try parse(src.mkString) finally src.close()
  }

  "EmailHttpDataFormat" should "extract renderedBody and title as Some from the rendered contract" in {
    val conv = (contract \ "rendered").extract[EmailHttpDataFormat]
    conv.hostname should be("EQP001")
    conv.app should be("ARS")
    conv.model should be("MODEL-A")
    conv.code should be("RESOURCE_MONITOR")
    conv.subcode should be("CPU_CRITICAL")
    conv.variables("Severity") should be("CRITICAL")
    conv.renderedBody shouldBe defined
    conv.renderedBody.get should include("<table")
    conv.title should be(Some("[EARS] CPU CRITICAL - EQP001"))
  }

  it should "extract renderedBody and title as None from the legacy 9-field contract" in {
    val conv = (contract \ "legacy").extract[EmailHttpDataFormat]
    // every legacy field still parses
    conv.hostname should be("EQP001")
    conv.ip should be("10.0.0.99")
    conv.app should be("ARS")
    conv.process should be("PHOTO")
    conv.model should be("MODEL-A")
    conv.line should be("L1")
    conv.code should be("RESOURCE_MONITOR")
    conv.subcode should be("CPU_CRITICAL")
    conv.variables("WindowMin") should be("10")
    // optional fields absent → None, NOT a MappingException (backward compat)
    conv.renderedBody should be(None)
    conv.title should be(None)
  }
}
