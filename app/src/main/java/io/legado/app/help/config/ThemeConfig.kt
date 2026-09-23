package io.legado.app.help.config

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.DisplayMetrics
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.toColorInt
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Theme
import io.legado.app.help.DefaultData
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.model.BookCover
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.hexString
import io.legado.app.utils.postEvent
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.stackBlur
import splitties.init.appCtx
import java.io.File

// 默认主题曾使用的主色与强调色，用于识别仍停留在旧默认配色上的用户。
private const val LEGACY_DEFAULT_PRIMARY_COLOR = "#795548"
private const val LEGACY_DEFAULT_ACCENT_DAY_COLOR = "#64B5F6"

@Keep
object ThemeConfig {
    const val configFileName = "themeConfig.json"
    val configFilePath = FileUtils.getPath(appCtx.filesDir, configFileName)

    val configList: ArrayList<Config> by lazy {
        val savedConfigs = getConfigs()
        val configs = ArrayList(savedConfigs ?: DefaultData.themeConfigs)
        if (LocalConfig.getInt(DEFAULT_THEME_VERSION_KEY, 0) < DEFAULT_THEME_VERSION) {
            val merged = mergeDefaultThemeConfigs(configs, DefaultData.themeConfigs)
            configs.clear()
            configs.addAll(merged)
            if (savedConfigs != null) {
                save(configs)
            }
            LocalConfig.edit()
                .putInt(DEFAULT_THEME_VERSION_KEY, DEFAULT_THEME_VERSION)
                .apply()
        }
        configs
    }

    fun getTheme() = when {
        AppConfig.isEInkMode -> Theme.EInk
        AppConfig.isNightTheme -> Theme.Dark
        else -> Theme.Light
    }

    fun isDarkTheme(): Boolean {
        return getTheme() == Theme.Dark
    }

    fun applyDayNight(context: Context) {
        applyTheme(context)
        initNightMode()
        BookCover.upDefaultCover()
        postEvent(EventBus.RECREATE, "")
    }

    fun applyDayNightInit(context: Context) {
        applyTheme(context)
        initNightMode()
    }

    private fun initNightMode() {
        val targetMode =
            if (AppConfig.isNightTheme) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
        AppCompatDelegate.setDefaultNightMode(targetMode)
    }

    fun getBgImage(context: Context, metrics: DisplayMetrics): Bitmap? {
        val bgCfg = when (getTheme()) {
            Theme.Light -> Pair(
                context.getPrefString(PreferKey.bgImage),
                context.getPrefInt(PreferKey.bgImageBlurring, 0)
            )

            Theme.Dark -> Pair(
                context.getPrefString(PreferKey.bgImageN),
                context.getPrefInt(PreferKey.bgImageNBlurring, 0)
            )

            else -> null
        } ?: return null
        if (bgCfg.first.isNullOrBlank()) return null
        val bgImage = BitmapUtils
            .decodeBitmap(bgCfg.first!!, metrics.widthPixels, metrics.heightPixels)
        if (bgCfg.second == 0) {
            return bgImage
        }
        return bgImage?.stackBlur(bgCfg.second)
    }

    fun upConfig() {
        getConfigs()?.forEach { config ->
            addConfig(config)
        }
    }

    fun save() {
        save(configList)
    }

    private fun save(configs: List<Config>) {
        val json = GSON.toJson(configs)
        FileUtils.delete(configFilePath)
        FileUtils.createFileIfNotExist(configFilePath).writeText(json)
    }

    fun delConfig(index: Int) {
        configList.removeAt(index)
        save()
    }

    fun addConfig(json: String): Boolean {
        GSON.fromJsonObject<Config>(json.trim { it < ' ' }).getOrNull()
            ?.let {
                if (validateConfig(it)) {
                    addConfig(it)
                    return true
                }
            }
        return false
    }

