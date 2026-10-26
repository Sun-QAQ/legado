package io.legado.app.model

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.Status
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.readSimulating
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.book.update
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.NativeVideoPlayback
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.VideoPlayService
import io.legado.app.utils.postEvent
import io.legado.app.utils.startService
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancelChildren
import splitties.init.appCtx
import java.util.regex.Pattern

@SuppressLint("StaticFieldLeak")
@Suppress("unused")
object VideoPlay : CoroutineScope by MainScope() {
    /**
     * 播放模式枚举
     */
    enum class PlayMode(val iconRes: Int) {
        LIST_END_STOP(R.drawable.ic_play_mode_list_end_stop),
        SINGLE_LOOP(R.drawable.ic_play_mode_single_loop),
        RANDOM(R.drawable.ic_play_mode_random),
        LIST_LOOP(R.drawable.ic_play_mode_list_loop);

        fun next(): PlayMode {
            return when (this) {
                LIST_END_STOP -> SINGLE_LOOP
                SINGLE_LOOP -> RANDOM
                RANDOM -> LIST_LOOP
                LIST_LOOP -> LIST_END_STOP
            }
        }
    }

    var playMode = PlayMode.LIST_END_STOP
    var status = Status.STOP
    private var activityContext: Context? = null
    private var serviceContext: Context? = null
    private val context: Context get() = activityContext ?: serviceContext ?: appCtx
    var callback: CallBack? = null
    var book: Book? = null
    var chapterSize = 0
    var simulatedChapterSize = 0
    var durChapterIndex = 0
    var durChapterPos = 0
    var durChapter: BookChapter? = null
    var durPlayUrl = ""
    var durVideoSize = 0
    var inBookshelf = false
    var bookSource: BookSource? = null
    val loadingChapters = arrayListOf<Int>()

    fun changePlayMode() {
        playMode = playMode.next()
        postEvent(EventBus.PLAY_MODE_CHANGED, playMode)
    }

    fun upData(book: Book) {
        VideoPlay.book = book
        chapterSize = appDb.bookChapterDao.getChapterCount(book.bookUrl)
        simulatedChapterSize = if (book.readSimulating()) {
            book.simulatedTotalChapterNum()
        } else {
            chapterSize
        }
        if (durChapterIndex != book.durChapterIndex) {
            stopPlay()
            durChapterIndex = book.durChapterIndex
            durChapterPos = book.durChapterPos
            durPlayUrl = ""
            durVideoSize = 0
        }
        upDurChapter()
    }

    fun resetData(book: Book) {
        stop()
        VideoPlay.book = book
        chapterSize = appDb.bookChapterDao.getChapterCount(book.bookUrl)
        simulatedChapterSize = if (book.readSimulating()) {
            book.simulatedTotalChapterNum()
        } else {
            chapterSize
        }
        bookSource = book.getBookSource()
        durChapterIndex = book.durChapterIndex
        durChapterPos = book.durChapterPos
        durPlayUrl = ""
        durVideoSize = 0
        upDurChapter()
        postEvent(EventBus.AUDIO_BUFFER_PROGRESS, 0)
    }

    private fun addLoading(index: Int): Boolean {
        synchronized(this) {
            if (loadingChapters.contains(index)) return false
            loadingChapters.add(index)
            return true
        }
    }

    private fun removeLoading(index: Int) {
        synchronized(this) {
            loadingChapters.remove(index)
        }
    }

    fun loadOrUpPlayUrl() {
        if (durPlayUrl.isEmpty()) {
            loadPlayUrl()
        } else {
            upPlayUrl()
        }
    }

