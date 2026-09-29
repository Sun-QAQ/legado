package io.legado.app.service

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import androidx.media.AudioFocusRequestCompat
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.constant.Status
import io.legado.app.help.MediaHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.help.glide.ImageLoader
import io.legado.app.model.VideoPlay
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.AnalyzeUrl.Companion.getMediaItem
import io.legado.app.receiver.MediaButtonReceiver
import io.legado.app.ui.book.video.VideoPlayActivity
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.broadcastPendingIntent
import io.legado.app.utils.postEvent
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splitties.init.appCtx
import splitties.systemservices.audioManager
import splitties.systemservices.notificationManager
import splitties.systemservices.powerManager
import splitties.systemservices.wifiManager

/**
 * 视频播放服务
 * 持有 ExoPlayer 实例并通过 LocalBinder 暴露给 VideoPlayActivity 的 PlayerView,
 * 同时负责前台通知、媒体会话、定时关闭与耳机断开监听。
 */
class VideoPlayService : BaseService(),
    AudioManager.OnAudioFocusChangeListener,
    Player.Listener {

    companion object {
        @JvmStatic
        var isRun = false
            private set

        @JvmStatic
        var pause = true
            private set

        @JvmStatic
        var timeMinute: Int = 0

        var url: String = ""
            private set

        private const val MEDIA_SESSION_ACTIONS = (PlaybackStateCompat.ACTION_PLAY
                or PlaybackStateCompat.ACTION_PAUSE
                or PlaybackStateCompat.ACTION_PLAY_PAUSE
                or PlaybackStateCompat.ACTION_SEEK_TO)

        private const val APP_ACTION_STOP = "Stop"
        private const val APP_ACTION_TIMER = "Timer"
    }

    inner class LocalBinder : Binder() {
        fun getService(): VideoPlayService = this@VideoPlayService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    private val useWakeLock = AppConfig.audioPlayUseWakeLock
    private val wakeLock by lazy {
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "legado:VideoPlayService")
            .apply {
                this.setReferenceCounted(false)
            }
    }
    private val wifiLock by lazy {
        @Suppress("DEPRECATION")
        wifiManager?.createWifiLock(WIFI_MODE_FULL_HIGH_PERF, "legado:VideoPlayService")?.apply {
            setReferenceCounted(false)
        }
    }
    private val mFocusRequest: AudioFocusRequestCompat by lazy {
        MediaHelp.buildAudioFocusRequestCompat(this)
    }
    val exoPlayer: ExoPlayer by lazy {
        ExoPlayerHelper.createHttpExoPlayer(this)
    }
    private var mediaSessionCompat: MediaSessionCompat? = null
    private var broadcastReceiver: BroadcastReceiver? = null
    private var needResumeOnAudioFocusGain = false
    private var position = VideoPlay.book?.durChapterPos ?: 0
    private var dsJob: Job? = null
    private var upNotificationJob: Coroutine<*>? = null
    private var upPlayProgressJob: Job? = null
    private var playSpeed: Float = 1f
    private var cover: Bitmap =
        BitmapFactory.decodeResource(appCtx.resources, R.drawable.icon_read_book)
    private var coverTarget: CustomTarget<Bitmap>? = null

    override fun onCreate() {
        super.onCreate()
        isRun = true
        exoPlayer.addListener(this)
        VideoPlay.registerService(this)
        initMediaSession()
        initBroadcastReceiver()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
        doDs()
        upCover()
    }

    /**
     * 异步加载封面, 用于媒体会话和前台通知
     * 不能阻塞服务初始化: 封面地址多为远端地址,
     * 网络不通时会一直等待到超时, 既拖慢播放也抛出大量异常堆栈
     */
    private fun upCover() {
        val coverPath = VideoPlay.book?.getDisplayCover()
        if (coverPath.isNullOrEmpty()) return
        coverTarget?.let { Glide.with(this).clear(it) }
        val target = object : CustomTarget<Bitmap>() {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                if (resource.width > 16 && resource.height > 16) {
                    cover = resource
                    upMediaMetadata()
                    upVideoPlayNotification()
                }
            }

            override fun onLoadCleared(placeholder: Drawable?) = Unit

            override fun onLoadFailed(errorDrawable: Drawable?) = Unit
        }
        coverTarget = target
        ImageLoader.loadBitmap(this, coverPath).into(target)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                IntentAction.play -> {
                    exoPlayer.stop()
                    upPlayProgressJob?.cancel()
                    pause = false
                    position = VideoPlay.book?.durChapterPos ?: 0
                    url = VideoPlay.durPlayUrl
                    play()
                }

                IntentAction.playNew -> {
                    exoPlayer.stop()
                    upPlayProgressJob?.cancel()
                    pause = false
                    position = 0
                    url = VideoPlay.durPlayUrl
                    play()
                }

                IntentAction.stopPlay -> {
                    exoPlayer.stop()
                    upPlayProgressJob?.cancel()
                    VideoPlay.status = Status.STOP
                    postEvent(EventBus.AUDIO_STATE, Status.STOP)
                }

                IntentAction.pause -> pause()
                IntentAction.resume -> resume()
                IntentAction.prev -> VideoPlay.prev()
                IntentAction.next -> VideoPlay.next()
                IntentAction.adjustSpeed -> upSpeed(intent.getFloatExtra("adjust", 1f))
                IntentAction.addTimer -> addTimer()
                IntentAction.setTimer -> setTimer(intent.getIntExtra("minute", 0))
                IntentAction.adjustProgress -> {
                    adjustProgress(intent.getIntExtra("position", position))
                }

                IntentAction.stop -> stopSelf()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (useWakeLock) {
            wakeLock.release()
            wifiLock?.release()
        }
        isRun = false
        coverTarget?.let { Glide.with(this).clear(it) }
        coverTarget = null
        abandonFocus()
        exoPlayer.release()
        mediaSessionCompat?.release()
        unregisterReceiver(broadcastReceiver)
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_STOPPED)
        VideoPlay.status = Status.STOP
        postEvent(EventBus.AUDIO_STATE, Status.STOP)
        VideoPlay.unregisterService()
        upNotificationJob?.invokeOnCompletion {
            notificationManager.cancel(NotificationId.VideoPlayService)
        }
    }

    /**
     * 播放视频
     */
    @SuppressLint("WakelockTimeout")
    private fun play() {
        if (useWakeLock) {
            wakeLock.acquire()
            wifiLock?.acquire()
        }
        upVideoPlayNotification()
        if (!requestFocus()) {
            return
        }
        execute(context = Main) {
            VideoPlay.status = Status.STOP
            postEvent(EventBus.AUDIO_STATE, Status.STOP)
            upPlayProgressJob?.cancel()
            // 传入章节/书源地址作为 baseUrl, 使书源返回的相对地址能被补全为绝对地址
            val baseUrl = VideoPlay.durChapter?.url
                ?: VideoPlay.bookSource?.bookSourceUrl
                ?: ""
            val analyzeUrl = AnalyzeUrl(
                url,
                baseUrl = baseUrl,
                source = VideoPlay.bookSource,
                ruleData = VideoPlay.book,
                chapter = VideoPlay.durChapter,
                coroutineContext = coroutineContext
            )
            exoPlayer.setMediaItem(analyzeUrl.getMediaItem())
            exoPlayer.playWhenReady = true
            exoPlayer.seekTo(position.toLong())
            exoPlayer.prepare()
        }.onError {
            AppLog.put("播放出错\n${it.localizedMessage}", it)
            toastOnUi("$url ${it.localizedMessage}")
            stopSelf()
        }
    }

    /**
     * 暂停播放
     */
    private fun pause(abandonFocus: Boolean = true) {
        if (useWakeLock) {
            wakeLock.release()
            wifiLock?.release()
        }
        try {
            pause = true
            if (abandonFocus) {
                abandonFocus()
            }
            upPlayProgressJob?.cancel()
            position = exoPlayer.currentPosition.toInt()
            if (exoPlayer.isPlaying) exoPlayer.pause()
            upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PAUSED)
            VideoPlay.status = Status.PAUSE
            postEvent(EventBus.AUDIO_STATE, Status.PAUSE)
            upVideoPlayNotification()
        } catch (e: Exception) {
            e.printOnDebug()
        }
    }

    /**
     * 恢复播放
     */
    @SuppressLint("WakelockTimeout")
    private fun resume() {
        if (useWakeLock) {
            wakeLock.acquire()
            wifiLock?.acquire()
        }
        try {
            pause = false
            if (url.isEmpty()) {
                VideoPlay.loadOrUpPlayUrl()
                return
            }
            if (!exoPlayer.isPlaying) {
                exoPlayer.play()
            }
            upPlayProgress()
            upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
            VideoPlay.status = Status.PLAY
            postEvent(EventBus.AUDIO_STATE, Status.PLAY)
            upVideoPlayNotification()
        } catch (e: Exception) {
            e.printOnDebug()
            stopSelf()
        }
    }

    /**
     * 调节进度
     */
    private fun adjustProgress(position: Int) {
        this.position = position
        exoPlayer.seekTo(position.toLong())
    }

    /**
     * 调节速度
     */
    @SuppressLint(value = ["ObsoleteSdkInt"])
    private fun upSpeed(adjust: Float) {
        kotlin.runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                playSpeed += adjust
                exoPlayer.setPlaybackSpeed(playSpeed)
                postEvent(EventBus.AUDIO_SPEED, playSpeed)
            }
        }
    }

    /**
     * 播放状态监控
     */
    override fun onPlaybackStateChanged(playbackState: Int) {
        super.onPlaybackStateChanged(playbackState)
        when (playbackState) {
            Player.STATE_IDLE -> {
                // 空闲
            }

            Player.STATE_BUFFERING -> {
                // 缓冲中
            }

            Player.STATE_READY -> {
                // 准备好
                VideoPlay.upLoading(false)
                if (exoPlayer.playWhenReady) {
                    VideoPlay.status = Status.PLAY
                    postEvent(EventBus.AUDIO_STATE, Status.PLAY)
                } else {
                    VideoPlay.status = Status.PAUSE
                    postEvent(EventBus.AUDIO_STATE, Status.PAUSE)
                }
                postEvent(EventBus.AUDIO_SIZE, exoPlayer.duration.toInt())
                upMediaMetadata()
                upPlayProgress()
                VideoPlay.saveDurChapter(exoPlayer.duration)
            }

            Player.STATE_ENDED -> {
                // 结束
                upPlayProgressJob?.cancel()
                VideoPlay.playPositionChanged(exoPlayer.duration.toInt())
                VideoPlay.next()
            }
        }
        upVideoPlayNotification()
    }

    private fun upMediaMetadata() {
        val metadata = MediaMetadataCompat.Builder()
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, cover)
            .putText(MediaMetadataCompat.METADATA_KEY_TITLE, VideoPlay.durChapter?.title ?: "null")
            .putText(MediaMetadataCompat.METADATA_KEY_ARTIST, VideoPlay.book?.name ?: "null")
            .putText(MediaMetadataCompat.METADATA_KEY_ALBUM, VideoPlay.book?.author ?: "null")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, exoPlayer.duration)
            .build()
        mediaSessionCompat?.setMetadata(metadata)
    }

    /**
     * 播放错误事件
     */
    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)
        VideoPlay.status = Status.STOP
        postEvent(EventBus.AUDIO_STATE, Status.STOP)
        VideoPlay.upLoading(false)
        val errorMsg = "视频播放出错\nurl=$url\n${error.errorCodeName} ${error.errorCode}"
        AppLog.put(errorMsg, error)
        toastOnUi(errorMsg)
    }

    private fun setTimer(minute: Int) {
        timeMinute = minute
        doDs()
    }

    private fun addTimer() {
        if (timeMinute == 180) {
            timeMinute = 0
        } else {
            timeMinute += 10
            if (timeMinute > 180) timeMinute = 180
        }
        doDs()
    }

    /**
     * 定时
     */
    private fun doDs() {
        postEvent(EventBus.AUDIO_DS, timeMinute)
        upVideoPlayNotification()
        dsJob?.cancel()
        dsJob = lifecycleScope.launch {
            while (isActive) {
                delay(60000)
                if (!pause) {
                    if (timeMinute >= 0) {
                        timeMinute--
                    }
                    if (timeMinute == 0) {
                        VideoPlay.stop()
                        postEvent(EventBus.AUDIO_DS, timeMinute)
                        break
                    }
                }
                postEvent(EventBus.AUDIO_DS, timeMinute)
                upVideoPlayNotification()
            }
        }
    }

    /**
     * 每隔1秒发送播放进度
     */
    private fun upPlayProgress() {
        upPlayProgressJob?.cancel()
        upPlayProgressJob = lifecycleScope.launch {
            while (isActive) {
                VideoPlay.playPositionChanged(exoPlayer.currentPosition.toInt())
                postEvent(EventBus.AUDIO_BUFFER_PROGRESS, exoPlayer.bufferedPosition.toInt())
                postEvent(EventBus.AUDIO_PROGRESS, VideoPlay.durChapterPos)
                postEvent(EventBus.AUDIO_SIZE, exoPlayer.duration.toInt())
                upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
                delay(1000)
            }
        }
    }

    /**
     * 更新媒体状态
     */
    private fun upMediaSessionPlaybackState(state: Int) {
        mediaSessionCompat?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(MEDIA_SESSION_ACTIONS)
                .setState(state, exoPlayer.currentPosition, 1f)
                .setBufferedPosition(exoPlayer.bufferedPosition)
                .addCustomAction(
                    APP_ACTION_STOP,
                    getString(R.string.stop),
                    R.drawable.ic_stop_black_24dp
                )
                .addCustomAction(
                    APP_ACTION_TIMER,
                    getString(R.string.set_timer),
                    R.drawable.ic_time_add_24dp
                )
                .build()
        )
    }

    /**
     * 初始化MediaSession, 注册多媒体按钮
     */
    @SuppressLint("UnspecifiedImmutableFlag")
    private fun initMediaSession() {
        mediaSessionCompat = MediaSessionCompat(this, "videoPlay")
        mediaSessionCompat?.setCallback(object : MediaSessionCompat.Callback() {
            override fun onSeekTo(pos: Long) {
                position = pos.toInt()
                exoPlayer.seekTo(pos)
            }

            override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                return MediaButtonReceiver.handleIntent(this@VideoPlayService, mediaButtonEvent)
            }

            override fun onPlay() = resume()

            override fun onPause() = pause()

            override fun onCustomAction(action: String?, extras: Bundle?) {
                action ?: return
                when (action) {
                    APP_ACTION_STOP -> stopSelf()
                    APP_ACTION_TIMER -> addTimer()
                }
            }
        })
        mediaSessionCompat?.setMediaButtonReceiver(
            broadcastPendingIntent<MediaButtonReceiver>(Intent.ACTION_MEDIA_BUTTON)
        )
        mediaSessionCompat?.isActive = true
    }

    /**
     * 断开耳机监听
     */
    private fun initBroadcastReceiver() {
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent.action) {
                    pause()
                }
            }
        }
        val intentFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(broadcastReceiver, intentFilter)
    }

    /**
     * 音频焦点变化
     */
    override fun onAudioFocusChange(focusChange: Int) {
        if (AppConfig.ignoreAudioFocus) {
            AppLog.put("忽略音频焦点处理(视频)")
            return
        }
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (needResumeOnAudioFocusGain) {
                    AppLog.put("音频焦点获得,继续播放")
                    resume()
                } else {
                    AppLog.put("音频焦点获得")
                }
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                AppLog.put("音频焦点丢失,暂停播放")
                pause()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                AppLog.put("音频焦点暂时丢失并会很快再次获得,暂停播放")
                if (!pause) {
                    needResumeOnAudioFocusGain = true
                    pause(false)
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                AppLog.put("音频焦点短暂丢失,不做处理")
            }
        }
    }

    private fun createNotification(): NotificationCompat.Builder {
        var nTitle: String = when {
            pause -> getString(R.string.audio_pause)
            timeMinute in 1..60 -> getString(
                R.string.playing_timer,
                timeMinute
            )

            else -> getString(R.string.video_play_t)
        }
        nTitle += ": ${VideoPlay.book?.name}"
        var nSubtitle = VideoPlay.durChapter?.title
        if (nSubtitle.isNullOrEmpty()) {
            nSubtitle = getString(R.string.video_play_s)
        }
        val builder = NotificationCompat
            .Builder(this@VideoPlayService, AppConst.channelIdReadAloud)
            .setSmallIcon(R.drawable.ic_volume_up)
            .setSubText(getString(R.string.video))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(nTitle)
            .setContentText(nSubtitle)
            .setContentIntent(
                activityPendingIntent<VideoPlayActivity>("activity")
            )
        builder.setLargeIcon(cover)
        if (pause) {
            builder.addAction(
                R.drawable.ic_play_24dp,
                getString(R.string.resume),
                servicePendingIntent<VideoPlayService>(IntentAction.resume)
            )
        } else {
            builder.addAction(
                R.drawable.ic_pause_24dp,
                getString(R.string.pause),
                servicePendingIntent<VideoPlayService>(IntentAction.pause)
            )
        }
        builder.addAction(
            R.drawable.ic_stop_black_24dp,
            getString(R.string.stop),
            servicePendingIntent<VideoPlayService>(IntentAction.stop)
        )
        builder.addAction(
            R.drawable.ic_time_add_24dp,
            getString(R.string.set_timer),
            servicePendingIntent<VideoPlayService>(IntentAction.addTimer)
        )
        builder.setStyle(
            androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0, 1, 2)
                .setMediaSession(mediaSessionCompat?.sessionToken)
        )
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        return builder
    }

    private fun upVideoPlayNotification() {
        upNotificationJob = execute {
            try {
                val notification = createNotification()
                notificationManager.notify(NotificationId.VideoPlayService, notification.build())
            } catch (e: Exception) {
                AppLog.put("创建视频播放通知出错,${e.localizedMessage}", e, true)
            }
        }
    }

    /**
     * 更新通知
     */
    override fun startForegroundNotification() {
        execute {
            try {
                val notification = createNotification()
                startForeground(NotificationId.VideoPlayService, notification.build())
            } catch (e: Exception) {
                AppLog.put("创建视频播放通知出错,${e.localizedMessage}", e, true)
                //创建通知出错不结束服务就会崩溃,服务必须绑定通知
                stopSelf()
            }
        }
    }

    /**
     * 请求音频焦点
     * @return 音频焦点
     */
    private fun requestFocus(): Boolean {
        if (AppConfig.ignoreAudioFocus) {
            return true
        }
        return MediaHelp.requestFocus(mFocusRequest)
    }

    /**
     * 放弃音频焦点
     */
    private fun abandonFocus() {
        @Suppress("DEPRECATION")
        audioManager.abandonAudioFocus(this)
    }

}
