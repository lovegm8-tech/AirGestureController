package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class ReleaseUpdatePolicyTest {
    @Test fun stableVersionIsNewerThanBetaAtSameNumber() {
        assertTrue(ReleaseUpdatePolicy.isNewer("v1.0.8", "1.0.8-beta.2"))
        assertFalse(ReleaseUpdatePolicy.isNewer("v1.0.8-beta.1", "1.0.8"))
    }

    @Test fun newerVersionIsNotAcceptedOnMalformedOrEqualTag() {
        assertFalse(ReleaseUpdatePolicy.isNewer("v1.0.7", "1.0.7"))
        assertFalse(ReleaseUpdatePolicy.isNewer("v1.0.6", "1.0.7"))
        assertFalse(ReleaseUpdatePolicy.isNewer("latest", "1.0.7"))
        assertFalse(ReleaseUpdatePolicy.isNewer("v1.0.8", "latest"))
        assertTrue(ReleaseUpdatePolicy.isNewer("v1.0.8-beta.1", "1.0.7"))
        assertTrue(ReleaseUpdatePolicy.isNewer("v1.0.8-beta.3", "1.0.8-beta.2"))
    }

    @Test fun channelsCannotCross() {
        assertTrue(ReleaseUpdatePolicy.correctChannel(
            "v1.0.8-beta.1", true, ReleaseUpdatePolicy.Channel.TEST))
        assertFalse(ReleaseUpdatePolicy.correctChannel(
            "v1.0.8", false, ReleaseUpdatePolicy.Channel.TEST))
        assertTrue(ReleaseUpdatePolicy.correctChannel(
            "v1.0.8", false, ReleaseUpdatePolicy.Channel.STABLE))
        assertFalse(ReleaseUpdatePolicy.correctChannel(
            "v1.0.8-beta.1", true, ReleaseUpdatePolicy.Channel.STABLE))
        assertFalse(ReleaseUpdatePolicy.correctChannel(
            "v1.0.8", true, ReleaseUpdatePolicy.Channel.STABLE))
    }

    @Test fun onlyOfficialSignedAssetMetadataIsDownloadable() {
        val validSha = "sha256:" + "a".repeat(64)
        val official = "https://github.com/lovegm8-tech/AirGestureController/releases/download/v1.0.8/app-release.apk"
        assertTrue(ReleaseUpdatePolicy.acceptableAsset("app-release.apk", official, validSha, 40_000_000))
        assertFalse(ReleaseUpdatePolicy.acceptableAsset("unknown.apk", official, validSha, 40_000_000))
        assertFalse(ReleaseUpdatePolicy.acceptableAsset("app-release.apk",
            "https://evil.invalid/releases/app-release.apk", validSha, 40_000_000))
        assertFalse(ReleaseUpdatePolicy.acceptableAsset("app-release.apk",
            official, "sha256:wrong", 40_000_000))
        assertFalse(ReleaseUpdatePolicy.acceptableAsset("app-release.apk",
            official, validSha, 1))
    }

    @Test fun downloadRedirectsMustRemainOnKnownGitHubDomains() {
        assertTrue(ReleaseUpdatePolicy.trustedDownloadHost("release-assets.githubusercontent.com"))
        assertTrue(ReleaseUpdatePolicy.trustedDownloadHost("github.com"))
        assertFalse(ReleaseUpdatePolicy.trustedDownloadHost("github.com.evil.invalid"))
        assertFalse(ReleaseUpdatePolicy.trustedDownloadHost("myserver.invalid"))
    }
}
