package io.legado.app.help.config

import io.legado.app.utils.GSON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.pow

class DefaultThemeConfigTest {

    @Test
    fun `默认主题包含撞色糖果色和多巴胺配色`() {
        val configs = readDefaultThemes()
        val expectedNames = setOf(
            "撞色·赤陶青绿",
            "撞色·钴蓝暖橙",
            "撞色·紫金夜宴",
            "糖果·草莓奶糖",
            "糖果·薄荷蜜桃",
            "糖果·蓝莓棉花",
            "多巴胺·晴空玫红",
            "多巴胺·霓虹青柠"
        )

        assertTrue(configs.map { it.themeName }.containsAll(expectedNames))
        assertEquals(configs.size, configs.map { it.themeName }.distinct().size)
        configs.forEach { config ->
            listOf(
                config.primaryColor,
                config.accentColor,
                config.backgroundColor,
                config.bottomBackground
            ).forEach { color ->
                assertTrue("无效色值: $color", color.matches(Regex("#[0-9A-Fa-f]{6}")))
            }
            val backgroundIsLight = luminance(config.backgroundColor) >= 0.5
            val bottomIsLight = luminance(config.bottomBackground) >= 0.5
            assertEquals("背景明暗与主题类型不符: ${config.themeName}", !config.isNightTheme, backgroundIsLight)
            assertEquals("底栏明暗与主题类型不符: ${config.themeName}", !config.isNightTheme, bottomIsLight)
        }
    }

    @Test
    fun `升级合并默认主题时保留用户主题和同名自定义配置`() {
        val customizedDefault = ThemeConfig.Config(
            themeName = "默认",
            isNightTheme = false,
            primaryColor = "#123456",
            accentColor = "#654321",
            backgroundColor = "#FFFFFF",
            bottomBackground = "#EEEEEE"
        )
        val custom = customizedDefault.copy(themeName = "我的主题")

        val merged = mergeDefaultThemeConfigs(
            savedConfigs = listOf(customizedDefault, custom),
            defaultConfigs = readDefaultThemes()
        )

        assertEquals(customizedDefault, merged.first { it.themeName == "默认" })
        assertTrue(merged.contains(custom))
        assertTrue(merged.any { it.themeName == "多巴胺·霓虹青柠" })
        assertEquals(merged.size, merged.map { it.themeName }.distinct().size)
    }

    @Test
    fun `升级旧默认预设且重复合并不改变结果`() {
        val defaults = readDefaultThemes()
        val updatedDefault = defaults.first { it.themeName == "默认" }
        val legacyDefault = updatedDefault.copy(
            primaryColor = "#795548",
            accentColor = "#E53935"
        )
        val custom = legacyDefault.copy(themeName = "我保存的红色主题")

        val merged = mergeDefaultThemeConfigs(listOf(legacyDefault, custom), defaults)

        assertEquals(updatedDefault, merged.first { it.themeName == "默认" })
        assertTrue(merged.contains(custom))
        assertEquals(merged, mergeDefaultThemeConfigs(merged, defaults))
    }

    @Test
    fun `升级浅蓝强调色的旧默认预设`() {
        val defaults = readDefaultThemes()
        val updatedDefault = defaults.first { it.themeName == "默认" }
        val legacyLightBlueDefault = updatedDefault.copy(
            primaryColor = "#795548",
            accentColor = "#64B5F6"
        )

        val merged = mergeDefaultThemeConfigs(listOf(legacyLightBlueDefault), defaults)

        assertEquals(updatedDefault, merged.first { it.themeName == "默认" })
    }

    private fun readDefaultThemes(): List<ThemeConfig.Config> {
        val file = sequenceOf(
            File("src/main/assets/defaultData/themeConfig.json"),
            File("app/src/main/assets/defaultData/themeConfig.json")
        ).first { it.exists() }
        return GSON.fromJson(file.readText(), Array<ThemeConfig.Config>::class.java).toList()
    }

    private fun luminance(color: String): Double {
        val rgb = color.removePrefix("#").chunked(2).map { it.toInt(16) / 255.0 }
        val linear = rgb.map { channel ->
            if (channel <= 0.03928) channel / 12.92
            else ((channel + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]
    }
}
