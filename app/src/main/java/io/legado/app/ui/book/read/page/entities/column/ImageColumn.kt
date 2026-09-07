package io.legado.app.ui.book.read.page.entities.column

import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
import androidx.annotation.Keep
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextLine.Companion.emptyTextLine
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

/**
 * 图片列
 */
@Keep
data class ImageColumn(
    override var start: Float,
    override var end: Float,
    var src: String
) : BaseColumn {

    override var textLine: TextLine = emptyTextLine
    private var gifStartTime = 0L

    override fun draw(view: ContentTextView, canvas: Canvas) {
        val book = ReadBook.book ?: return

        val height = textLine.height
        val width = (end - start).toInt()

        val gif = ImageProvider.getGif(book, src, width, height.toInt())
        if (gif != null) {
            if (gifStartTime == 0L) {
                gifStartTime = SystemClock.uptimeMillis()
            }
            val elapsed = ((SystemClock.uptimeMillis() - gifStartTime) % gif.totalDuration).toInt()
            val frameIndex = gif.frameIndexAt(elapsed)
            val bitmap = gif.frames[frameIndex]
            if (!bitmap.isRecycled) {
                val rectF = if (textLine.isImage) {
                    RectF(start, 0f, end, height)
                } else {
                    /*以宽度为基准保持图片的原始比例叠加，当div为负数时，允许高度比字符更高*/
                    val h = (end - start) / bitmap.width * bitmap.height
                    val div = (height - h) / 2
                    RectF(start, div, end, height - div)
                }
                kotlin.runCatching {
                    canvas.drawBitmap(bitmap, null, rectF, view.imagePaint)
                }.onFailure { e ->
                    appCtx.toastOnUi(e.localizedMessage)
                }
            }
            //gif自驱动动画, 帧离开屏幕后不再draw, 自动停止
            view.postInvalidateDelayed(gif.nextFrameDelayAt(elapsed).toLong())
            //optimizeRender下TextLine录制到bitmap, 需标记脏以逐帧重录
            textLine.canvasRecorder.invalidate()
        } else {
            val bitmap = ImageProvider.getImage(
                book,
                src,
                width,
                height.toInt()
            )

            val rectF = if (textLine.isImage) {
                RectF(start, 0f, end, height)
            } else {
                /*以宽度为基准保持图片的原始比例叠加，当div为负数时，允许高度比字符更高*/
                val h = (end - start) / bitmap.width * bitmap.height
                val div = (height - h) / 2
                RectF(start, div, end, height - div)
            }
            kotlin.runCatching {
                canvas.drawBitmap(bitmap, null, rectF, view.imagePaint)
            }.onFailure { e ->
                appCtx.toastOnUi(e.localizedMessage)
            }
        }
    }

}
