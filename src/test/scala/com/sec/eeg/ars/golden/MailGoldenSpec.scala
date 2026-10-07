package com.sec.eeg.ars.golden

import com.sec.eeg.ars.testkit.GoldenSuite

/** 메일 4종 (RMS·ARSAgent·CommandServer·InterfaceServer·AgentWatchdog 가 부르는 경로) */
class MailGoldenSpec extends GoldenSuite("SendEmail", "SendEmailForRTM", "SendRecoveryEmail", "ScriptResult")