    fun addConfig(newConfig: Config) {
        if (!validateConfig(newConfig)) {
            return
        }
        configList.forEachIndexed { index, config ->
            if (newConfig.themeName == config.themeName) {
                configList[index] = newConfig
                return
            }
        }
        configList.add(newConfig)
        save()
    }

    private fun validateConfig(config: Config): Boolean {
        try {
            config.primaryColor.toColorInt()
            config.accentColor.toColorInt()
            config.backgroundColor.toColorInt()
            config.bottomBackground.toColorInt()
            return true
        } catch (_: Exception) {
            return false
        }
    }

    private fun getConfigs(): List<Config>? {
        val configFile = File(configFilePath)
        if (configFile.exists()) {
            kotlin.runCatching {
                val json = configFile.readText()
                return GSON.fromJsonArray<Config>(json).getOrThrow()
            }.onFailure {
                it.printOnDebug()
            }
        }
        return null
    }

    fun applyConfig(context: Context, config: Config) {
        try {
            val primary = Color.parseColor(config.primaryColor)
            val accent = Color.parseColor(config.accentColor)
            val background = Color.parseColor(config.backgroundColor)
            val bBackground = Color.parseColor(config.bottomBackground)
            if (config.isNightTheme) {
                context.putPrefInt(PreferKey.cNPrimary, primary)
                context.putPrefInt(PreferKey.cNAccent, accent)
                context.putPrefInt(PreferKey.cNBackground, background)
                context.putPrefInt(PreferKey.cNBBackground, bBackground)
            } else {
                context.putPrefInt(PreferKey.cPrimary, primary)
                context.putPrefInt(PreferKey.cAccent, accent)
                context.putPrefInt(PreferKey.cBackground, background)
                context.putPrefInt(PreferKey.cBBackground, bBackground)
            }
            val nightModeChanged = AppConfig.isNightTheme != config.isNightTheme
            AppConfig.isNightTheme = config.isNightTheme
            applyDayNight(context)
            //夜间模式未变时 applyDayNight 不会触发系统重建,需额外通知所有界面刷新
            if (!nightModeChanged) {
                postEvent(EventBus.UP_THEME, "")
            }
        } catch (e: Exception) {
            AppLog.put("设置主题出错\n$e", e, true)
        }
    }

    fun saveDayTheme(context: Context, name: String) {
        val primary =
            context.getPrefInt(PreferKey.cPrimary, context.getCompatColor(R.color.default_primary_day))
        val accent =
            context.getPrefInt(PreferKey.cAccent, context.getCompatColor(R.color.default_accent_day))
        val background =
            context.getPrefInt(PreferKey.cBackground, context.getCompatColor(R.color.md_grey_100))
        val bBackground =
            context.getPrefInt(PreferKey.cBBackground, context.getCompatColor(R.color.md_grey_200))
        val config = Config(
            themeName = name,
            isNightTheme = false,
            primaryColor = "#${primary.hexString}",
            accentColor = "#${accent.hexString}",
            backgroundColor = "#${background.hexString}",
            bottomBackground = "#${bBackground.hexString}"
        )
        addConfig(config)
    }

    fun saveNightTheme(context: Context, name: String) {
        val primary =
            context.getPrefInt(
                PreferKey.cNPrimary,
                context.getCompatColor(R.color.md_blue_grey_600)
            )
        val accent =
            context.getPrefInt(
                PreferKey.cNAccent,
                context.getCompatColor(R.color.default_accent_night)
            )
        val background =
            context.getPrefInt(PreferKey.cNBackground, context.getCompatColor(R.color.md_grey_900))
        val bBackground =
            context.getPrefInt(PreferKey.cNBBackground, context.getCompatColor(R.color.md_grey_850))
        val config = Config(
            themeName = name,
            isNightTheme = true,
            primaryColor = "#${primary.hexString}",
            accentColor = "#${accent.hexString}",
            backgroundColor = "#${background.hexString}",
            bottomBackground = "#${bBackground.hexString}"
        )
        addConfig(config)
    }