    /**
     * 加载播放URL
     */
    private fun loadPlayUrl() {
        val index = durChapterIndex
        if (addLoading(index)) {
            val book = book
            val bookSource = bookSource
            if (book != null && bookSource != null) {
                upDurChapter()
                val chapter = durChapter
                if (chapter == null) {
                    removeLoading(index)
                    return
                }
                if (chapter.isVolume) {
                    skipTo(index + 1)
                    removeLoading(index)
                    return
                }
                upLoading(true)
                val playback = NativeVideoPlayback(bookSource.bookSourceUrl)
                WebBook.getContent(this, bookSource, book, chapter, context = IO + playback)
                    .onSuccess { content ->
                        val playContent = playback.playUrl ?: content
                        if (playContent.isEmpty()) {
                            appCtx.toastOnUi("未获取到资源链接")
                        } else {
                            contentLoadFinish(chapter, playContent)
                        }
                    }.onError {
                        AppLog.put("获取资源链接出错\n$it", it, true)
                        upLoading(false)
                    }.onCancel {
                        removeLoading(index)
                    }.onFinally {
                        removeLoading(index)
                    }
            } else {
                removeLoading(index)
                appCtx.toastOnUi("book or source is null")
            }
        }
    }

    /**
     * 加载完成
     */
    private fun contentLoadFinish(chapter: BookChapter, content: String) {
        if (chapter.index == book?.durChapterIndex) {
            durPlayUrl = extractPlayUrl(content)
            upPlayUrl()
        }
    }

    /**
     * 从书源正文内容中提取媒体直链
     * 部分书源正文规则返回带修饰文本的内容, 需提取出合法的 URL
     */
    private fun extractPlayUrl(content: String): String {
        val trimmed = content.trim()
        if (playUrlPattern.matcher(trimmed).matches()) {
            return trimmed
        }
        val matcher = playUrlPattern.matcher(trimmed)
        return if (matcher.find()) matcher.group() else trimmed
    }

    private val playUrlPattern = Pattern.compile("https?://\\S+")


    private fun upPlayUrl() {
        if (isPlayToEnd()) {
            playNew()
        } else {
            play()
        }
    }

    /**
     * 播放当前章节
     */
    fun play() {
        context.startService<VideoPlayService> {
            action = IntentAction.play
        }
    }

    /**
     * 从头播放新章节
     */
    private fun playNew() {
        context.startService<VideoPlayService> {
            action = IntentAction.playNew
        }
    }

    /**
     * 更新当前章节
     */
    fun upDurChapter() {
        val book = book ?: return
        durChapter = appDb.bookChapterDao.getChapter(book.bookUrl, durChapterIndex)
        durVideoSize = durChapter?.end?.toInt() ?: 0
        val title = durChapter?.title ?: appCtx.getString(R.string.data_loading)
        postEvent(EventBus.AUDIO_SUB_TITLE, title)
        postEvent(EventBus.AUDIO_SIZE, durVideoSize)
        postEvent(EventBus.AUDIO_PROGRESS, durChapterPos)
    }

    fun pause(context: Context) {
        if (VideoPlayService.isRun) {
            context.startService<VideoPlayService> {
                action = IntentAction.pause
            }
        }
    }

    fun resume(context: Context) {
        if (VideoPlayService.isRun) {
            context.startService<VideoPlayService> {
                action = IntentAction.resume
            }
        }
    }

    fun stop() {
        if (VideoPlayService.isRun) {
            context.startService<VideoPlayService> {
                action = IntentAction.stop
            }
        }
    }

    fun adjustSpeed(adjust: Float) {
        if (VideoPlayService.isRun) {
            context.startService<VideoPlayService> {
                action = IntentAction.adjustSpeed
                putExtra("adjust", adjust)
            }
        }
    }

    fun adjustProgress(position: Int) {
        durChapterPos = position
        saveRead()
        if (VideoPlayService.isRun) {
            context.startService<VideoPlayService> {
                action = IntentAction.adjustProgress
                putExtra("position", position)
            }
        }
    }

    fun skipTo(index: Int) {
        Coroutine.async {
            stopPlay()
            if (index in 0..<simulatedChapterSize) {
                durChapterIndex = index
                durChapterPos = 0
                durPlayUrl = ""
                saveRead()
                loadPlayUrl()
            }
        }
    }

