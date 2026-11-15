package app.listentogether.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClockSyncTest {
    @Test
    fun picksLowestRttSample() {
        var local = 1_000L
        val sync = ClockSync(clock = { local })
        // 服务器比本地快 5000ms；第一次往返 200ms 且不对称，第二次 20ms。
        sync.addSample(t0 = 1_000, t1 = 6_150, t2 = 6_150, t3 = 1_200)
        sync.addSample(t0 = 2_000, t1 = 7_010, t2 = 7_010, t3 = 2_020)
        assertEquals(20, sync.best!!.rttMs)
        assertEquals(5_000, sync.best!!.offsetMs)
        local = 3_000
        assertEquals(8_000, sync.serverNowMs())
    }
}

class DriftControllerTest {
    @Test
    fun deadZoneDoesNothing() {
        assertEquals(DriftAction.None, DriftController().decide(10_000, 10_050))
    }

    @Test
    fun smallDriftAdjustsRateUntilSettled() {
        val c = DriftController()
        assertEquals(DriftAction.SetRate(0.97f), c.decide(10_000, 10_150)) // 快了 150ms，放慢
        assertEquals(DriftAction.None, c.decide(10_000, 10_080)) // 还在追
        assertEquals(DriftAction.SetRate(1f), c.decide(10_000, 10_010)) // 追上了
        assertEquals(DriftAction.SetRate(1.03f), c.decide(10_000, 9_900)) // 慢了 100ms，加速
    }

    @Test
    fun largeDriftSeeks() {
        val c = DriftController()
        c.decide(10_000, 10_150)
        val action = c.decide(10_000, 11_000)
        assertIs<DriftAction.Seek>(action)
        assertEquals(10_000, action.positionMs)
        assertEquals(1f, c.currentRate)
    }
}

class SyncModeTest {
    @Test
    fun relaxedNeverChangesRateAndSeeksOnlyPastThreeSeconds() {
        val c = SyncMode.RELAXED.controller()
        assertEquals(DriftAction.None, c.decide(10_000, 10_150))
        assertEquals(DriftAction.None, c.decide(10_000, 7_500))
        assertEquals(DriftAction.None, c.decide(10_000, 13_000))
        assertIs<DriftAction.Seek>(c.decide(10_000, 13_200))
        assertEquals(1f, c.currentRate)
    }

    @Test
    fun balancedUsesGentleRate() {
        val c = SyncMode.BALANCED.controller()
        assertEquals(DriftAction.None, c.decide(10_000, 10_120))
        assertEquals(DriftAction.SetRate(0.99f), c.decide(10_000, 10_300))
        assertIs<DriftAction.Seek>(c.decide(10_000, 11_200))
    }

    @Test
    fun unknownKeyFallsBackToRelaxed() {
        assertEquals(SyncMode.RELAXED, SyncMode.of(null))
        assertEquals(SyncMode.RELAXED, SyncMode.of("bogus"))
        assertEquals(SyncMode.PRECISE, SyncMode.of("precise"))
    }
}

class SeekPlannerTest {
    @Test
    fun learnsLeadFromLandingError() {
        val p = SeekPlanner()
        assertEquals(10_000, p.target(10_000))
        p.onSeek(1_000)
        p.onLanded(-1_200) // 没有提前量，落地时落后 1.2 秒
        assertEquals(1_200, p.leadMs)
        assertEquals(11_200, p.target(10_000))
        p.onSeek(5_000)
        p.onLanded(300) // 提前 1.2 秒跳，落地快了 0.3 秒，其实只要 0.9 秒
        assertEquals(1_100, p.leadMs)
    }

    @Test
    fun bufferedSeeksLearnSeparately() {
        val p = SeekPlanner()
        p.onSeek(0); p.onLanded(-2_000)
        assertEquals(2_000, p.leadMs)
        // 缓冲区内跳转不套用联网的提前量，不会跳过头
        assertEquals(10_000, p.target(10_000, inBuffer = true))
        p.onSeek(5_000, inBuffer = true); p.onLanded(-40)
        assertEquals(20, p.bufferedLeadMs)
        assertEquals(2_000, p.leadMs)
    }

    @Test
    fun ignoresLandingWithoutSeekOrWayOff() {
        val p = SeekPlanner()
        p.onLanded(-1_000)
        assertEquals(0, p.leadMs)
        p.onSeek(1_000)
        p.onLanded(-60_000)
        assertEquals(0, p.leadMs)
    }

    @Test
    fun backsOffWhenSeekingRepeatedly() {
        val p = SeekPlanner()
        assertTrue(p.canSeek(0)); p.onSeek(0)
        assertTrue(p.canSeek(1_000)); p.onSeek(1_000)
        assertFalse(p.canSeek(2_500)) // 第三次要隔 2 秒
        assertTrue(p.canSeek(3_000)); p.onSeek(3_000)
        assertFalse(p.canSeek(6_000)) // 第四次要隔 4 秒
        assertTrue(p.canSeek(7_000)); p.onSeek(7_000)
        // 20 秒后旧记录过期，恢复正常
        assertTrue(p.canSeek(30_000))
    }
}
