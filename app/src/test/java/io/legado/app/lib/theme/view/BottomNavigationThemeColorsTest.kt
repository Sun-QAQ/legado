package io.legado.app.lib.theme.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BottomNavigationThemeColorsTest {

    @Test
    fun `主题颜色改变后重新计算导航栏全部颜色`() {
        val accent = 0xFF336699.toInt()
        val lightColors = resolveBottomNavigationThemeColors(
            accent = accent,
            isLightBackground = true,
            defaultItemColor = 0xFF666666.toInt()
        )
        val darkColors = resolveBottomNavigationThemeColors(
            accent = accent,
            isLightBackground = false,
            defaultItemColor = 0xFFBBBBBB.toInt()
        )

        assertEquals(0xFFD6E0EA.toInt(), lightColors.activeIndicatorColor)
        assertEquals(0xFF112335.toInt(), darkColors.activeIndicatorColor)
        assertEquals(accent, darkColors.selectedItemColor)
        assertEquals(0xFFBBBBBB.toInt(), darkColors.defaultItemColor)
        assertNotEquals(lightColors, darkColors)
    }
}
