package io.legado.app.help.voice

import android.content.Intent
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.book.isNotShelf
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.startActivityForBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class VoiceActionResult(
    val message: String,
    val choices: List<Pair<String, VoiceCommand>> = emptyList()
)

/** Runs on main, with database queries dispatched off main. No simulated screen taps. */
object VoiceActions {
    suspend fun execute(command: VoiceCommand): VoiceActionResult = when (command) {
        VoiceCommand.Cancel -> VoiceActionResult("已取消")
        is VoiceCommand.OpenShelfBook -> {
            val book = withContext(Dispatchers.IO) { appDb.bookDao.getBook(command.bookUrl) }
            if (book == null || book.isNotShelf) VoiceActionResult("这本书已不在书架中，请重新选择") else open(book)
        }
        is VoiceCommand.TurnPage -> VoiceActionResult(
            (VoiceAssistant.activity as? ReadBookActivity)?.turnPageByVoice(command.next)
                ?: "请先打开一本书，再翻页"
        )
        is VoiceCommand.EyePage -> VoiceActionResult(
            (VoiceAssistant.activity as? ReadBookActivity)?.setEyePageByVoice(command.enabled)
                ?: "请先进入阅读页面，再设置眼动翻页"
        )
        is VoiceCommand.OpenPage -> {
            val main = mainActivity()
            VoiceActionResult(if (main?.navigateByVoice(command.discovery) == true) {
                if (command.discovery) "已打开发现" else "已返回书架"
            } else "页面暂不可用，请先完成首页设置，并确认发现入口已开启")
        }
        is VoiceCommand.OpenOrdinal -> {
            val main = VoiceAssistant.activity as? MainActivity
            val books = main?.voiceBookshelf()?.books
            val book = books?.getOrNull(command.number - 1)
            if (book != null && main.voiceAssistantReady) open(book)
            else VoiceActionResult(if (main == null) "请先返回书架，再按序号打开书籍"
                else "当前分组没有第${command.number}本书，或书架尚未加载完成")
        }
        is VoiceCommand.OpenBook -> {
            val books = withContext(Dispatchers.IO) { appDb.bookDao.all.filterNot { it.isNotShelf } }
            val direct = VoiceEntityMatcher.match(command.name, books) { it.name }
            val matches = direct.ifEmpty {
                withContext(Dispatchers.Default) { VoiceBookNames.suggestions(command.name, books) { it.name } }
            }
            when {
                matches.isEmpty() -> VoiceActionResult("书架中没有找到${command.name}，请说完整书名")
                matches.size == 1 && direct.isNotEmpty() -> open(matches.first())
                matches.size <= 5 -> VoiceActionResult(
                    if (direct.isEmpty()) "找到同音书名，请说第几个，或点击确认" else "找到多个版本，请说第几个，或点击选择",
                    matches.map { "${it.name} · ${it.author} · ${it.originName}" to VoiceCommand.OpenShelfBook(it.bookUrl) }
                )
                else -> VoiceActionResult("匹配的书籍较多，请说更完整的书名")
            }
        }
        is VoiceCommand.SwitchGroup -> {
            val groups = withContext(Dispatchers.IO) { appDb.bookGroupDao.all }
            val matches = VoiceEntityMatcher.match(command.name, groups) { it.groupName }
            if (matches.size != 1) VoiceActionResult(
                if (matches.isEmpty()) "没有找到${command.name}分组" else "有多个相近分组，请说完整分组名"
            ) else {
                val main = mainActivity()
                if (main?.navigateByVoice(false) != true) VoiceActionResult("书架暂不可用") else {
                    var selected = false
                    repeat(20) {
                        if (!selected) selected = main.voiceBookshelf()?.selectVoiceGroup(matches[0].groupId) == true
                        if (!selected) delay(100)
                    }
                    VoiceActionResult(if (selected) "已切换到${matches[0].groupName}分组"
                        else "该分组未显示，请先在书架分组管理中显示它")
                }
            }
        }
    }

    private suspend fun open(book: Book): VoiceActionResult {
        val activity = VoiceAssistant.activity ?: return VoiceActionResult("页面已离开，请重试")
        activity.startActivityForBook(book)
        repeat(30) {
            delay(100)
            if (VoiceAssistant.activity?.intent?.getStringExtra("bookUrl") == book.bookUrl) {
                return VoiceActionResult("已打开${book.name}")
            }
        }
        return VoiceActionResult("已请求打开${book.name}，请等待页面加载")
    }

    private suspend fun mainActivity(): MainActivity? {
        val current = VoiceAssistant.activity ?: return null
        if (current !is MainActivity) {
            current.startActivity(Intent(current, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        }
        repeat(30) {
            (VoiceAssistant.activity as? MainActivity)?.takeIf { it.voiceAssistantReady }?.let { return it }
            delay(100)
        }
        return null
    }
}
