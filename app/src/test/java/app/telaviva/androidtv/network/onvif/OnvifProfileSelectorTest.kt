package app.telaviva.androidtv.network.onvif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OnvifProfileSelectorTest {
    @Test
    fun selectsHighestResolutionMainAndLowestResolutionSecondary() {
        val main = OnvifProfile("main", "H264", 1920, 1080)
        val sub = OnvifProfile("sub", "H264", 640, 360)
        val profiles = listOf(main, sub, OnvifProfile("hevc", "H265", 3840, 2160))

        val result = OnvifProfileSelector.select(profiles)

        assertTrue(result is ProfileSelectionResult.SelectedProfiles)
        val selected = result as ProfileSelectionResult.SelectedProfiles
        assertSame(main, selected.main)
        assertSame(sub, selected.secondary)
    }

    @Test
    fun cameraWithoutSecondH264ProfileHasNoSubstream() {
        val profile = OnvifProfile("only", "H264", 1280, 720)

        val result = OnvifProfileSelector.select(listOf(profile))

        assertTrue(result is ProfileSelectionResult.SelectedProfiles)
        val selected = result as ProfileSelectionResult.SelectedProfiles
        assertEquals("only", selected.main.token)
        assertNull(selected.secondary)
    }

    @Test
    fun h265OnlyProfilesReturnUnsupportedCodec() {
        val result = OnvifProfileSelector.select(listOf(OnvifProfile("hevc", "H265", 1920, 1080)))

        assertSame(ProfileSelectionResult.UnsupportedCodec, result)
    }
}
