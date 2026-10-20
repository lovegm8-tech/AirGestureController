package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class NfcWebSharePolicyTest {
    @Test fun supportedBrowsersCanSendOneNfcWebShare() {
        for (pkg in listOf(
            NfcWebSharePolicy.CHROME,
            NfcWebSharePolicy.GOOGLE_APP,
            NfcWebSharePolicy.SAMSUNG_INTERNET
        )) {
            assertTrue(NfcWebSharePolicy.allowsForeground(pkg))
        }
    }

    @Test fun nonBrowserAndUnknownAppsCannotInitiateNfcWebShare() {
        for (pkg in listOf(
            null, "", "com.sec.android.gallery3d",
            "com.google.android.youtube", "com.google.android.apps.photos",
            "tw.airgesture.transferbeta", "com.android.settings"
        )) {
            assertFalse(NfcWebSharePolicy.allowsForeground(pkg))
        }
    }

    @Test fun webLinkValidationRejectsDangerousSchemesAndLocalTargets() {
        assertNull(GrabContentPolicy.safeWebUrl("javascript:alert(1)"))
        assertNull(GrabContentPolicy.safeWebUrl("file:///sdcard/private.txt"))
        assertNull(GrabContentPolicy.safeWebUrl("https://localhost/private"))
        assertEquals("https://example.com/read?x=1",
            GrabContentPolicy.safeWebUrl("https://example.com/read?x=1"))
    }

    @Test fun browserNamesAreUnambiguousForDiagnostics() {
        assertEquals("Chrome", NfcWebSharePolicy.browserName(NfcWebSharePolicy.CHROME))
        assertEquals("Google App", NfcWebSharePolicy.browserName(NfcWebSharePolicy.GOOGLE_APP))
        assertEquals("三星瀏覽器", NfcWebSharePolicy.browserName(NfcWebSharePolicy.SAMSUNG_INTERNET))
    }
}
