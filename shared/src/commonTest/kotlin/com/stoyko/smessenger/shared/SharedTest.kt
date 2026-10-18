package com.stoyko.smessenger.shared

import com.stoyko.smessenger.shared.audio.Season
import com.stoyko.smessenger.shared.bubbles.BubbleBridgePolicy
import com.stoyko.smessenger.shared.bubbles.BubbleCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedTest {
    @Test
    fun bubbleRoundTrip() {
        val html = "<div class=card><div class=pic>🎁</div><button class=b data-reply=\"Да\">Да</button></div>"
        assertEquals(html, BubbleCodec.unpack(BubbleCodec.pack(html)))
    }

    @Test
    fun brokenBubbleIsNull() {
        assertNull(BubbleCodec.unpack("not base64 !!!"))
    }

    @Test
    fun seasons() {
        assertEquals(Season.WINTER, Season.forMonth(1))
        assertEquals(Season.SPRING, Season.forMonth(4))
        assertEquals(Season.SUMMER, Season.forMonth(7))
        assertEquals(Season.AUTUMN, Season.forMonth(10))
    }

    @Test
    fun replyNeedsGesture() {
        val p = BubbleBridgePolicy()
        assertNull(p.acceptReply("hi", touchAgeMs = -1))
        assertEquals("hi", p.acceptReply("  hi ", touchAgeMs = 100))
        assertNull(p.acceptReply("again", touchAgeMs = 100)) // чаще раза в секунду - нельзя
        assertNull(p.acceptUrl("javascript:alert(1)", touchAgeMs = 100))
    }
}
