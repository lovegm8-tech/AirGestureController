package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class NfcAutoAcceptPolicyTest {
    private fun permits(
        approvedEndpoint: String? = "peer-a",
        approvedSession: String? = "session-1",
        expires: Long = 1000L,
        endpoint: String = "peer-a",
        offered: String? = "session-1",
        now: Long = 950L,
        verified: Boolean = true
    ) = NfcAutoAcceptPolicy.permits(approvedEndpoint,
        approvedSession, expires, endpoint, offered, now, verified)

    @Test fun onlyTheTouchedPairedEndpointMayAutoReceive() {
        assertTrue(permits())
        assertFalse(permits(endpoint = "peer-b"))
        assertFalse(permits(verified = false))
        assertFalse(permits(offered = "session-other"))
        assertFalse(permits(offered = null))
        assertFalse(permits(approvedSession = null))
        assertFalse(permits(approvedEndpoint = null))
        assertFalse(permits(now = 1001L))
    }
}
