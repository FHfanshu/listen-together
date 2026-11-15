package app.listentogether.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OutputSyncPolicyTest {
    @Test fun remoteOutputUsesSeekOnlyWithoutChangingThePhonePreference() {
        val preferred = SyncMode.PRECISE
        val remote = outputSyncMode(preferred, remote = true)
        assertEquals(SyncMode.RELAXED, remote)
        val controller = remote.controller()
        assertEquals(DriftAction.None, controller.decide(10_000, 8_000))
        assertIs<DriftAction.Seek>(controller.decide(10_000, 5_000))
        assertEquals(1f, controller.currentRate)
        assertEquals(preferred, outputSyncMode(preferred, remote = false))
    }
}
