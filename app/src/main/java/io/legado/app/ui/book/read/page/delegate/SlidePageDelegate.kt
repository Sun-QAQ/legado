package io.legado.app.ui.book.read.page.delegate

import android.graphics.Canvas
import androidx.core.graphics.withTranslation
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.entities.PageDirection

class SlidePageDelegate(readView: ReadView) : HorizontalPageDelegate(readView) {

    override fun onAnimStart(animationSpeed: Int) {
        val distanceX: Float
        when (animationDirection) {
            PageDirection.NEXT -> distanceX =
                if (isCancel) {
                    var dis = viewWidth - startX + touchX
                    if (dis > viewWidth) {
                        dis = viewWidth.toFloat()
                    }
                    viewWidth - dis
                } else {
                    -(touchX + (viewWidth - startX))
                }

            else -> distanceX =
                if (isCancel) {
                    -(touchX - startX)
                } else {
                    viewWidth - (touchX - startX)
                }
        }
        startScroll(touchX.toInt(), 0, distanceX.toInt(), 0, animationSpeed)
    }

    override fun onDraw(canvas: Canvas) {
        val offsetX = touchX - startX

        if ((animationDirection == PageDirection.NEXT && offsetX > 0)
            || (animationDirection == PageDirection.PREV && offsetX < 0)
        ) return
        val distanceX = if (offsetX > 0) offsetX - viewWidth else offsetX + viewWidth
        if (!isRunning) return
        if (animationDirection == PageDirection.PREV) {
            canvas.withTranslation(distanceX + viewWidth) {
                curRecorder.draw(this)
            }
            canvas.withTranslation(distanceX) {
                targetRecorder.draw(this)
            }
        } else if (animationDirection == PageDirection.NEXT) {
            canvas.withTranslation(distanceX) {
                targetRecorder.draw(this)
            }
            canvas.withTranslation(distanceX - viewWidth) {
                curRecorder.draw(this)
            }
        }
    }

    override fun onAnimStop() {
        if (!isCancel) {
            readView.fillPage(mDirection)
        }
    }
}
