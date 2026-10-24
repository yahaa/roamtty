package io.itermux.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sound player's rules (iOS `AudioJitterTests`): a buffer as deep as the jitter, drops only for bursts, drift in the rate. */
class AudioJitterTest {
    private class Run(var drops: Int = 0, var dries: Int = 0, var packets: Int = 0, var cushions: Int = 0,
        val backlogs: MutableList<Double> = mutableListOf(), val targets: MutableList<Int> = mutableListOf())

    /** Packets leave the host every 20 ms (its clock [hostFast] fast), arrive first in first out [delay] ms later; the phone plays at `rate`. */
    private fun simulate(seconds: Int, hostFast: Double = 0.0, delay: (Long) -> Double): Run {
        val j = AudioJitter()
        val run = Run()
        var previous = 0.0
        val arrivals = (1..seconds * 50L).map { seq -> previous = maxOf(previous, seq * 20 / (1 + hostFast) + delay(seq)); seq to previous }
        var backlog = 0.0
        var last = arrivals[0].second
        for ((seq, t) in arrivals) {
            backlog -= (t - last) * j.rate
            var dry = false
            if (backlog < 0) { if (run.packets > 0) { dry = true; run.dries++ }; backlog = 0.0 }
            last = t
            when (val d = j.arrive(t.toLong(), seq, backlog.toInt(), dry)) {
                AudioJitter.Decision.Drop -> run.drops++
                is AudioJitter.Decision.Play -> {
                    backlog += d.cushionMs + AudioJitter.PACKET_MS
                    run.packets++
                    if (d.cushionMs > 0) run.cushions++
                }
            }
            run.backlogs += backlog
            run.targets += j.targetMs
        }
        return run
    }

    /** Deterministic noise in 0..<1. */
    private fun noise(seq: Long, salt: Long = 0): Double {
        var x = seq * -7046029254386353131L xor salt
        x = x xor (x ushr 31); x *= -4658895280553007687L; x = x xor (x ushr 29)
        return java.lang.Long.remainderUnsigned(x, 10_000) / 10_000.0
    }

    @Test fun aWiredLinkKeepsTheBufferShallow() {
        val run = simulate(30) { noise(it) * 4 + 2 }
        assertEquals(0, run.drops)
        assertEquals(AudioJitter.MIN_TARGET_MS, run.targets.last())
        assertTrue(run.backlogs.takeLast(500).max() < 120)
        assertTrue(run.dries <= 1)
    }

    /** Like a Mac's Wi-Fi to the test VM: 8–160 ms, packets held back and released together, the VM's clock 1.6% fast. */
    @Test fun wifiJitterDeepensTheBufferInsteadOfDroppingAndRunningDry() {
        val run = simulate(60, hostFast = 0.016) { seq ->
            val stall = if (noise(seq / 8, 7) < 0.25) noise(seq / 8, 9) * 120 else 0.0
            8 + noise(seq) * 30 + stall
        }
        assertTrue("${run.drops} drops", run.drops.toDouble() / run.packets < 0.01)
        assertTrue("${run.dries} dries", run.dries.toDouble() / run.packets < 0.01)
        assertTrue(run.targets.last() > 120)
        val steady = run.packets / 2
        assertTrue(run.backlogs.takeLast(steady).average() < run.targets.takeLast(steady).average() + 40)
        assertTrue(run.cushions <= 2)
    }

    @Test fun driftIsTakenUpByTheRate() {
        val run = simulate(60, hostFast = 0.013) { noise(it) * 4 + 2 }
        assertEquals(0, run.drops)
        assertEquals(AudioJitter.MIN_TARGET_MS, run.targets.last())
        assertEquals((AudioJitter.MIN_TARGET_MS + AudioJitter.PACKET_MS).toDouble(), run.backlogs.takeLast(500).average(), 8.0)
    }

    @Test fun aBurstPastTheLimitIsDroppedDownToTheTarget() {
        val j = AudioJitter()
        assertEquals(AudioJitter.Decision.Play(AudioJitter.MIN_TARGET_MS - 20), j.arrive(20, 1, 0, false))
        var seq = 1L
        fun next(backlog: Int): AudioJitter.Decision { seq++; return j.arrive(seq * 20, seq, backlog, false) }
        assertEquals(AudioJitter.Decision.Play(0), next(150))
        assertEquals(AudioJitter.Decision.Drop, next(200))
        assertEquals(AudioJitter.Decision.Drop, next(100))
        assertEquals(AudioJitter.Decision.Play(0), next(60))
        assertEquals(AudioJitter.Decision.Play(0), next(150))
    }

    @Test fun silenceRefillsTheCushionAStallDoesNot() {
        var j = AudioJitter()
        j.arrive(20, 1, 0, false)
        assertEquals(AudioJitter.Decision.Play(0), j.arrive(200, 2, 0, true))
        assertEquals(AudioJitter.Decision.Play(0), j.arrive(201, 3, 20, false))
        assertEquals("older than the last", AudioJitter.Decision.Drop, j.arrive(202, 2, 40, false))
        j = AudioJitter()
        j.arrive(20, 1, 0, false)
        j.arrive(40, 2, 40, false)
        // The host was silent for a second: it skipped packets 3–51.
        assertEquals(AudioJitter.Decision.Play(40), j.arrive(1040, 52, 0, false))
        assertEquals(AudioJitter.MIN_TARGET_MS, j.targetMs)
        // A restarted serve numbers from 1 again.
        assertEquals(AudioJitter.Decision.Play(40), j.arrive(90_000, 1, 0, false))
    }

    @Test fun theRateIsCappedAtThreePercent() {
        val j = AudioJitter()
        var seq = 0L
        repeat(500) { seq++; j.arrive(seq * 20, seq, 170, false) }
        assertEquals(1.03, j.rate, 0.001)
        repeat(500) { seq++; j.arrive(seq * 20, seq, 0, false) }
        assertEquals(0.97, j.rate, 0.001)
    }
}
