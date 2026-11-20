package io.itermux.app.ui

import androidx.compose.ui.geometry.Offset
import io.itermux.app.AppLanguage
import io.itermux.app.AppLocale
import io.itermux.app.EnglishText
import io.itermux.app.session.ScreenApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PetShortcutArcTest {
    @Test fun roomyViewportUsesTheFullHalfTurnAtTheLargestRadius() {
        val arc = petArcLayout(8, centerY = 800f, top = 0f, bottom = 2000f, maxRadius = 330f, minRadius = 210f, spacing = 125f)
        assertEquals(330f, arc.radius)
        // 80° either side of straight left, top item first.
        assertEquals(260f, arc.angles.first(), 0.01f)
        assertEquals(100f, arc.angles.last(), 0.01f)
        assertEquals(180f, (arc.angles[3] + arc.angles[4]) / 2, 0.01f)
    }

    @Test fun everyItemStaysInsideTheViewport() {
        // A short landscape viewport with the pet in its upper part.
        val arc = petArcLayout(8, centerY = 340f, top = 120f, bottom = 820f, maxRadius = 330f, minRadius = 210f, spacing = 125f)
        repeat(8) { i ->
            val y = 340f + arc.itemOffset(i).y
            assertTrue("item $i at $y", y in 119.9f..820.1f)
            assertTrue(arc.itemOffset(i).x <= 0.01f)
        }
    }

    @Test fun shortViewportShrinksTheCircle() {
        // 200px either side: a smaller circle keeps more of its half-turn on screen.
        val arc = petArcLayout(8, centerY = 300f, top = 100f, bottom = 500f, maxRadius = 330f, minRadius = 210f, spacing = 125f)
        assertTrue(arc.radius < 330f)
        assertTrue(arc.angles.first() - arc.angles.last() > 100f)
    }

    @Test fun itemsRunTopToBottomOnTheLeft() {
        val arc = petArcLayout(8, centerY = 800f, top = 0f, bottom = 2000f, maxRadius = 330f, minRadius = 210f, spacing = 125f)
        val ys = arc.angles.indices.map { arc.itemOffset(it).y }
        assertEquals(ys.sorted(), ys)
    }

    @Test fun hoverFollowsTheDragDirection() {
        val arc = petArcLayout(8, centerY = 800f, top = 0f, bottom = 2000f, maxRadius = 330f, minRadius = 210f, spacing = 125f)
        arc.angles.indices.forEach { i -> assertEquals(i, arc.hoveredItem(arc.itemOffset(i) * 0.6f)) }
        // Beyond the ring still counts.
        assertEquals(0, arc.hoveredItem(arc.itemOffset(0) * 1.5f))
    }

    @Test fun noHoverOverThePetOrAwayFromTheArc() {
        val arc = petArcLayout(8, centerY = 800f, top = 0f, bottom = 2000f, maxRadius = 330f, minRadius = 210f, spacing = 125f)
        assertNull(arc.hoveredItem(Offset(-40f, 10f)))
        assertNull(arc.hoveredItem(Offset(300f, 0f)))
    }

    @Test fun everyScreenAppHasTranslatedShortcuts() {
        val chinese = Regex("""[一-鿿]""")
        ScreenApp.entries.forEach { app ->
            val items = petShortcuts(app)
            assertTrue("$app has shortcuts", items.isNotEmpty())
            items.forEach { item ->
                if (AppLocale.language == AppLanguage.ENGLISH) {
                    assertTrue("untranslated hint in $app: ${item.hint}", !chinese.containsMatchIn(item.hint))
                    assertTrue("untranslated desc in $app: ${item.description}", !chinese.containsMatchIn(item.description))
                } else {
                    if (chinese.containsMatchIn(item.hint)) assertTrue("missing hint: ${item.hint}", item.hint in EnglishText.values)
                    if (chinese.containsMatchIn(item.description)) assertTrue("missing desc: ${item.description}", item.description in EnglishText.values)
                }
            }
        }
        assertTrue(petShortcuts(ScreenApp.AGY).any { it.tag == "pet-shift-tab" })
        assertTrue(petShortcuts(ScreenApp.AGY).any { it.tag == "pet-ctrl-r" })
    }
}
