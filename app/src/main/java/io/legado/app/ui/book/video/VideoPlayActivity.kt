package io.legado.app.ui.book.video

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.Status
import io.legado.app.constant.Theme
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.databinding.ActivityVideoPlayBinding
import io.legado.app.databinding.ViewVideoControlsBinding
import io.legado.app.help.book.isVideo
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.BookCover
import io.legado.app.model.VideoPlay
import io.legado.app.service.VideoPlayService
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.audio.TimerSliderPopup
import io.legado.app.ui.book.changesource.ChangeBookSourceDialog
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.dpToPx
import io.legado.app.utils.invisible
import io.legado.app.utils.observeEvent
import io.legado.app.utils.observeEventSticky
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.views.onLongClick
import java.util.Locale

/**
 * 视频播放
 */
class VideoPlayActivity :
    VMBaseActivity<ActivityVideoPlayBinding, VideoPlayViewModel>(toolBarTheme = Theme.Dark),
    ChangeBookSourceDialog.CallBack,
    VideoPlay.CallBack {

    override val binding by viewBinding(ActivityVideoPlayBinding::inflate)
    override val viewModel by viewModels<VideoPlayViewModel>()
    private val controls by lazy {
        ViewVideoControlsBinding.bind(binding.playerView.findViewById(R.id.video_controller))
    }
    private val timerSliderPopup by lazy {
        TimerSliderPopup(this, { VideoPlayService.timeMinute }, VideoPlay::setTimer)
    }
    private var playMode = VideoPlay.PlayMode.LIST_END_STOP
    private var pausedByLifecycle = false
    private val playerListener = object : Player.Listener {
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            updateSpeed(playbackParameters.speed)
        }
    }

    private var videoPlayService: VideoPlayService? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            videoPlayService = (service as VideoPlayService.LocalBinder).getService()
            // 视频画面与控制条交由 PlayerView, 播放器实例由 service 持有
            binding.playerView.player = videoPlayService?.exoPlayer
            binding.playerView.player?.let {
                it.addListener(playerListener)
                updateSpeed(it.playbackParameters.speed)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            binding.playerView.player?.removeListener(playerListener)
            binding.playerView.player = null
            videoPlayService = null
        }
    }

    private val sourceEditResult =
        registerForActivityResult(StartActivityContract(BookSourceEditActivity::class.java)) {
            if (it.resultCode == RESULT_OK) {
                viewModel.upSource()
            }
        }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.apply {
            setBackgroundResource(R.drawable.bg_video_top_scrim)
            setTitleTextColor(Color.WHITE)
            setSubTitleTextColor(
                ContextCompat.getColor(this@VideoPlayActivity, R.color.video_control_secondary)
            )
            setColorFilter(Color.WHITE)
        }
        VideoPlay.register(this)
        bindVideoService()
        viewModel.titleData.observe(this) {
            binding.titleBar.title = it
        }
        viewModel.coverData.observe(this) {
            upCover(it)
        }
        viewModel.initData(intent)
        initView()
    }

    private fun bindVideoService() {
        val serviceIntent = Intent(this, VideoPlayService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        @Suppress("DEPRECATION")
        bindService(Intent(this, VideoPlayService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.video_play, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        binding.titleBar.setColorFilter(Color.WHITE)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.findItem(R.id.menu_login)?.isVisible = !VideoPlay.bookSource?.loginUrl.isNullOrBlank()
        menu.findItem(R.id.menu_wake_lock)?.isChecked = AppConfig.audioPlayUseWakeLock
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_change_source -> VideoPlay.book?.let {
                showDialogFragment(ChangeBookSourceDialog(it.name, it.author))
            }

            R.id.menu_login -> VideoPlay.bookSource?.let {
                startActivity<SourceLoginActivity> {
                    putExtra("type", "bookSource")
                    putExtra("key", it.bookSourceUrl)
                }
            }

            R.id.menu_wake_lock -> AppConfig.audioPlayUseWakeLock = !AppConfig.audioPlayUseWakeLock
            R.id.menu_copy_video_url -> sendToClip(VideoPlayService.url)
            R.id.menu_edit_source -> VideoPlay.bookSource?.let {
                sourceEditResult.launch {
                    putExtra("sourceUrl", it.bookSourceUrl)
                }
            }

            R.id.menu_log -> showDialogFragment<AppLogDialog>()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(controls.root) { _, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            controls.videoControls.updatePadding(
                left = 16.dpToPx() + safe.left,
                right = 16.dpToPx() + safe.right,
                bottom = 8.dpToPx() + safe.bottom
            )
            binding.titleBar.toolbar.updatePadding(left = safe.left, right = safe.right)
            insets
        }
        ViewCompat.requestApplyInsets(controls.root)
        // 底部控制区由 PlayerView 统一显隐，标题栏同步跟随。
        binding.playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                val show = visibility == View.VISIBLE
                if (show) binding.titleBar.visible() else binding.titleBar.invisible()
            }
        )
        controls.ivPlayMode.setOnClickListener {
            VideoPlay.changePlayMode()
        }

        observeEventSticky<VideoPlay.PlayMode>(EventBus.PLAY_MODE_CHANGED) {
            playMode = it
            updatePlayModeIcon()
        }

        controls.ivSkipNext.setOnClickListener {
            VideoPlay.next()
        }
        // 长按调节倍速
        controls.ivSkipNext.onLongClick {
            VideoPlay.adjustSpeed(0.1f)
        }
        controls.ivSkipPrevious.setOnClickListener {
            VideoPlay.prev()
        }
        controls.ivSkipPrevious.onLongClick {
            VideoPlay.adjustSpeed(-0.1f)
        }
        controls.ivChapter.setOnClickListener {
            if (VideoPlay.book != null) {
                EpisodeListDialog().show(supportFragmentManager, "episodeList")
            }
        }
        controls.tvTimer.setOnClickListener {
            timerSliderPopup.showAtLocation(binding.root, Gravity.BOTTOM, 0, 0)
        }
    }

    private fun updatePlayModeIcon() {
        controls.ivPlayMode.setImageResource(playMode.iconRes)
    }

    private fun updateSpeed(speed: Float) {
        controls.exoPlaybackSpeed.text = String.format(Locale.ROOT, "%.1fX", speed)
    }

    private fun upCover(path: String?) {
        BookCover.loadBlur(this, path, sourceOrigin = VideoPlay.bookSource?.bookSourceUrl)
            .into(binding.ivBg)
    }

    private fun playButton() {
        when (VideoPlay.status) {
            Status.PLAY -> VideoPlay.pause(this)
            Status.PAUSE -> VideoPlay.resume(this)
            else -> VideoPlay.loadOrUpPlayUrl()
        }
    }

    override val oldBook: Book?
        get() = VideoPlay.book

    override fun changeTo(source: BookSource, book: Book, toc: List<BookChapter>) {
        if (book.isVideo) {
            viewModel.changeTo(source, book, toc)
        } else {
            VideoPlay.stop()
            lifecycleScope.launch {
                withContext(IO) {
                    VideoPlay.book?.migrateTo(book, toc)
                    book.removeType(BookType.updateError)
                    VideoPlay.book?.delete()
                    appDb.bookDao.insert(book)
                }
                startActivityForBook(book)
                finish()
            }
        }
    }

    override fun finish() {
        val book = VideoPlay.book ?: return super.finish()

        if (VideoPlay.inBookshelf) {
            return super.finish()
        }

        if (!AppConfig.showAddToShelfAlert) {
            viewModel.removeFromBookshelf { super.finish() }
        } else {
            alert(title = getString(R.string.add_to_bookshelf)) {
                setMessage(getString(R.string.check_add_bookshelf, book.name))
                okButton {
                    VideoPlay.book?.removeType(BookType.notShelf)
                    VideoPlay.book?.save()
                    VideoPlay.inBookshelf = true
                    setResult(RESULT_OK)
                }
                noButton { viewModel.removeFromBookshelf { super.finish() } }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    override fun observeLiveBus() {
        observeEvent<Boolean>(EventBus.MEDIA_BUTTON) {
            if (it) {
                playButton()
            }
        }
        observeEventSticky<Int>(EventBus.AUDIO_STATE) {
            VideoPlay.status = it
        }
        observeEventSticky<String>(EventBus.AUDIO_SUB_TITLE) {
            binding.titleBar.subtitle = it
            controls.ivSkipPrevious.isEnabled = VideoPlay.durChapterIndex > 0
            controls.ivSkipNext.isEnabled =
                VideoPlay.durChapterIndex < VideoPlay.simulatedChapterSize - 1
        }
        observeEventSticky<Float>(EventBus.AUDIO_SPEED) {
            updateSpeed(it)
        }
        observeEventSticky<Int>(EventBus.AUDIO_DS) {
            controls.tvTimer.text = if (it > 0) {
                getString(R.string.timer_m, it)
            } else {
                getString(R.string.set_timer)
            }
        }
    }

    override fun upLoading(loading: Boolean) {
        runOnUiThread {
            binding.progressLoading.visible(loading)
        }
    }

    override fun onPause() {
        super.onPause()
        binding.playerView.player?.let {
            if (it.playWhenReady) {
                pausedByLifecycle = true
                it.pause()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (pausedByLifecycle) {
            pausedByLifecycle = false
            binding.playerView.player?.play()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.playerView.player?.removeListener(playerListener)
        binding.playerView.player = null
        timerSliderPopup.dismiss()
        try {
            @Suppress("DEPRECATION")
            unbindService(connection)
        } catch (_: Exception) {
        }
        if (VideoPlay.status != Status.PLAY) {
            VideoPlay.stop()
        }
        VideoPlay.unregister(this)
    }

}
