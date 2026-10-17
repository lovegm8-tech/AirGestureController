package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class CameraUnlockGateTest {
    @Test fun screenOnDoesNotGrantCameraDuringFaceUnlock() {
        val gate = CameraUnlockGate()
        assertFalse(gate.canUseCamera(true, false, false))
        gate.screenOn()
        assertFalse(gate.canUseCamera(true, true, true))
        assertFalse(gate.canUseCamera(true, false, false))
        assertFalse(gate.confirmUnlocked(true, true, true))
        assertFalse(gate.canUseCamera(true, false, false))
        assertTrue(gate.confirmUnlocked(true, false, false))
        assertTrue(gate.canUseCamera(true, false, false))
    }

    @Test fun screenOffImmediatelyRevokesCameraPermit() {
        val gate = CameraUnlockGate()
        gate.screenOn()
        assertTrue(gate.confirmUnlocked(true, false, false))
        assertTrue(gate.canUseCamera(true, false, false))
        gate.screenOff()
        assertFalse(gate.canUseCamera(true, false, false))
        assertFalse(gate.confirmUnlocked(false, false, false))
        gate.screenOn()
        assertFalse(gate.canUseCamera(true, false, false))
    }

    @Test fun relockRevokesCameraEvenAfterEarlierSuccessfulUnlock() {
        val gate = CameraUnlockGate()
        gate.screenOn()
        gate.confirmUnlocked(true, false, false)
        assertFalse(gate.canUseCamera(false, false, false))
        assertFalse(gate.canUseCamera(true, true, false))
        assertFalse(gate.canUseCamera(true, false, true))
        assertFalse(gate.confirmUnlocked(true, false, true))
        assertFalse(gate.canUseCamera(true, false, false))
    }

    @Test fun powerOnBroadcastAloneCannotOpenCamera() {
        val gate = CameraUnlockGate()
        gate.screenOff()
        gate.screenOn()
        assertFalse(gate.confirmUnlocked(true, false, true))
        assertFalse(gate.confirmUnlocked(true, true, false))
        assertFalse(gate.canUseCamera(true, false, false))
        assertTrue(gate.confirmUnlocked(true, false, false))
    }
}
