package io.legado.app.help.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppReleaseInfoTest {

    @Test
    fun apkVersionDoesNotIncludeFileExtension() {
        val release = AppReleaseInfo(
            AppVariant.OFFICIAL,
            0,
            "更新说明",
            "legado_app_3.26.092418.apk",
            "https://github.com/Sun-QAQ/legado/releases/download/v3.26.092418/legado_app_3.26.092418.apk",
            ""
        )

        assertEquals("3.26.092418", release.versionName)
        assertFalse(release.versionName > "3.26.092418")
        assertFalse(release.versionName > "3.26.092418debug")
        assertTrue(release.versionName > "3.26.092317debug")
    }
}
