package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class GameForegroundPolicyTest {
    @Test fun pokemonGoStoreCopiesAndMonsterHunterNowAreRecognized() {
        assertTrue(GameForegroundPolicy.isGame("com.nianticlabs.pokemongo"))
        assertTrue(GameForegroundPolicy.isGame("com.nianticlabs.pokemongo.ares"))
        assertTrue(GameForegroundPolicy.isGame("com.nianticlabs.monsterhunter"))
    }
    @Test fun appDeclaredCategoryCatchesOtherGames() {
        assertTrue(GameForegroundPolicy.isGame("org.example.newgame",
            GameForegroundPolicy.CATEGORY_GAME))
        assertTrue(GameForegroundPolicy.isGame("org.example.oldgame",
            legacyGameFlag = true))
        assertFalse(GameForegroundPolicy.isGame("com.google.android.youtube"))
        assertFalse(GameForegroundPolicy.isGame("com.android.chrome", -1))
        assertFalse(GameForegroundPolicy.isGame(null, GameForegroundPolicy.CATEGORY_GAME))
    }
    @Test fun systemUiDoesNotImmediatelyResumeGameplayTracking() {
        assertTrue(GameForegroundPolicy.isTransientWindow("com.android.systemui"))
        assertTrue(GameForegroundPolicy.isTransientWindow("com.samsung.android.systemui"))
        assertFalse(GameForegroundPolicy.isTransientWindow("com.android.launcher"))
    }
}