    /**
     * 更新主题
     */
    fun applyTheme(context: Context) = with(context) {
        migrateDefaultAccent(this)
        migrateDefaultThemeColors(this)
        when {
            AppConfig.isEInkMode -> {
                ThemeStore.editTheme(this)
                    .primaryColor(Color.WHITE)
                    .accentColor(Color.BLACK)
                    .backgroundColor(Color.WHITE)
                    .bottomBackground(Color.WHITE)
                    .apply()
            }

            AppConfig.isNightTheme -> {
                val primary =
                    getPrefInt(PreferKey.cNPrimary, getCompatColor(R.color.md_blue_grey_600))
                val accent =
                    getPrefInt(PreferKey.cNAccent, getCompatColor(R.color.default_accent_night))
                var background =
                    getPrefInt(PreferKey.cNBackground, getCompatColor(R.color.md_grey_900))
                if (ColorUtils.isColorLight(background)) {
                    background = getCompatColor(R.color.md_grey_900)
                    putPrefInt(PreferKey.cNBackground, background)
                }
                val bBackground =
                    getPrefInt(PreferKey.cNBBackground, getCompatColor(R.color.md_grey_850))
                ThemeStore.editTheme(this)
                    .primaryColor(ColorUtils.withAlpha(primary, 1f))
                    .accentColor(ColorUtils.withAlpha(accent, 1f))
                    .backgroundColor(ColorUtils.withAlpha(background, 1f))
                    .bottomBackground(ColorUtils.withAlpha(bBackground, 1f))
                    .apply()
            }

            else -> {
                val primary =
                    getPrefInt(PreferKey.cPrimary, getCompatColor(R.color.default_primary_day))
                val accent =
                    getPrefInt(PreferKey.cAccent, getCompatColor(R.color.default_accent_day))
                var background =
                    getPrefInt(PreferKey.cBackground, getCompatColor(R.color.md_grey_100))
                if (!ColorUtils.isColorLight(background)) {
                    background = getCompatColor(R.color.md_grey_100)
                    putPrefInt(PreferKey.cBackground, background)
                }
                val bBackground =
                    getPrefInt(PreferKey.cBBackground, getCompatColor(R.color.md_grey_200))
                ThemeStore.editTheme(this)
                    .primaryColor(ColorUtils.withAlpha(primary, 1f))
                    .accentColor(ColorUtils.withAlpha(accent, 1f))
                    .backgroundColor(ColorUtils.withAlpha(background, 1f))
                    .bottomBackground(ColorUtils.withAlpha(bBackground, 1f))
                    .apply()
            }
        }
    }

    private fun migrateDefaultAccent(context: Context) = with(context) {
        val migrationKey = "lightBlueDefaultAccentApplied"
        if (LocalConfig.getBoolean(migrationKey, false)) return@with
        // 先标记，避免偏好变更监听再次应用主题时重复迁移。
        LocalConfig.edit().putBoolean(migrationKey, true).apply()
        val dayDefaults = mapOf(
            PreferKey.cPrimary to R.color.md_brown_500,
            PreferKey.cAccent to R.color.md_red_600,
            PreferKey.cBackground to R.color.md_grey_100,
            PreferKey.cBBackground to R.color.md_grey_200
        )
        val nightDefaults = mapOf(
            PreferKey.cNPrimary to R.color.md_blue_grey_600,
            PreferKey.cNAccent to R.color.md_deep_orange_800,
            PreferKey.cNBackground to R.color.md_grey_900,
            PreferKey.cNBBackground to R.color.md_grey_850
        )
        // 四项配色全部匹配旧默认值才升级，保留自定义主题。
        if (dayDefaults.all { (key, color) ->
                getPrefInt(key, getCompatColor(color)) == getCompatColor(color)
            }) {
            putPrefInt(PreferKey.cAccent, getCompatColor(R.color.default_accent_day))
        }
        if (nightDefaults.all { (key, color) ->
                getPrefInt(key, getCompatColor(color)) == getCompatColor(color)
            }) {
            putPrefInt(PreferKey.cNAccent, getCompatColor(R.color.default_accent_night))
        }
    }

