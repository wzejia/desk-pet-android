package com.xiaoduo.deskpet.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DATA-3：语音轮入的写者门语义（文字轮在途即拒，防 _messages 双写者交错覆盖）。 */
class TurnGateRulesTest {

    @Test
    fun `DATA-3 sending or streaming counts as text turn in flight`() {
        assertTrue(TurnGateRules.textTurnInFlight(sending = true, streaming = false))
        assertTrue(TurnGateRules.textTurnInFlight(sending = false, streaming = true))
        assertTrue(TurnGateRules.textTurnInFlight(sending = true, streaming = true))
    }

    @Test
    fun `DATA-3 idle phase admits voice turn`() {
        assertFalse(TurnGateRules.textTurnInFlight(sending = false, streaming = false))
    }
}
