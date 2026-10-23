package dev.offlinezec.wallet.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** P5.G3 logic: a restored seed is warned about twice (restore, then the first time offline). */
class SeedWarningTest {
    @Test
    fun twiceThenQuiet() {
        // A wallet created here: never.
        assertNull(seedWarning(SeedWarningFlags(), online = false))
        // Restored: at once, online or not, until acknowledged.
        var f = SeedWarningFlags(restored = true)
        assertEquals(SeedWarning.RESTORED, seedWarning(f, online = true))
        assertEquals(SeedWarning.RESTORED, seedWarning(f, online = false))
        f = f.copy(ackRestore = true)
        // Online: nothing more yet. Offline: the second warning, until acknowledged.
        assertNull(seedWarning(f, online = true))
        assertEquals(SeedWarning.OFFLINE, seedWarning(f, online = false))
        f = f.copy(ackOffline = true)
        assertNull(seedWarning(f, online = false))
        assertNull(seedWarning(f, online = true))
    }
}
