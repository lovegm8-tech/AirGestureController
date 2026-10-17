package tw.airgesture.transferbeta.core

/** Home-screen action routing; no camera/Nearby operations in the Activity. */
enum class HomeState { NEED_PERMISSION, NEED_ACCESSIBILITY, NEED_START, SEARCHING, READY }
enum class HomeAction { GRANT_PERMISSION, ENABLE_ACCESSIBILITY, START, WAIT, BACKGROUND }

data class HomeDecision(val state: HomeState, val action: HomeAction, val instruction: String)

object HomeScreenPolicy {
    fun decide(
        hasPermissions: Boolean,
        accessibilityEnabled: Boolean,
        serviceRunning: Boolean,
        connected: Boolean
    ): HomeDecision = when {
        !hasPermissions -> HomeDecision(HomeState.NEED_PERMISSION,
            HomeAction.GRANT_PERMISSION, "第 1 步：允許相機與附近裝置")
        !accessibilityEnabled -> HomeDecision(HomeState.NEED_ACCESSIBILITY,
            HomeAction.ENABLE_ACCESSIBILITY, "第 2 步：啟用懸浮操作服務")
        !serviceRunning -> HomeDecision(HomeState.NEED_START,
            HomeAction.START, "開啟懸浮操作")
        !connected -> HomeDecision(HomeState.SEARCHING,
            HomeAction.BACKGROUND, "隔空操作已啟用；傳檔另需附近手機連線")
        else -> HomeDecision(HomeState.READY,
            HomeAction.BACKGROUND, "隔空操作與傳檔均已就緒")
    }
    /** Never send a pairing prompt into the background. */
    fun shouldAutoBackground(
        userEnabledAutoRun: Boolean, normalLauncherEntry: Boolean,
        serviceRunning: Boolean, connected: Boolean,
        pairingPromptOpen: Boolean, advancedOpen: Boolean
    ): Boolean = userEnabledAutoRun && normalLauncherEntry && serviceRunning &&
        connected && !pairingPromptOpen && !advancedOpen
}
