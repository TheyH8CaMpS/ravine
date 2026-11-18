package dev.veil.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wallpaper feed parser against the real overflight.json shape (TheyH8CaMpS/shield-wallpaper-library). */
class WallpapersParseTest {
    private val overflight = """
        [
          {"location": "Moody green", "title": "Fog in the green valley", "author": "V2osk",
           "url_img": "https://images.unsplash.com/photo-1470071459604-3b5ec3a7fe05?fm=jpg&w=3840&h=2160&q=90"},
          {"location": "Dodgers", "title": "The Ravine at night", "author": null,
           "url_img": "https://upload.wikimedia.org/wikipedia/commons/thumb/f/f3/Dodger_Stadium.jpg/3840px-Dodger_Stadium.jpg"},
          {"location": "Broken", "title": "No image"},
          {"location": "Broken", "title": "Local path", "url_img": "/sdcard/x.jpg"}
        ]
    """.trimIndent()

    @Test fun parsesOverflightArray() {
        val list = Wallpapers.parse(overflight)
        assertEquals(2, list.size)
        assertEquals("Moody green", list[0].collection)
        assertEquals("Fog in the green valley", list[0].title)
        assertEquals("V2osk", list[0].author)
        assertTrue(list[0].url.startsWith("https://images.unsplash.com/"))
        assertNull(list[1].author)
    }

    @Test fun acceptsWrappedObject() {
        val list = Wallpapers.parse("""{"items":[{"title":"A","url":"https://x/a.jpg"}]}""")
        assertEquals(1, list.size)
        assertEquals("", list[0].collection)
    }
}
