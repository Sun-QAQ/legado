package io.legado.app.ui.book.agent

import android.graphics.drawable.Animatable
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentGifPlaybackTest {

    @Test
    fun `动图加载完成后开始播放且静态资源不受影响`() {
        val animation = FakeAnimatable()

        assertTrue(startAgentImageAnimation(animation))
        assertTrue(animation.started)
        assertFalse(startAgentImageAnimation(Any()))
    }

    private class FakeAnimatable : Animatable {
        var started = false

        override fun start() {
            started = true
        }

        override fun stop() = Unit

        override fun isRunning(): Boolean = started
    }
}
