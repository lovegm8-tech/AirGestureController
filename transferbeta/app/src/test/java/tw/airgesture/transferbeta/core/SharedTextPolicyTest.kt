package tw.airgesture.transferbeta.core
import org.junit.Assert.*
import org.junit.Test

class SharedTextPolicyTest {
    @Test fun singleLinkFromSocialCaption() {
        assertEquals("https://example.com/post",
            SharedTextPolicy.onlyWebLink("Watch: https://example.com/post"))
    }
    @Test fun refuseAmbiguousLinks() {
        assertNull(SharedTextPolicy.onlyWebLink(
            "https://a.example/post and https://b.example/post"))
    }
    @Test fun neverGuessFromTitle() {
        assertNull(SharedTextPolicy.onlyWebLink("Interesting YouTube video"))
    }
}
