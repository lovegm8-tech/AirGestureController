package tw.airgesture.transferbeta.updates

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import tw.airgesture.transferbeta.core.ReleaseUpdatePolicy

/** Data-only push is strictly a check hint: no URL or executable command. */
class UpdatePushService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        if (!UpdateCoordinator.enabled(this)) return
        if (message.data["action"] != "check_update") return
        val expected = UpdateCoordinator.channel(this)
        val supplied = message.data["channel"]
        if (supplied != expected.topic) return
        UpdateCoordinator.checkNow(this)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // No token is sent to a custom server. Firebase persists topic subscriptions.
        if (UpdateCoordinator.enabled(this)) UpdateCoordinator.initialize(this)
    }
}
