package dev.veil.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistArtTest {
    @Test fun candidates() {
        assertEquals(listOf("Phoebe Bridgers, Julien Baker", "Phoebe Bridgers"), ArtistArt.candidates("Phoebe Bridgers, Julien Baker"))
        assertEquals(listOf("Simon & Garfunkel", "Simon"), ArtistArt.candidates("Simon & Garfunkel")) // whole name is tried first
        assertEquals(listOf("Drake feat. Future", "Drake"), ArtistArt.candidates("Drake feat. Future"))
        assertEquals(emptyList<String>(), ArtistArt.candidates("  "))
    }

    @Test fun namesMatchIgnoringCaseAndPunctuation() {
        assertEquals(ArtistArt.norm("Florence + the Machine"), ArtistArt.norm("florence the machine"))
        assertEquals(ArtistArt.norm("Simon & Garfunkel"), ArtistArt.norm("Simon and Garfunkel"))
    }
}
