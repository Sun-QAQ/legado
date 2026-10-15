package io.legado.app

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.ThemePalette
import io.legado.app.ui.config.ConfigActivity
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeSwitchTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun themeMenuRefreshesMyPageWhenReturningFromSettings() {
        val context = instrumentation.targetContext
        val originalMode = context.getPrefString(PreferKey.themeMode)
        val originalGroupStyle = AppConfig.bookGroupStyle
        try {
            instrumentation.runOnMainSync {
                AppConfig.bookGroupStyle = 1
                AppConfig.isNightTheme = false
                ThemeConfig.applyDayNight(context)
            }
            instrumentation.waitForIdleSync()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    it.findViewById<View>(R.id.menu_my_config).performClick()
                }
                for (night in listOf(true, false)) {
                    scenario.onActivity {
                        it.findViewById<View>(R.id.ll_theme).performClick()
                    }
                    awaitResumed(ConfigActivity::class.java, ready = {
                        it.findViewById<Toolbar>(R.id.toolbar).menu.findItem(R.id.menu_theme_mode) != null
                    }) {
                        // 通过菜单分发事件，避免模拟器窗口转场期间丢弃触摸输入。
                        it.findViewById<Toolbar>(R.id.toolbar).menu
                            .performIdentifierAction(R.id.menu_theme_mode, 0)
                    }
                    instrumentation.waitForIdleSync()
                    awaitResumed(ConfigActivity::class.java) {
                        assertEquals(night, AppConfig.isNightTheme)
                    }
                    Espresso.pressBack()
                    awaitResumed(MainActivity::class.java) { activity ->
                        val palette = ThemePalette(activity)
                        assertEquals("Summary must follow the selected theme", palette.secondaryText,
                            activity.findViewById<TextView>(R.id.tv_web_service_summary).currentTextColor)
                        assertEquals("Stats must follow the selected theme", palette.accent,
                            activity.findViewById<TextView>(R.id.tv_reading_time).currentTextColor)
                        val statCard = activity.findViewById<TextView>(R.id.tv_reading_time).parent as View
                        assertEquals(palette.surface, (statCard.background as GradientDrawable).color!!.defaultColor)
                        val icon = activity.findViewById<ImageView>(R.id.iv_web_service_icon)
                        assertEquals(palette.accent, icon.imageTintList!!.defaultColor)
                        assertEquals(palette.accentContainer, (icon.background as GradientDrawable).color!!.defaultColor)
                        val darkBackground = !io.legado.app.utils.ColorUtils.isColorLight(palette.background)
                        assertEquals(night, darkBackground)
                        assertTrue(activity.findViewById<View>(R.id.ll_theme).isShown)
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                context.putPrefString(PreferKey.themeMode, originalMode)
                AppConfig.bookGroupStyle = originalGroupStyle
                ThemeConfig.applyDayNight(context)
            }
        }
    }

    private fun <T : Activity> awaitResumed(
        type: Class<T>,
        ready: (T) -> Boolean = { true },
        action: (T) -> Unit
    ) {
        repeat(100) {
            var activity: T? = null
            instrumentation.runOnMainSync {
                activity = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).firstOrNull { type.isInstance(it) }
                    ?.let(type::cast)?.takeIf(ready)
            }
            if (activity != null) {
                instrumentation.runOnMainSync { action(activity!!) }
                return
            }
            SystemClock.sleep(50)
        }
        throw AssertionError("${type.simpleName} did not resume")
    }
}
