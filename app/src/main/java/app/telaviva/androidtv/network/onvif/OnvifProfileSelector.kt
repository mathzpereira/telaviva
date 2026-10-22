package app.telaviva.androidtv.network.onvif

sealed interface ProfileSelectionResult {
    data class SelectedProfiles(val main: OnvifProfile, val secondary: OnvifProfile?) : ProfileSelectionResult

    data object UnsupportedCodec : ProfileSelectionResult
}

object OnvifProfileSelector {
    fun select(profiles: List<OnvifProfile>): ProfileSelectionResult {
        val h264Profiles = profiles
            .filter { it.encoding.equals(H264_ENCODING, ignoreCase = true) && it.token.isNotBlank() }
            .distinctBy(OnvifProfile::token)
        if (h264Profiles.isEmpty()) return ProfileSelectionResult.UnsupportedCodec

        val sorted = h264Profiles.sortedWith(
            compareBy<OnvifProfile> { resolution(it) }
                .thenBy(OnvifProfile::token),
        )
        return ProfileSelectionResult.SelectedProfiles(
            main = sorted.last(),
            secondary = sorted.dropLast(1).firstOrNull(),
        )
    }

    private fun resolution(profile: OnvifProfile): Long =
        (profile.width?.coerceAtLeast(0)?.toLong() ?: 0L) *
            (profile.height?.coerceAtLeast(0)?.toLong() ?: 0L)

    private const val H264_ENCODING = "H264"
}