    fun prev() {
        Coroutine.async {
            stopPlay()
            if (durChapterIndex > 0) {
                durChapterIndex -= 1
                durChapterPos = 0
                durPlayUrl = ""
                saveRead()
                loadPlayUrl()
            }
        }
    }

    fun next() {
        stopPlay()
        when (playMode) {
            PlayMode.LIST_END_STOP -> {
                if (durChapterIndex + 1 < simulatedChapterSize) {
                    durChapterIndex += 1
                    durChapterPos = 0
                    durPlayUrl = ""
                    saveRead()
                    loadPlayUrl()
                }
            }

            PlayMode.SINGLE_LOOP -> {
                durChapterPos = 0
                durPlayUrl = ""
                saveRead()
                loadPlayUrl()
            }

            PlayMode.RANDOM -> {
                durChapterIndex = (0 until simulatedChapterSize).random()
                durChapterPos = 0
                durPlayUrl = ""
                saveRead()
                loadPlayUrl()
            }

            PlayMode.LIST_LOOP -> {
                durChapterIndex = (durChapterIndex + 1) % simulatedChapterSize
                durChapterPos = 0
                durPlayUrl = ""
                saveRead()
                loadPlayUrl()
            }
        }
    }

    fun setTimer(minute: Int) {
        if (VideoPlayService.isRun) {
            val intent = Intent(context, VideoPlayService::class.java)
            intent.action = IntentAction.setTimer
            intent.putExtra("minute", minute)
            context.startService(intent)
        } else {
            VideoPlayService.timeMinute = minute
            postEvent(EventBus.AUDIO_DS, minute)
        }
    }

    fun addTimer() {
        val intent = Intent(context, VideoPlayService::class.java)
        intent.action = IntentAction.addTimer
        context.startService(intent)
    }

    fun stopPlay() {
        if (VideoPlayService.isRun) {
            context.startService<VideoPlayService> {
                action = IntentAction.stopPlay
            }
        }
    }

    fun saveRead() {
        val book = book ?: return
        Coroutine.async {
            book.lastCheckCount = 0
            book.durChapterTime = System.currentTimeMillis()
            val chapterChanged = book.durChapterIndex != durChapterIndex
            book.durChapterIndex = durChapterIndex
            book.durChapterPos = durChapterPos
            if (chapterChanged) {
                appDb.bookChapterDao.getChapter(book.bookUrl, book.durChapterIndex)?.let {
                    book.durChapterTitle = it.getDisplayTitle(
                        ContentProcessor.get(book.name, book.origin).getTitleReplaceRules(),
                        book.getUseReplaceRule()
                    )
                }
            }
            book.update()
        }
    }

    /**
     * 保存章节长度
     */
    fun saveDurChapter(videoSize: Long) {
        val chapter = durChapter ?: return
        Coroutine.async {
            durVideoSize = videoSize.toInt()
            chapter.end = videoSize
            appDb.bookChapterDao.update(chapter)
        }
    }

    fun playPositionChanged(position: Int) {
        durChapterPos = position
        saveRead()
    }

    fun upLoading(loading: Boolean) {
        callback?.upLoading(loading)
    }

    private fun isPlayToEnd(): Boolean {
        return durChapterIndex + 1 == simulatedChapterSize
                && durChapterPos == durVideoSize
    }

    fun register(context: Context) {
        activityContext = context
        callback = context as CallBack
    }

    fun unregister(context: Context) {
        if (activityContext === context) {
            activityContext = null
            callback = null
        }
        coroutineContext.cancelChildren()
    }

    fun registerService(context: Context) {
        serviceContext = context
    }

    fun unregisterService() {
        serviceContext = null
    }

    interface CallBack {

        fun upLoading(loading: Boolean)

    }

}
