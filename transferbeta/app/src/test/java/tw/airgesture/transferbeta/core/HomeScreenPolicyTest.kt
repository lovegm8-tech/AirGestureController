package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class HomeScreenPolicyTest {
    @Test fun permissionIsFirstAndKeepsUserInApp() {
        val d = HomeScreenPolicy.decide(false, false, false, false)
        assertEquals(HomeState.NEED_PERMISSION, d.state)
        assertEquals(HomeAction.GRANT_PERMISSION, d.action)
    }
    @Test fun accessibilityIsSecondAndKeepsUserInApp() {
        assertEquals(HomeAction.ENABLE_ACCESSIBILITY,
            HomeScreenPolicy.decide(true, false, false, false).action)
    }
    @Test fun firstTapStartsServiceWithoutImplicitBackground() {
        assertEquals(HomeAction.START,
            HomeScreenPolicy.decide(true, true, false, false).action)
    }
    @Test fun searchingOffersExplicitReturnToDesktopButton() {
        val d = HomeScreenPolicy.decide(true, true, true, false)
        assertEquals(HomeState.SEARCHING, d.state)
        assertEquals(HomeAction.BACKGROUND, d.action)
    }
    @Test fun alreadyConnectedOffersExplicitReturnToDesktopButton() {
        val d = HomeScreenPolicy.decide(true, true, true, true)
        assertEquals(HomeState.READY, d.state)
        assertEquals(HomeAction.BACKGROUND, d.action)
    }
    // There is deliberately no shouldAutoBackground policy. Pairing events,
    // launcher re-entry and permission completion cannot send app to desktop.
}
