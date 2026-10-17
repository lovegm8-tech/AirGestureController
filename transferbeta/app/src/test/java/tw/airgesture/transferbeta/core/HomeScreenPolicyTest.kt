package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class HomeScreenPolicyTest {
    @Test fun permissionIsFirstAndPreventsBackground() {
        assertEquals(HomeState.NEED_PERMISSION,
            HomeScreenPolicy.decide(false,false,false,false).state)
    }
    @Test fun accessibilityIsSecond() {
        assertEquals(HomeAction.ENABLE_ACCESSIBILITY,
            HomeScreenPolicy.decide(true,false,false,false).action)
    }
    @Test fun userMustStartServiceOnce() {
        assertEquals(HomeAction.START,
            HomeScreenPolicy.decide(true,true,false,false).action)
    }
    @Test fun searchingShowsHelpfulStateNotReady() {
        assertEquals(HomeState.SEARCHING,
            HomeScreenPolicy.decide(true,true,true,false).state)
    }
    @Test fun serviceAndConnectionReadyMeansNoFurtherPress() {
        assertEquals(HomeAction.BACKGROUND,
            HomeScreenPolicy.decide(true,true,true,true).action)
    }
    @Test fun cannotHideWhilePairing() {
        assertFalse(HomeScreenPolicy.shouldAutoBackground(true,true,true,true,true,false))
    }
    @Test fun cannotHideWhenSettingsOpened() {
        assertFalse(HomeScreenPolicy.shouldAutoBackground(true,true,true,true,false,true))
    }
    @Test fun cannotHideFromNotificationSettingsOrShareIntent() {
        assertFalse(HomeScreenPolicy.shouldAutoBackground(true,false,true,true,false,false))
    }
    @Test fun repeatLaunchDoesNotNeedPairingIfStillConnected() {
        assertTrue(HomeScreenPolicy.shouldAutoBackground(true,true,true,true,false,false))
    }
    @Test fun noNearbyPeerStillAllowsControlAndBackground() {
        assertEquals(HomeAction.BACKGROUND, HomeScreenPolicy.decide(true,true,true,false).action)
        assertFalse(HomeScreenPolicy.shouldAutoBackground(true,true,true,false,false,false))
    }
}
