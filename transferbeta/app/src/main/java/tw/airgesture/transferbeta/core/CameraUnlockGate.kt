package tw.airgesture.transferbeta.core

/**
 * Screen-on is NOT the same as unlocked. In particular, Samsung face unlock
 * needs exclusive access to the front camera before AirGrab may rebind it.
 * This gate is separate from the Nearby background transport.
 */
class CameraUnlockGate {
    private var screenInteractive = false
    private var unlockVerified = false

    fun screenOff() {
        screenInteractive = false
        unlockVerified = false
    }

    fun screenOn() {
        screenInteractive = true
        unlockVerified = false
    }

    /** Only a fresh KeyguardManager / PowerManager check may open this gate. */
    fun confirmUnlocked(interactive: Boolean, keyguardLocked: Boolean, deviceLocked: Boolean): Boolean {
        unlockVerified = screenInteractive && interactive &&
            !keyguardLocked && !deviceLocked
        return unlockVerified
    }

    fun canUseCamera(interactive: Boolean, keyguardLocked: Boolean, deviceLocked: Boolean): Boolean =
        screenInteractive && unlockVerified && interactive &&
            !keyguardLocked && !deviceLocked
}