    /**
     * 默认配色升级：主色由棕色换成与强调色相配的蓝色，强调色也调浅一档。
     * 仅替换仍是旧默认值的项，保留用户自定义配色。
     */
    private fun migrateDefaultThemeColors(context: Context) = with(context) {
        val migrationKey = "blueDefaultThemeColorsApplied"
        if (LocalConfig.getBoolean(migrationKey, false)) return@with
        // 先标记，避免偏好变更监听再次应用主题时重复迁移。
        LocalConfig.edit().putBoolean(migrationKey, true).apply()
        val oldPrimary = LEGACY_DEFAULT_PRIMARY_COLOR.toColorInt()
        if (getPrefInt(PreferKey.cPrimary, oldPrimary) == oldPrimary) {
            putPrefInt(PreferKey.cPrimary, getCompatColor(R.color.default_primary_day))
        }
        val oldAccent = LEGACY_DEFAULT_ACCENT_DAY_COLOR.toColorInt()
        if (getPrefInt(PreferKey.cAccent, oldAccent) == oldAccent) {
            putPrefInt(PreferKey.cAccent, getCompatColor(R.color.default_accent_day))
        }
    }

    fun clearBg() {
        val bgImagePath = appCtx.getPrefString(PreferKey.bgImage)
        appCtx.externalFiles.getFile(PreferKey.bgImage).listFiles()?.forEach {
            if (it.absolutePath != bgImagePath) {
                it.delete()
            }
        }
        val bgImageNPath = appCtx.getPrefString(PreferKey.bgImageN)
        appCtx.externalFiles.getFile(PreferKey.bgImageN).listFiles()?.forEach {
            if (it.absolutePath != bgImageNPath) {
                it.delete()
            }
        }
    }

    @Keep
    data class Config(
        var themeName: String,
        var isNightTheme: Boolean,
        var primaryColor: String,
        var accentColor: String,
        var backgroundColor: String,
        var bottomBackground: String
    ) {

        override fun hashCode(): Int {
            return GSON.toJson(this).hashCode()
        }

        override fun equals(other: Any?): Boolean {
            other ?: return false
            if (other is Config) {
                return other.themeName == themeName
                        && other.isNightTheme == isNightTheme
                        && other.primaryColor == primaryColor
                        && other.accentColor == accentColor
                        && other.backgroundColor == backgroundColor
                        && other.bottomBackground == bottomBackground
            }
            return false
        }

    }

    private const val DEFAULT_THEME_VERSION_KEY = "defaultThemeVersion"
    private const val DEFAULT_THEME_VERSION = 3

}

internal fun mergeDefaultThemeConfigs(
    savedConfigs: List<ThemeConfig.Config>,
    defaultConfigs: List<ThemeConfig.Config>
): List<ThemeConfig.Config> = buildList {
    val legacyDefault = ThemeConfig.Config(
        themeName = "默认",
        isNightTheme = false,
        primaryColor = LEGACY_DEFAULT_PRIMARY_COLOR,
        accentColor = "#E53935",
        backgroundColor = "#F5F5F5",
        bottomBackground = "#EEEEEE"
    )
    val legacyLightBlueDefault = legacyDefault.copy(accentColor = LEGACY_DEFAULT_ACCENT_DAY_COLOR)
    val legacyDefaults = listOf(legacyDefault, legacyLightBlueDefault)
    val updatedDefault = defaultConfigs.firstOrNull { it.themeName == legacyDefault.themeName }
    addAll(savedConfigs.map { config ->
        // 仅更新未修改过的内置默认预设，保留同名自定义主题。
        if (config in legacyDefaults && updatedDefault != null) updatedDefault else config
    })
    val names = savedConfigs.mapTo(HashSet()) { it.themeName }
    defaultConfigs.forEach { config ->
        if (names.add(config.themeName)) {
            add(config)
        }
    }
}
