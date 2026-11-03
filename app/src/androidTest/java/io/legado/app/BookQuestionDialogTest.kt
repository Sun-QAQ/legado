package io.legado.app

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.ui.book.agent.BookQuestionDialog
import io.legado.app.ui.book.read.ReadMenu
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.utils.dpToPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookQuestionDialogTest {
    @Test
    fun fiveReadingButtonsFitOnNarrowScreensWithAiOnTheRight() {
        ActivityScenario.launch(SearchActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val menu = ReadMenu(activity)
                val width = 320.dpToPx()
                menu.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(640.dpToPx(), View.MeasureSpec.EXACTLY))
                menu.layout(0, 0, width, menu.measuredHeight)
                val row = menu.findViewById<ViewGroup>(R.id.ll_floating_button)
                val buttons = listOf(R.id.fabSearch, R.id.fabAutoPage, R.id.fabReplaceRule, R.id.fabNightTheme, R.id.fabAi)
                    .map { row.findViewById<View>(it) }
                buttons.forEach {
                    assertTrue(it.measuredWidth > 0)
                    assertTrue(it.left >= 0 && it.right <= row.width)
                    assertEquals(View.VISIBLE, it.visibility)
                }
                buttons.zipWithNext().forEach { (left, right) -> assertTrue(left.right <= right.left) }
                assertEquals(activity.getString(R.string.book_question_title), buttons.last().contentDescription)
            }
        }
    }

    @Test
    fun questionDialogKeepsBookAndDraftAfterRecreationAndAvoidsKeyboard() {
        ActivityScenario.launch(SearchActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                BookQuestionDialog.newInstance("界面测试书籍", "测试作者", "local://ui-test", "第一章", "已读正文")
                    .showNow(activity.supportFragmentManager, "questionTest")
                activity.supportFragmentManager.executePendingTransactions()
                val root = activity.supportFragmentManager.findFragmentByTag("questionTest")!!.requireView()
                assertEquals("《界面测试书籍》", root.findViewById<TextView>(R.id.tv_book).text.toString())
                assertEquals(3, root.findViewById<ViewGroup>(R.id.suggestions).childCount)
                assertEquals(3, root.findViewById<ViewGroup>(R.id.quick_actions).childCount)
                root.findViewById<EditText>(R.id.et_input).setText("尚未发送的问题")
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.findFragmentByTag("questionTest") as BookQuestionDialog
                val root = fragment.requireView()
                assertEquals("《界面测试书籍》", root.findViewById<TextView>(R.id.tv_book).text.toString())
                assertEquals("尚未发送的问题", root.findViewById<EditText>(R.id.et_input).text.toString())
                ViewCompat.dispatchApplyWindowInsets(root, WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 300))
                    .setVisible(WindowInsetsCompat.Type.ime(), true).build())
                assertEquals(300, root.paddingBottom)
                assertEquals(View.GONE, root.findViewById<View>(R.id.welcome).visibility)
                root.findViewById<View>(R.id.btn_close).performClick()
                activity.supportFragmentManager.executePendingTransactions()
                assertTrue(activity.supportFragmentManager.findFragmentByTag("questionTest") == null)
            }
        }
    }
}
