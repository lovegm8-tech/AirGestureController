package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec

class PeerIdentityProtocolTest {
    private fun identity() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    private fun nonce() = PeerIdentityProtocol.encode(ByteArray(32).also {
        SecureRandom().nextBytes(it)
    })
    private val aliceId = "a0b1c2d3e4f5"
    private val bobId = "f5e4d3c2b1a0"

    @Test fun validIdsAndNonces() {
        assertTrue(PeerIdentityProtocol.validPeerId(aliceId))
        assertFalse(PeerIdentityProtocol.validPeerId("Phone A"))
        assertTrue(PeerIdentityProtocol.validNonce(nonce()))
        assertFalse(PeerIdentityProtocol.validNonce("short"))
    }
    @Test fun pinnedKeyMustMatchBothIdAndPublicKey() {
        val a = PeerIdentityProtocol.encodedKey(identity().public)
        val b = PeerIdentityProtocol.encodedKey(identity().public)
        assertTrue(PeerIdentityProtocol.matchesPinned(aliceId,a,aliceId,a))
        assertFalse(PeerIdentityProtocol.matchesPinned(aliceId,a,aliceId,b))
        assertFalse(PeerIdentityProtocol.matchesPinned(aliceId,a,bobId,a))
    }
    @Test fun proofVerifiesButImpostorCannotSignForPinnedDevice() {
        val a=identity(); val b=identity()
        val ap=PeerIdentityProtocol.encodedKey(a.public)
        val bp=PeerIdentityProtocol.encodedKey(b.public)
        val message=PeerIdentityProtocol.proofMessage(aliceId,bobId,ap,bp,nonce(),nonce())
        assertTrue(PeerIdentityProtocol.verify(ap,message,PeerIdentityProtocol.sign(a,message)))
        assertFalse(PeerIdentityProtocol.verify(ap,message,PeerIdentityProtocol.sign(b,message)))
    }
    @Test fun freshChallengesPreventReplay() {
        val a=identity(); val b=identity()
        val ap=PeerIdentityProtocol.encodedKey(a.public); val bp=PeerIdentityProtocol.encodedKey(b.public)
        val na=nonce(); val nb=nonce()
        val one=PeerIdentityProtocol.proofMessage(aliceId,bobId,ap,bp,na,nb)
        val two=PeerIdentityProtocol.proofMessage(aliceId,bobId,ap,bp,nonce(),nb)
        val sig=PeerIdentityProtocol.sign(a,one)
        assertTrue(PeerIdentityProtocol.verify(ap,one,sig))
        assertFalse(PeerIdentityProtocol.verify(ap,two,sig))
    }
    @Test fun roleSwapAndKeySwapAreNotAccepted() {
        val a=identity(); val b=identity()
        val ap=PeerIdentityProtocol.encodedKey(a.public); val bp=PeerIdentityProtocol.encodedKey(b.public)
        val na=nonce(); val nb=nonce()
        val original=PeerIdentityProtocol.proofMessage(aliceId,bobId,ap,bp,na,nb)
        val swap=PeerIdentityProtocol.proofMessage(bobId,aliceId,bp,ap,nb,na)
        val sig=PeerIdentityProtocol.sign(a,original)
        assertFalse(PeerIdentityProtocol.verify(ap,swap,sig))
    }
    @Test fun malformedOrUnknownPublicKeyIsDenied() {
        assertFalse(PeerIdentityProtocol.verify("not-base64","hello".toByteArray(),"bad"))
        assertFalse(PeerIdentityProtocol.matchesPinned(aliceId,"garbage",aliceId,"garbage"))
    }
}
