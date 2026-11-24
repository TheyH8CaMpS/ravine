package dev.veil.launcher.ui

import androidx.compose.ui.text.font.FontStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** ravine#15: one clear type system. A few named styles in Theme.kt, Manrope for the UI, no italics. */
class TypeSystemTest {
    @Test fun themeDefinesTheNamedStyles() {
        assertEquals(
            setOf("display", "nowPlaying", "title", "body", "label", "value", "eyebrow"),
            Type.all.keys,
        )
    }

    @Test fun noStyleIsItalic() {
        Type.all.forEach { (name, style) ->
            assertTrue("$name is italic", style.fontStyle != FontStyle.Italic)
        }
    }

    @Test fun serifOnlyForDisplayAndNowPlaying() {
        val serif = Type.all.filterValues { it.fontFamily == Display }.keys
        assertEquals(setOf("display", "nowPlaying"), serif)
        Type.all.filterKeys { it !in serif }.forEach { (name, style) ->
            assertEquals("$name should be Manrope", Manrope, style.fontFamily)
        }
    }

    /** Nothing sets italics or the serif by hand; screens use the named styles instead. */
    @Test fun screensDontUseItalicsOrTheSerifDirectly() {
        val sources = File("src/main/java/dev/veil/launcher").walk().filter { it.extension == "kt" }.toList()
        assertTrue("source files not found from ${File(".").absolutePath}", sources.isNotEmpty())
        val offenders = sources.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val bad = "FontStyle.Italic" in line || "fontFamily = Display" in line
                if (bad) "${f.name}:${i + 1}: ${line.trim()}" else null
            }
        }
        assertTrue("italic or hand-set serif:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
