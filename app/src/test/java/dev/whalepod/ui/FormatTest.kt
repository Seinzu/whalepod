package dev.whalepod.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test
    fun shortFeedLabels() {
        assertEquals("Ad-Free", shortFeedLabel("My Show (Ad-Free)", "My Show", "https://paid.example/f"))
        assertEquals("Patreon", shortFeedLabel("My Show - Patreon", "My Show", "https://x/f"))
        assertEquals("Bonus Club", shortFeedLabel("Bonus Club", "My Show", "https://x/f"))
        assertEquals("supercast.example", shortFeedLabel("My Show", "My Show", "https://www.supercast.example/feed"))
        assertEquals("x.example", shortFeedLabel(null, "My Show", "https://x.example/f"))
    }
}
