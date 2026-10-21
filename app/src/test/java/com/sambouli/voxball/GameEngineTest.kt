package com.sambouli.voxball

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GameEngineTest {

    private fun playingEngine(seed: Int = 7): GameEngine {
        val e = GameEngine()
        e.random = Random(seed)
        e.resize(1600f, 900f)
        var guard = 0
        while (e.phase == Phase.HINTS && guard++ < 40) e.advanceHint()
        e.skipCalibSeat()
        e.skipCalibSeat()
        guard = 0
        while (e.phase == Phase.SERVE && guard++ < 100) e.update(0.05f, VoiceFrame())
        return e
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
    fun serveGoesToThePlayerWhoJustLostThePoint() {
        val e = playingEngine()
        forcePoint(e, 0) // J1 scores, J2 lost
        assertEquals(1, e.serveTo)
        var guard = 0
        while (e.phase != Phase.SERVE && guard++ < 200) e.update(0.05f, VoiceFrame())
        assertEquals(1, e.serveTo)
        assertEquals("the receiver owns the voice", 1, e.activeSeat)
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
