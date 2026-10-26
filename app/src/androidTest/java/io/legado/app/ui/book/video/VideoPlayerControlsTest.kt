package io.legado.app.ui.book.video

import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.widget.SeekBar
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.Futures
import io.legado.app.R
import io.legado.app.databinding.ActivityVideoPlayBinding
import io.legado.app.databinding.ViewVideoControlsBinding
import io.legado.app.ui.book.audio.TimerSliderPopup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoPlayerControlsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun customPlayPauseButtonStillControlsMedia3Player() = instrumentation.runOnMainSync {
        val context = ContextThemeWrapper(instrumentation.targetContext, R.style.AppTheme_Light)
        val binding = ActivityVideoPlayBinding.inflate(LayoutInflater.from(context))
        val controls = ViewVideoControlsBinding.bind(binding.playerView.findViewById(R.id.video_controller))
        val player = TestPlayer()
        try {
            binding.playerView.player = player
            assertFalse(player.playWhenReady)
            controls.exoPlayPause.performClick()
            assertTrue(player.playWhenReady)
            controls.exoPlayPause.performClick()
            assertFalse(player.playWhenReady)
        } finally {
            binding.playerView.player = null
            player.release()
        }
    }

    @Test
    fun timerUserInputUsesTheSuppliedPlaybackCallback() = instrumentation.runOnMainSync {
        val context = ContextThemeWrapper(instrumentation.targetContext, R.style.AppTheme_Light)
        var videoTimer = -1
        val popup = TimerSliderPopup(context, { 0 }, { videoTimer = it })
        val slider = popup.contentView.findViewById<SeekBar>(R.id.seek_bar)
        slider.progress = 10
        assertEquals(-1, videoTimer)
        slider.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertTrue(videoTimer > 10)
        assertEquals(slider.progress, videoTimer)
        popup.dismiss()
    }

    private class TestPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        private var shouldPlay = false

        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().add(Player.COMMAND_PLAY_PAUSE).build())
            .setPlaylist(listOf(MediaItemData.Builder("video-controls-test").setDurationUs(60_000_000).build()))
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(shouldPlay, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build()

        override fun handleSetPlayWhenReady(playWhenReady: Boolean) = Futures.immediateVoidFuture().also {
            shouldPlay = playWhenReady
        }
    }
}
