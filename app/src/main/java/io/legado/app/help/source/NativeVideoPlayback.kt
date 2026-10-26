package io.legado.app.help.source

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 原生播放器解析正文时，接管旧书源通过 startBrowser 打开的媒体直链。
 * 仅随本次加载的协程传递，不影响登录、验证和其他书籍的浏览器调用。
 */
class NativeVideoPlayback(private val sourceKey: String) :
    AbstractCoroutineContextElement(Key) {

    companion object Key : CoroutineContext.Key<NativeVideoPlayback> {
        private val mediaUrl = Regex(
            "^https?://[^/?#\\s]+/[^?#\\s]*\\.(?:mp4|m3u8|m4v|webm|mov|mkv|flv|ts)(?:[?#,].*)?$",
            RegexOption.IGNORE_CASE
        )
    }

    var playUrl: String? = null
        private set

    fun interceptBrowser(sourceKey: String?, url: String): Boolean {
        if (this.sourceKey != sourceKey || !mediaUrl.matches(url.trim())) {
            return false
        }
        playUrl = url.trim()
        return true
    }
}
