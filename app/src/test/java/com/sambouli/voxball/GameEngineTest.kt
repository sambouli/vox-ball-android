package com.sambouli.voxball

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GameEngineTest {

    /** A voiced frame at 200 Hz: what the analyser reports during a hummed note. */
    private fun hum() = VoiceFrame(voiced = true, f0 = 200f, level = 0.8f, clarity = 0.9f)

    /** Walks a player through the full ready -> countdown -> recorded -> confirm sequence. */
    private fun calibrate(e: GameEngine) {
        var guard = 0
        while (e.phase == Phase.CALIB_DONE && guard++ < 400) e.update(0.05f, VoiceFrame())
        e.beginCalibCountdown()
        while (e.phase == Phase.CALIB_COUNTDOWN && guard++ < 400) e.update(0.05f, VoiceFrame())
        while (!e.calibAwaitingConfirm && guard++ < 400) e.update(0.05f, hum())
        e.confirmCalibResult()
    }

    private fun playingEngine(seed: Int = 7): GameEngine {
        val e = GameEngine()
        e.random = Random(seed)
        e.resize(1600f, 900f)
        var guard = 0
        while (e.phase == Phase.HINTS && guard++ < 40) e.advanceHint()
        calibrate(e) // J1
        e.continueAfterCalibPause()
        calibrate(e) // J2
        e.startRound()
        guard = 0
        while (e.phase == Phase.SERVE && guard++ < 100) e.update(0.05f, VoiceFrame())
        return e
    }

    @Test
    fun calibrationWaitsForThePlayerAndCountsDown() {
        val e = GameEngine()
        e.resize(1600f, 900f)
        while (e.phase == Phase.HINTS) e.advanceHint()
        assertEquals(Phase.CALIB_READY, e.phase)
        assertEquals(0, e.calibSeat)
        // nothing is captured until the player is ready: a voice in the room is ignored
        repeat(40) { e.update(0.05f, hum()) }
        assertEquals(0, e.calibSamples)
        assertEquals(Phase.CALIB_READY, e.phase)

        e.beginCalibCountdown()
        assertEquals(Phase.CALIB_COUNTDOWN, e.phase)
        repeat(20) { e.update(0.05f, hum()) } // 1 s of countdown: still ignored
        assertEquals(0, e.calibSamples)

        var guard = 0
        while (e.phase == Phase.CALIB_COUNTDOWN && guard++ < 200) e.update(0.05f, VoiceFrame())
        assertEquals(Phase.CALIB_RECORD, e.phase)
    }

    @Test
    fun aSilentRecordingIsRejectedAndRetried() {
        val e = GameEngine()
        e.resize(1600f, 900f)
        while (e.phase == Phase.HINTS) e.advanceHint()
        e.beginCalibCountdown()
        var guard = 0
        while (e.phase != Phase.CALIB_RECORD && guard++ < 200) e.update(0.05f, VoiceFrame())
        while (!e.calibAwaitingConfirm && guard++ < 200) e.update(0.05f, VoiceFrame())
        assertTrue(e.calibAwaitingConfirm)
        assertTrue("nothing heard must not be accepted", !e.calibOk)
        assertEquals("a silent take does not hand over", 0, e.calibSeat)
        assertTrue(
            "back on the ready screen for the same player",
            e.phase == Phase.CALIB_READY || e.phase == Phase.CALIB_RECORD
        )
        assertEquals("the take buffer is empty again", 0, e.calibSamples)
    }

    @Test
    fun calibrationHandsOverToAPauseScreenInsteadOfChaining() {
        val e = GameEngine()
        e.resize(1600f, 900f)
        while (e.phase == Phase.HINTS) e.advanceHint()
        calibrate(e) // J1 hums 200 Hz
        assertEquals("hand over to a visible pause", Phase.CALIB_DONE, e.phase)
        assertEquals(1, e.calibSeat)
        assertEquals("J1 keeps his own baseline", 200f, e.pads[0].baseline, 6f)
        assertTrue("J2 has not been measured yet", e.pads[1].baseline < 1f)

        // during the hand-over pause nothing can be captured, whoever speaks
        repeat(20) { e.update(0.05f, VoiceFrame(voiced = true, f0 = 300f, level = 0.9f, clarity = 0.9f)) }
        assertEquals(Phase.CALIB_DONE, e.phase)
        assertEquals(0, e.calibSamples)

        // after the pause J2 gets his own ready screen; the recording only starts on purpose
        var guard = 0
        while (e.phase == Phase.CALIB_DONE && guard++ < 200) e.update(0.05f, VoiceFrame())
        assertEquals(Phase.CALIB_READY, e.phase)
        assertEquals(1, e.calibSeat)
        repeat(20) { e.update(0.05f, VoiceFrame(voiced = true, f0 = 300f, level = 0.9f, clarity = 0.9f)) }
        assertEquals("still nothing captured before J2 taps", 0, e.calibSamples)
    }

    @Test
    fun theFirstLevelIsGentle() {
        val e = playingEngine()
        // a full-length court crossing must take over two seconds at level 1
        val crossingSeconds = e.w / e.serveSpeed()
        assertTrue("level 1 crossing took $crossingSeconds s", crossingSeconds > 2.0f)
        val slow = e.serveSpeed()
        repeat(10) { forcePoint(e, 0) }
        assertTrue("still accelerates with levels", e.serveSpeed() > slow)
    }

    @Test
    fun aBallThatGoesThroughIsReplayedAtTheSameSideOnlyOnce() {
        val e = playingEngine()
        val startScore = e.score[0] + e.score[1]
        val passed = ArrayList<Int>()
        e.onPass = { passed.add(if (e.bx < 800f) 0 else 1) }
        // Let the ball pass the receiver each time, never filling a racket.
        var guard = 0
        while (passed.size < 2 && guard++ < 4000) {
            e.pads[0].charge = 0f
            e.pads[1].charge = 0f
            e.update(1f / 60f, VoiceFrame())
        }
        assertEquals("two balls were let through", 2, passed.size)
        // the pass is seen at the racket, the point lands when the ball leaves the court
        guard = 0
        while (e.phase == Phase.PLAY && guard++ < 600) e.update(1f / 60f, VoiceFrame())
        assertEquals("both points went to the other player", startScore + 2, e.score[0] + e.score[1])
        assertNotEquals(
            "the serve must alternate away from the loser (passed seats: $passed)",
            passed[0], passed[1]
        )
    }

    @Test
    fun receiverIsAlwaysLiveWhileTheBallComesAtHim() {
        val e = playingEngine()
        var guard = 0
        // whatever the flight, the seat with the microphone is the one being aimed at
        repeat(30) {
            while (!e.isRunning() && guard++ < 200) e.update(0.05f, VoiceFrame())
            if (e.isRunning()) {
                if (e.bvx < 0f) assertEquals(0, e.activeSeat) else assertEquals(1, e.activeSeat)
            }
            e.pads[0].charge = 0f
            e.pads[1].charge = 0f
            e.update(1f / 60f, VoiceFrame())
        }
    }

    private fun forcePoint(e: GameEngine, scorer: Int) {
        var guard = 0
        while (e.phase != Phase.PLAY && guard++ < 500) e.update(0.05f, VoiceFrame())
        e.by = 450f
        e.bvx = 0f
        e.bvy = 0f
        e.bx = if (scorer == 0) e.w + 200f else -200f
        e.update(0.016f, VoiceFrame())
    }

    @Test
    fun pitchMappingIsDirectionalAndDeadZoned() {
        val e = GameEngine()
        // no movement around the player's own pitch
        assertEquals(0f, e.pitchToMove(175f, 175f), 0.0001f)
        assertEquals(0f, e.pitchToMove(181f, 175f), 0.0001f) // +0.58 semitone
        // higher pitch pushes up, lower pitch pushes down
        assertTrue(e.pitchToMove(260f, 175f) > 0f)
        assertTrue(e.pitchToMove(120f, 175f) < 0f)
        // cap at full speed, and monotonic in between
        assertEquals(1f, e.pitchToMove(400f, 175f), 0.0001f)
        assertEquals(-1f, e.pitchToMove(80f, 175f), 0.0001f)
        val a = e.pitchToMove(200f, 175f)
        val b = e.pitchToMove(230f, 175f)
        assertTrue(b > a)
        // unknown pitch never moves the racket
        assertEquals(0f, e.pitchToMove(0f, 175f), 0.0001f)
    }

    @Test
    fun chargedRacketReturnsTheBall() {
        val e = playingEngine()
        assertEquals(Phase.PLAY, e.phase)
        val p = e.pads[0]
        p.charge = 1f
        p.y = 450f
        e.by = 450f
        e.bx = e.leftFace + e.ballR + 6f
        e.bvx = -600f
        e.bvy = 0f
        val rallyBefore = e.rally
        e.update(0.016f, VoiceFrame())
        assertTrue("ball must bounce back to the right", e.bvx > 0f)
        assertEquals(rallyBefore + 1, e.rally)
    }

    @Test
    fun emptyRacketIsTransparentAndThePointIsLost() {
        val e = playingEngine()
        val p = e.pads[0]
        p.charge = 0f
        p.y = 450f
        e.by = 450f
        e.bx = e.leftFace + e.ballR + 6f
        e.bvx = -900f
        e.bvy = 0f
        var guard = 0
        while (e.phase == Phase.PLAY && guard++ < 200) e.update(0.016f, VoiceFrame())
        assertEquals("J1 must not score", 0, e.score[0])
        assertEquals("J2 must score", 1, e.score[1])
        assertTrue(p.passFlash > 0f)
    }

    @Test
    fun misalignedRacketMissesEvenWhenCharged() {
        val e = playingEngine()
        val p = e.pads[0]
        p.charge = 1f
        p.y = 100f // ball arrives at the bottom
        e.by = 800f
        e.bx = e.leftFace + e.ballR + 6f
        e.bvx = -900f
        e.bvy = 0f
        var guard = 0
        while (e.phase == Phase.PLAY && guard++ < 200) e.update(0.016f, VoiceFrame())
        assertEquals(1, e.score[1])
    }

    @Test
    fun chargeFollowsTheVoiceAndDecaysInSilence() {
        val e = playingEngine()
        val active = e.pads[e.activeSeat]
        val idle = e.pads[1 - e.activeSeat]
        active.charge = 0f
        idle.charge = 0f
        repeat(10) {
            e.update(0.016f, VoiceFrame(voiced = true, f0 = 230f, level = 0.9f, clarity = 0.9f))
        }
        assertTrue("active racket must fill up", active.charge > 0.2f)
        assertEquals("the idle racket must stay empty", 0f, idle.charge, 0.0001f)
        val charged = active.charge
        repeat(10) { e.update(0.016f, VoiceFrame()) }
        assertTrue("silence must empty the racket", active.charge < charged)
    }

    @Test
    fun silenceNeverMovesTheRacket() {
        val e = playingEngine()
        val active = e.pads[e.activeSeat]
        active.y = 450f
        val y0 = active.y
        repeat(30) { e.update(0.016f, VoiceFrame()) }
        assertEquals(y0, active.y, 0.001f)
    }

    @Test
    fun levelRisesEveryFiveBalls() {
        val e = playingEngine()
        assertEquals(1, e.level)
        repeat(4) { forcePoint(e, 1) }
        assertEquals("still level 1 after 4 balls", 1, e.level)
        forcePoint(e, 1)
        assertEquals("level 2 on the 5th ball", 2, e.level)
        repeat(5) { forcePoint(e, 1) }
        assertEquals(3, e.level)
        assertEquals(10, e.ballsPlayed)
    }

    @Test
    fun ballGetsFasterWithLevels() {
        val e = playingEngine()
        val v1 = e.serveSpeed()
        repeat(5) { forcePoint(e, 0) }
        val v2 = e.serveSpeed()
        assertTrue(v2 > v1)
        assertTrue(e.speedMult() <= Tuning.SPEED_CAP)
    }

    @Test
    fun ballStaysInsideTheWalls() {
        val e = playingEngine()
        e.bx = e.w / 2f
        e.by = 10f
        e.bvx = 300f
        e.bvy = -900f
        repeat(60) { e.update(0.016f, VoiceFrame()) }
        assertTrue(e.by >= e.ballR - 0.001f)
        assertTrue(e.by <= e.h - e.ballR + 0.001f)
    }

    @Test
    fun serveAlternatesToTheOtherPlayer() {
        val e = playingEngine()
        // J1 scores: the ball was lost on J2's side, so the next ball must go to J1
        val before = e.serveTo
        forcePoint(e, 0)
        assertNotEquals("never the same receiver twice", before, e.serveTo)
        var guard = 0
        while (e.phase != Phase.SERVE && guard++ < 200) e.update(0.05f, VoiceFrame())
        assertEquals("the ball flies towards the announced seat", e.serveTo, e.activeSeat)
        assertNotEquals(0f, e.bvx)
    }

    @Test
    fun pauseFreezesTheWorld() {
        val e = playingEngine()
        e.togglePause()
        assertEquals(Phase.PAUSED, e.phase)
        val x = e.bx
        val y = e.by
        repeat(10) { e.update(0.05f, VoiceFrame()) }
        assertEquals(x, e.bx, 0.001f)
        assertEquals(y, e.by, 0.001f)
        e.togglePause()
        assertEquals(Phase.PLAY, e.phase)
    }
}
