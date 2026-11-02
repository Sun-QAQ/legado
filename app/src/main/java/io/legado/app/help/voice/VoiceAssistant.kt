package io.legado.app.help.voice

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.help.book.isNotShelf
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.welcome.WelcomeActivity
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/** Process coordinator; holds no Activity after pause and never records in the background. */
object VoiceAssistant : Application.ActivityLifecycleCallbacks,
    SharedPreferences.OnSharedPreferenceChangeListener {
    const val ENABLED = "voiceAssistantEnabled"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "voice-assistant") }
        .asCoroutineDispatcher()
    private var host = WeakReference<Activity>(null)
    val activity: Activity? get() = host.get()
    @Volatile private var engine: OfflineVoiceEngine? = null
    private var session: Job? = null
    private var execution: Job? = null
    private var cleanup: Job? = null
    private var speaker: VoiceSpeaker? = null
    private var overlay = WeakReference<View>(null)
    private var status: String? = null
    private var statusView = WeakReference<TextView>(null)
    private var renderedChoices = emptyList<VoicePlanChoice>()
    private var choices = emptyList<VoicePlanChoice>()
    private var interpreting = false
    private var pausedBookUrl: String? = null
    private var installed = false
    private var turnSerial = 0L

    fun install(application: Application) {
        if (installed) return
        installed = true
        application.registerActivityLifecycleCallbacks(this)
        application.defaultSharedPreferences.registerOnSharedPreferenceChangeListener(this)
        ContextCompat.registerReceiver(application, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                stopAll()
                if (intent.action == Intent.ACTION_USER_PRESENT) startSession()
            }
        }, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun available(): Boolean {
        val current = activity ?: return false
        if (current is WelcomeActivity) return false
        if (!appCtx.getPrefBoolean(ENABLED) || current.isFinishing || current.isDestroyed) return false
        if (current is MainActivity && !current.voiceAssistantReady) return false
        if ((appCtx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) return false
        if (!(appCtx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive) return false
        return ContextCompat.checkSelfPermission(appCtx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (interpreting && key in setOf(VoiceAiInterpreter.SOURCE_ID, VoiceAiInterpreter.MODEL)) {
            cancel()
        }
        if (key == ENABLED) {
            if (appCtx.getPrefBoolean(ENABLED)) startSession() else stopAll()
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is BaseActivity<*>) return
        cleanup?.cancel()
        host = WeakReference(activity)
        if (appCtx.getPrefBoolean(ENABLED) && ContextCompat.checkSelfPermission(appCtx,
                Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            appCtx.putPrefBoolean(ENABLED, false)
            appCtx.toastOnUi(R.string.voice_assistant_permission)
        }
        render()
        startSession()
    }

    override fun onActivityPaused(activity: Activity) {
        if (this.activity !== activity) return
        host.clear()
        removeOverlay()
        stopSession()
        cleanup?.cancel()
        cleanup = scope.launch {
            delay(700) // Allow an in-app Activity handoff without reloading both models.
            if (this@VoiceAssistant.activity == null) stopAll()
        }
    }

    private fun stopSession() {
        session?.cancel()
        session = null
        engine?.stopCapture()
    }

    private fun stopAll() {
        turnSerial++
        interpreting = false
        stopSession()
        execution?.cancel()
        execution = null
        speaker?.close()
        speaker = null
        choices = emptyList()
        pausedBookUrl = null
        status = null
        removeOverlay()
        scope.launch(worker) { engine?.close(); engine = null }
    }

    /** Settings also exposes a tap-to-talk entry for accessibility and wake-word testing. */
    fun listenNow() {
        if (!available() || execution?.isActive == true) return
        stopSession()
        startSession(skipWake = true)
    }

    fun cancel() {
        turnSerial++
        interpreting = false
        stopSession()
        execution?.cancel()
        execution = null
        speaker?.close()
        speaker = null
        choices = emptyList()
        finishTurn()
        startSession()
    }

    fun refresh() { startSession() }

    private fun startSession(skipWake: Boolean = false) {
        if (session?.isActive == true || execution?.isActive == true || !available()) return
        val current = activity
        session = scope.launch {
            try {
                show("正在准备离线语音…")
                withContext(worker) {
                    if (engine == null) engine = OfflineVoiceEngine(appCtx)
                    engine!!.prepare()
                }
                if (!isActive || activity !== current || !available()) return@launch
                if (!skipWake) {
                    show(null)
                    val job = coroutineContext[Job]!!
                    withContext(worker) { engine!!.awaitWake { job.isActive } }
                }
                if (!available() || activity !== current) return@launch
                if (BaseReadAloudService.isPlay()) {
                    pausedBookUrl = ReadBook.book?.bookUrl
                    ReadAloud.pause(appCtx)
                }
                show("我在听，请说出请求")
                speak("我在听")
                val job = coroutineContext[Job]!!
                val names = shelfHotwords()
                val text = withContext(worker) {
                    engine!!.recognize({ job.isActive }, names) { partial ->
                        scope.launch { if (job.isActive) show("正在听：$partial") }
                    }
                }
                if (!available() || activity !== current) return@launch
                acceptRecognizedText(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.put("离线语音助手启动或识别失败", e)
                appCtx.toastOnUi("语音助手已停止：${e.localizedMessage ?: "语音引擎不可用"}")
                appCtx.putPrefBoolean(ENABLED, false)
            }
        }
    }

    internal fun acceptRecognizedText(text: String) {
        if (!available() || execution?.isActive == true) return
        stopSession()
        executeText(text)
    }

    private fun executeText(text: String, chosen: List<VoiceCommand>? = null) {
        val serial = ++turnSerial
        execution = scope.launch {
            try {
                show(if (text.isBlank()) "没有听清，请再说一次" else "正在处理：$text")
                var outcome = if (chosen != null) executePlan(chosen) else resolveRequest(text)
                repeat(3) { round ->
                    choices = outcome.choices
                    show(outcome.message)
                    speak(outcome.message + if (choices.isEmpty()) "" else choices.mapIndexed { index, choice ->
                        "第${index + 1}个，${choice.label.take(60)}"
                    }.joinToString("。", prefix = "。"))
                    if (choices.isEmpty() || !available()) return@launch
                    if (round == 2) {
                        choices = emptyList()
                        show("请补充更完整的书名后重新唤醒")
                        speak("请补充更完整的书名后重新唤醒")
                        return@launch
                    }
                    val job = coroutineContext[Job]!!
                    val names = shelfHotwords()
                    show("请说第几个，或点击选择")
                    val answer = withContext(worker) {
                        engine!!.recognize({ job.isActive }, names) { partial ->
                            scope.launch { if (job.isActive) show("正在听：$partial") }
                        }
                    }
                    val number = VoiceCommandParser.choice(answer)
                    val selected = number?.let { choices.getOrNull(it - 1) }
                    choices = emptyList()
                    outcome = if (selected == null) VoicePlanOutcome("已取消选择，请重新唤醒")
                        else executePlan(selected.commands)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = (e as? VoiceAiException)?.message ?: "操作未完成，请重试"
                show(message)
                if (e !is VoiceAiException) AppLog.put("语音指令执行失败", e)
                speak(message)
            } finally {
                if (serial == turnSerial) {
                    interpreting = false
                    choices = emptyList()
                    finishTurn()
                    execution = null
                    session = null
                    startSession()
                }
            }
        }
    }

    private suspend fun executePlan(commands: List<VoiceCommand>): VoicePlanOutcome =
        VoicePlanRunner.run(commands, VoiceActions::execute) { index, total ->
            show("正在执行第${index}步，共${total}步")
        }

    private suspend fun resolveRequest(text: String): VoicePlanOutcome {
        if (text.isBlank()) return VoicePlanOutcome("没有听清，请唤醒后再说一次")
        if (VoiceCommandParser.isNegated(text)) return VoicePlanOutcome("未执行被否定的操作，请重新说出需要的操作")
        val local = VoiceCommandParser.parse(text)
        if (local != null) {
            val result = VoiceActions.execute(local)
            // Literal-looking requests such as “打开辰东写的小说” may still need semantic matching.
            if (result.status != VoiceActionStatus.FAILED ||
                (local !is VoiceCommand.OpenBook && local !is VoiceCommand.SwitchGroup)) {
                return VoicePlanRunner.run(listOf(local), { result })
            }
        }
        val origin = activity
        val bookUrl = if (origin is ReadBookActivity) ReadBook.book?.bookUrl else null
        val groupId = (origin as? MainActivity)?.voiceBookshelf()?.groupId
        val page = (origin as? MainActivity)?.voicePageName()
        val serial = turnSerial
        interpreting = true
        show("正在联网理解请求，可点击取消")
        val decision = try { VoiceAiInterpreter.interpret(text) } finally {
            if (serial == turnSerial) interpreting = false
        }
        if (!available() || origin !== activity ||
            (origin is ReadBookActivity && bookUrl != ReadBook.book?.bookUrl) ||
            (origin is MainActivity && (groupId != origin.voiceBookshelf()?.groupId || page != origin.voicePageName()))) {
            return VoicePlanOutcome("页面已变化，请重新发起语音请求")
        }
        return when (decision) {
            is VoiceAiDecision.Execute -> executePlan(decision.commands)
            is VoiceAiDecision.Clarify -> VoicePlanOutcome("请确认：${decision.question}", decision.options)
            is VoiceAiDecision.Unsupported -> VoicePlanOutcome("暂不能完成：${decision.message}")
        }
    }

    private suspend fun speak(text: String) {
        val voice = speaker ?: VoiceSpeaker(appCtx).also { speaker = it }
        voice.speak(text)
        delay(180) // Avoid recognizing the speaker's remaining playback tail.
    }

    private suspend fun shelfHotwords(): List<String> = withContext(Dispatchers.IO) {
        appDb.bookDao.all.filterNot { it.isNotShelf }.sortedByDescending { it.durChapterTime }
            .take(150).map { it.name } + appDb.bookGroupDao.all.take(50).map { it.groupName }
    }

    private fun finishTurn() {
        status = null
        removeOverlay()
        if (pausedBookUrl != null && pausedBookUrl == ReadBook.book?.bookUrl &&
            activity is ReadBookActivity && available()) ReadAloud.resume(appCtx)
        pausedBookUrl = null
    }

    private fun show(message: String?) { status = message; render() }

    private fun removeOverlay() {
        overlay.get()?.let { (it.parent as? ViewGroup)?.removeView(it) }
        overlay.clear()
        statusView.clear()
        renderedChoices = emptyList()
    }

    private fun render() {
        val current = activity ?: return
        val text = status ?: run { removeOverlay(); return }
        val root = current.findViewById<FrameLayout>(android.R.id.content) ?: return
        if (overlay.get()?.parent === root && choices == renderedChoices) {
            statusView.get()?.text = text
            return
        }
        removeOverlay()
        fun dp(value: Int) = (value * current.resources.displayMetrics.density).toInt()
        val card = LinearLayout(current).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            elevation = dp(8).toFloat()
            background = GradientDrawable().apply {
                setColor(Color.rgb(35, 45, 58))
                cornerRadius = dp(16).toFloat()
            }
        }
        card.addView(TextView(current).apply {
            setText(text)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_voice_assistant, 0, 0, 0)
            compoundDrawablePadding = dp(8)
            setTextColor(Color.WHITE)
            textSize = 15f
            maxWidth = dp(260)
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
            statusView = WeakReference(this)
        })
        choices.forEach { choice ->
            card.addView(Button(current).apply {
                this.text = choice.label
                maxWidth = dp(260)
                setOnClickListener {
                    turnSerial++
                    stopSession()
                    execution?.cancel()
                    speaker?.close()
                    speaker = null
                    executeText("", choice.commands)
                }
            })
        }
        card.addView(Button(current).apply {
            setText(R.string.cancel)
            contentDescription = "取消语音监听"
            minHeight = dp(40)
            setOnClickListener { cancel() }
        })
        val bottomInset = ViewCompat.getRootWindowInsets(root)?.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
        )?.bottom ?: 0
        val scroll = ScrollView(current).apply { addView(card) }
        root.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END
        ).apply { bottomMargin = dp(80) + bottomInset; marginEnd = dp(12) })
        overlay = WeakReference(scroll)
        renderedChoices = choices
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
