package io.legado.app.help.voice

import android.content.Intent
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.ui.main.bookshelf.style2.FolderCoverPreview
import kotlinx.coroutines.flow.first
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.startActivityForBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

enum class VoiceActionStatus { SUCCESS, FAILED, NEEDS_USER_INPUT }

data class VoiceActionResult(
    val message: String,
    val choices: List<Pair<String, VoiceCommand>> = emptyList(),
    val status: VoiceActionStatus = if (choices.isEmpty()) VoiceActionStatus.SUCCESS else VoiceActionStatus.NEEDS_USER_INPUT
)

/** Runs on main, with database queries dispatched off main. No simulated screen taps. */
object VoiceActions {
    suspend fun execute(command: VoiceCommand): VoiceActionResult = when (command) {
        VoiceCommand.Cancel -> VoiceActionResult("已取消")
        is VoiceCommand.OpenShelfBook -> {
            val book = withContext(Dispatchers.IO) { appDb.bookDao.getBook(command.bookUrl) }
            if (book == null || book.isNotShelf) failed("这本书已不在书架中，请重新选择") else open(book)
        }
        is VoiceCommand.SwitchShelfGroup -> {
            val group = withContext(Dispatchers.IO) { appDb.bookGroupDao.getByID(command.groupId) }
            if (group == null) failed("分组已不存在，请重新选择") else switchGroup(group.groupId, group.groupName)
        }
        is VoiceCommand.TurnPage -> {
            val message = (VoiceAssistant.activity as? ReadBookActivity)?.turnPageByVoice(command.next)
                ?: "请先打开一本书，再翻页"
            VoiceActionResult(message, status = if (message.startsWith("已翻到"))
                VoiceActionStatus.SUCCESS else VoiceActionStatus.FAILED)
        }
        is VoiceCommand.EyePage -> {
            val reader = VoiceAssistant.activity as? ReadBookActivity
            if (reader == null) failed("请先进入阅读页面，再设置眼动翻页") else {
                val message = reader.setEyePageByVoice(command.enabled)
                VoiceActionResult(message, status = when {
                    message.startsWith("已") || message.contains("已经") -> VoiceActionStatus.SUCCESS
                    message.startsWith("请按") -> VoiceActionStatus.NEEDS_USER_INPUT
                    else -> VoiceActionStatus.FAILED
                })
            }
        }
        is VoiceCommand.OpenPage -> {
            val main = mainActivity()
            if (main?.navigateByVoice(command.discovery) == true) VoiceActionResult(
                if (command.discovery) "已打开发现" else "已返回书架"
            ) else failed("页面暂不可用，请先完成首页设置，并确认发现入口已开启")
        }
        is VoiceCommand.OpenOrdinal -> {
            val main = mainActivity()
            if (main?.navigateByVoice(false) != true) failed("书架暂不可用") else {
                val shelf = awaitShelf(main)
                if (shelf == null) failed("书架尚未加载完成，请重试") else {
                    val groupId = shelf.groupId
                    val books = withContext(Dispatchers.IO) {
                        FolderCoverPreview.sort(appDb.bookDao.flowByGroup(groupId).first(),
                            AppConfig.getBookSortByGroupId(groupId))
                    }
                    val book = books.getOrNull(command.number - 1)
                    if (book != null) open(book) else failed("当前分组没有第${command.number}本书")
                }
            }
        }
        is VoiceCommand.OpenBook -> {
            val books = withContext(Dispatchers.IO) { appDb.bookDao.all.filterNot { it.isNotShelf } }
            val direct = VoiceEntityMatcher.match(command.name, books) { it.name }
            val matches = direct.ifEmpty {
                withContext(Dispatchers.Default) { VoiceBookNames.suggestions(command.name, books) { it.name } }
            }
            when {
                matches.isEmpty() -> failed("书架中没有找到${command.name}，请说完整书名")
                matches.size == 1 && direct.isNotEmpty() -> open(matches.first())
                matches.size <= 5 -> VoiceActionResult(
                    if (direct.isEmpty()) "找到同音书名，请说第几个，或点击确认" else "找到多个版本，请说第几个，或点击选择",
                    matches.map { "${it.name} · ${it.author} · ${it.originName}" to VoiceCommand.OpenShelfBook(it.bookUrl) }
                )
                else -> failed("匹配的书籍较多，请说更完整的书名")
            }
        }
        is VoiceCommand.SwitchGroup -> {
            val groups = withContext(Dispatchers.IO) { appDb.bookGroupDao.all }
            val matches = VoiceEntityMatcher.match(command.name, groups) { it.groupName }
            if (matches.size != 1) failed(
                if (matches.isEmpty()) "没有找到${command.name}分组" else "有多个相近分组，请说完整分组名"
            ) else switchGroup(matches[0].groupId, matches[0].groupName)
        }
    }

    private fun failed(message: String) = VoiceActionResult(message, status = VoiceActionStatus.FAILED)

    private suspend fun awaitShelf(main: MainActivity): BaseBookshelfFragment? {
        repeat(30) {
            main.voiceBookshelf()?.let { return it }
            delay(100)
        }
        return null
    }

    private suspend fun switchGroup(id: Long, name: String): VoiceActionResult {
        val main = mainActivity()
        if (main?.navigateByVoice(false) != true) return failed("书架暂不可用")
        repeat(30) {
            if (main.voiceBookshelf()?.selectVoiceGroup(id) == true) return VoiceActionResult("已切换到${name}分组")
            delay(100)
        }
        return failed("该分组未显示，请先在书架分组管理中显示它")
    }

    private suspend fun open(book: Book): VoiceActionResult {
        val activity = VoiceAssistant.activity ?: return failed("页面已离开，请重试")
        activity.startActivityForBook(book)
        repeat(30) {
            delay(100)
            if (VoiceAssistant.activity?.intent?.getStringExtra("bookUrl") == book.bookUrl &&
                (VoiceAssistant.activity !is ReadBookActivity || io.legado.app.model.ReadBook.book?.bookUrl == book.bookUrl)) {
                return VoiceActionResult("已打开${book.name}")
            }
        }
        return VoiceActionResult("已请求打开${book.name}，请等待页面加载", status = VoiceActionStatus.NEEDS_USER_INPUT)
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
