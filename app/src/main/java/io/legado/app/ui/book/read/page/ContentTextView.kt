package io.legado.app.ui.book.read.page

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.method.ScrollingMovementMethod
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.PopupWindow
import android.widget.TextView
import io.legado.app.R
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.delegate.PageDelegate
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.TextPos
import io.legado.app.ui.book.read.page.entities.column.BaseColumn
import io.legado.app.ui.book.read.page.entities.column.ButtonColumn
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.ReviewColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.book.read.page.provider.TextPageFactory
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.utils.activity
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 阅读内容视图
 */
class ContentTextView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var selectAble = AppConfig.textSelectAble
    val selectedPaint by lazy {
        Paint().apply {
            color = context.getCompatColor(R.color.btn_bg_press_2)
            style = Paint.Style.FILL
        }
    }
    private var callBack: CallBack
    private val visibleRect = ChapterProvider.visibleRect
    val selectStart = TextPos(0, -1, -1)
    private val selectEnd = TextPos(0, -1, -1)
    var textPage: TextPage = TextPage()
        private set
    var isMainView = false
    var longScreenshot = false
    var reverseStartCursor = false
    var reverseEndCursor = false

    //滚动参数
    private val pageFactory get() = callBack.pageFactory
    private val pageDelegate get() = callBack.pageDelegate
    private var pageOffset = 0
    private var autoPager: AutoPager? = null
    private var isScroll = false
    private val renderRunnable by lazy { Runnable { preRenderPage() } }

    //图片缩放
    private var imageZoomScale = 1f
    private var imageZoomTranslateX = 0f
    private var imageZoomTranslateY = 0f
    private var imageZooming = false
    private var imageZoomInitialScale = 1f
    private var imageZoomInitialDistance = 1f
    private var imageZoomLastPanX = 0f
    private var imageZoomLastPanY = 0f
    private var imageZoomPanMoved = false
    private var imageZoomGestureStartX = 0f
    private var imageZoomGestureStartY = 0f
    private var imageZoomLastTapX = 0f
    private var imageZoomLastTapY = 0f
    private var imageZoomLastTapTime = 0L
    private val imageZoomMaxScale = 5f
    private val imageZoomTouchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val imageZoomDoubleTapTimeout = 300L
    private val imageZoomDoubleTapSlop = ViewConfiguration.get(context).scaledDoubleTapSlop

    //绘制图片的paint
    val imagePaint by lazy {
        Paint().apply {
            isAntiAlias = AppConfig.useAntiAlias
        }
    }

    init {
        callBack = activity as CallBack
    }

    /**
     * 设置内容
     */
    fun setContent(textPage: TextPage) {
        this.textPage = textPage
        if (imageZoomScale > 1f || imageZooming) {
            resetImageZoom()
        }
        // 非滑动翻页动画需要同步重绘，不然翻页可能会出现闪烁
        if (isScroll) {
            postInvalidate()
        } else {
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (imageZoomScale > 1f || imageZooming) {
            resetImageZoom()
        }
        if (!isMainView) return
        ChapterProvider.upViewSize(w, h)
        textPage.format()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        autoPager?.onDraw(canvas)
        if (longScreenshot) {
            canvas.translate(0f, scrollY.toFloat())
        }
        check(!visibleRect.isEmpty) { "visibleRect 为空" }
        canvas.clipRect(visibleRect)
        if (imageZoomScale > 1f) {
            canvas.save()
            canvas.translate(imageZoomTranslateX, imageZoomTranslateY)
            canvas.scale(imageZoomScale, imageZoomScale)
            drawPage(canvas)
            canvas.restore()
        } else {
            drawPage(canvas)
        }
    }

    /**
     * 绘制页面
     */
    private fun drawPage(canvas: Canvas) {
        var relativeOffset = relativeOffset(0)
        textPage.draw(this, canvas, relativeOffset)
        if (!callBack.isScroll) return
        //滚动翻页
        if (!pageFactory.hasNext()) return
        val textPage1 = relativePage(1)
        relativeOffset += textPage.height
        textPage1.draw(this, canvas, relativeOffset)
        if (!pageFactory.hasNextPlus()) return
        relativeOffset += textPage1.height
        if (relativeOffset < ChapterProvider.visibleHeight) {
            val textPage2 = relativePage(2)
            textPage2.draw(this, canvas, relativeOffset)
        }
    }

    override fun computeScroll() {
        pageDelegate?.computeScroll()
        autoPager?.computeOffset()
    }

    /**
     * 滚动事件
     * pageOffset 向上滚动 减小 向下滚动 增大
     * pageOffset 范围 0 ~ -textPage.height 大于0为上一页，小于-textPage.height为下一页
     * 以内容显示区域顶端为界，pageOffset的绝对值为textPage上方的高度
     * pageOffset + textPage.height 为 textPage 下方的高度
     */
    fun scroll(mOffset: Int) {
        pageOffset += mOffset
        if (longScreenshot) {
            scrollY += -mOffset
        }
        if (!pageFactory.hasPrev() && pageOffset > 0) {
            pageOffset = 0
            pageDelegate?.abortAnim()
        } else if (!pageFactory.hasNext()
            && pageOffset < 0
            && pageOffset + textPage.height < ChapterProvider.visibleHeight
        ) {
            val offset = (ChapterProvider.visibleHeight - textPage.height).toInt()
            pageOffset = min(0, offset)
            pageDelegate?.abortAnim()
        } else if (pageOffset > 0) {
            if (pageFactory.moveToPrev(true)) {
                pageOffset -= textPage.height.toInt()
            } else {
                pageOffset = 0
                pageDelegate?.abortAnim()
            }
        } else if (pageOffset < -textPage.height) {
            val height = textPage.height
            if (pageFactory.moveToNext(upContent = true)) {
                pageOffset += height.toInt()
            } else {
                pageOffset = -height.toInt()
                pageDelegate?.abortAnim()
            }
        }
        postInvalidate()
    }

    fun submitRenderTask() {
        renderThread.submit(renderRunnable)
    }

    private fun preRenderPage() {
        val view = this
        var invalidate = false
        pageFactory.run {
            if (hasPrev() && prevPage.render(view)) {
                invalidate = true
            }
            if (curPage.render(view)) {
                invalidate = true
            }
            if (hasNext() && nextPage.render(view) && callBack.isScroll) {
                invalidate = true
            }
            if (hasNextPlus() && nextPlusPage.render(view) && callBack.isScroll
                && relativeOffset(2) < ChapterProvider.visibleHeight
            ) {
                invalidate = true
            }
            if (invalidate) {
                postInvalidate()
                pageDelegate?.postInvalidate()
            }
        }
    }

    /**
     * 重置滚动位置
     */
    fun resetPageOffset() {
        pageOffset = 0
    }

    /**
     * 长按
     */
    fun longPress(
        x: Float,
        y: Float,
        select: (textPos: TextPos) -> Unit,
    ) {
        touch(x, y) { _, textPos, _, _, column ->
            when (column) {
                is ImageColumn -> callBack.onImageLongPress(x, y, column.src)
                is TextColumn -> {
                    if (!selectAble) return@touch
                    column.selected = true
                    select(textPos)
                }
            }
        }
    }

    /**
     * 单击
     * @return true:已处理, false:未处理
     */
    @Suppress("UNUSED_ANONYMOUS_PARAMETER")
    fun click(x: Float, y: Float): Boolean {
        var handled = false
        touch(x, y) { _, textPos, textPage, textLine, column ->
            when (column) {
                is ButtonColumn -> {
                    context.toastOnUi("Button Pressed!")
                    handled = true
                }

                is ReviewColumn -> {
                    context.toastOnUi("Button Pressed!")
                    handled = true
                }

                is ImageColumn -> if (AppConfig.previewImageByClick) {
                    activity?.showDialogFragment(PhotoDialog(column.src))
                    handled = true
                }

                is TextColumn -> column.noteContent?.let {
                    showNoteBubble(x, y, it)
                    handled = true
                }
            }
        }
        return handled
    }

    private fun showNoteBubble(x: Float, y: Float, content: String) {
        val margin = 16.dpToPx()
        val horizontalPadding = 18.dpToPx()
        val verticalPadding = 14.dpToPx()
        val displayMetrics = resources.displayMetrics
        val maxWidth = min(320.dpToPx(), displayMetrics.widthPixels - margin * 2)
        val textView = TextView(context).apply {
            text = content
            setTextColor(ReadBookConfig.textColor)
            textSize = 16f
            maxLines = 12
            isVerticalScrollBarEnabled = true
            movementMethod = ScrollingMovementMethod.getInstance()
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12.dpToPx().toFloat()
                setColor(ReadBookConfig.bgMeanColor)
                setStroke(1.dpToPx(), ThemeStore.accentColor)
            }
        }
        textView.measure(
            MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        val popupWidth = textView.measuredWidth.coerceAtLeast(120.dpToPx())
        val popupHeight = textView.measuredHeight
        val popup = PopupWindow(textView, popupWidth, popupHeight, true).apply {
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = 8.dpToPx().toFloat()
        }
        val location = IntArray(2)
        getLocationOnScreen(location)
        val anchorX = location[0] + x.roundToInt()
        val anchorY = location[1] + y.roundToInt()
        val popupX = (anchorX - popupWidth / 2)
            .coerceIn(margin, displayMetrics.widthPixels - popupWidth - margin)
        val aboveY = anchorY - popupHeight - margin
        val popupY = if (aboveY >= margin) aboveY else anchorY + margin
        popup.showAtLocation(this, Gravity.TOP or Gravity.START, popupX, popupY)
    }

    /**
     * 当前页面是否处于图片放大状态
     */
    fun isImageZoomActive(): Boolean {
        return imageZooming || imageZoomScale > 1f
    }

    /**
     * 查找触点位置上的图片列
     */
    fun findImageAt(x: Float, y: Float): ImageColumn? {
        if (isImageZoomActive()) return null
        var relativeOffset: Float
        for (relativePos in 0..2) {
            relativeOffset = relativeOffset(relativePos)
            if (relativePos > 0) {
                //滚动翻页
                if (!callBack.isScroll) break
                if (relativeOffset >= ChapterProvider.visibleHeight) break
            }
            val page = relativePage(relativePos)
            for (line in page.lines) {
                if (line.isImage && line.isTouch(x, y, relativeOffset)) {
                    return line.columns.firstOrNull() as? ImageColumn
                }
            }
        }
        return null
    }

    /**
     * 开始图片缩放（双指按下）
     */
    fun startImageZoom(event: MotionEvent) {
        imageZooming = true
        imageZoomInitialScale = imageZoomScale
        imageZoomInitialDistance = max(imageZoomDistance(event), 1f)
        val (fx, fy) = imageZoomFocus(event)
        imageZoomLastPanX = fx
        imageZoomLastPanY = fy
        imageZoomPanMoved = false
        postInvalidate()
    }

    /**
     * 处理图片缩放触摸事件
     * @return 是否仍处于缩放会话
     */
    fun onImageZoomTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                imageZoomGestureStartX = event.x
                imageZoomGestureStartY = event.y - callBack.headerHeight
                imageZoomLastPanX = event.x
                imageZoomLastPanY = event.y - callBack.headerHeight
                imageZoomPanMoved = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2 && imageZooming) {
                    val distance = max(imageZoomDistance(event), 1f)
                    val newScale = (imageZoomInitialScale * distance / imageZoomInitialDistance)
                        .coerceIn(1f, imageZoomMaxScale)
                    val (fx, fy) = imageZoomFocus(event)
                    applyImageZoomScale(newScale, fx, fy)
                } else if (event.pointerCount == 1 && imageZoomScale > 1f) {
                    val x = event.x
                    val y = event.y - callBack.headerHeight
                    val dx = x - imageZoomLastPanX
                    val dy = y - imageZoomLastPanY
                    //以手势起点累计位移判断拖动, 避免短暂拖动被误判为单击
                    if (abs(x - imageZoomGestureStartX) > imageZoomTouchSlop
                        || abs(y - imageZoomGestureStartY) > imageZoomTouchSlop
                    ) {
                        imageZoomPanMoved = true
                    }
                    imageZoomTranslateX += dx
                    imageZoomTranslateY += dy
                    imageZoomLastPanX = x
                    imageZoomLastPanY = y
                    clampImageZoomTranslate()
                    postInvalidate()
                } else {
                    val x = event.x
                    val y = event.y - callBack.headerHeight
                    imageZoomLastPanX = x
                    imageZoomLastPanY = y
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                //一根手指抬起，用剩余手指继续平移
                val index = if (event.actionIndex == 0) 1 else 0
                imageZoomLastPanX = event.getX(index)
                imageZoomLastPanY = event.getY(index) - callBack.headerHeight
                imageZoomGestureStartX = imageZoomLastPanX
                imageZoomGestureStartY = imageZoomLastPanY
                imageZoomPanMoved = false
            }

            MotionEvent.ACTION_UP -> {
                if (imageZooming) {
                    imageZooming = false
                    if (imageZoomScale <= 1.01f) {
                        resetImageZoom()
                        return false
                    }
                    clampImageZoomTranslate()
                    return true
                }
                //单指会话结束
                if (imageZoomPanMoved) {
                    //拖动平移, 保持缩放
                    return imageZoomScale > 1f
                }
                //单击不处理, 双击复位
                val now = SystemClock.uptimeMillis()
                if (now - imageZoomLastTapTime <= imageZoomDoubleTapTimeout
                    && abs(event.x - imageZoomLastTapX) < imageZoomDoubleTapSlop
                    && abs(event.y - callBack.headerHeight - imageZoomLastTapY) < imageZoomDoubleTapSlop
                ) {
                    imageZoomLastTapTime = 0L
                    resetImageZoom()
                    return false
                }
                imageZoomLastTapTime = now
                imageZoomLastTapX = event.x
                imageZoomLastTapY = event.y - callBack.headerHeight
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                imageZooming = false
                resetImageZoom()
                return false
            }
        }
        return true
    }

    private fun imageZoomDistance(event: MotionEvent): Float {
        val x0 = event.getX(0)
        val y0 = event.getY(0) - callBack.headerHeight
        val x1 = event.getX(1)
        val y1 = event.getY(1) - callBack.headerHeight
        return hypot(x1 - x0, y1 - y0)
    }

    private fun imageZoomFocus(event: MotionEvent): Pair<Float, Float> {
        val x0 = event.getX(0)
        val y0 = event.getY(0) - callBack.headerHeight
        val x1 = event.getX(1)
        val y1 = event.getY(1) - callBack.headerHeight
        return (x0 + x1) / 2f to (y0 + y1) / 2f
    }

    /**
     * 以焦点为锚点应用缩放
     */
    private fun applyImageZoomScale(newScale: Float, fx: Float, fy: Float) {
        if (newScale == imageZoomScale) return
        val k = newScale / imageZoomScale
        imageZoomTranslateX = fx - (fx - imageZoomTranslateX) * k
        imageZoomTranslateY = fy - (fy - imageZoomTranslateY) * k
        imageZoomScale = newScale
        postInvalidate()
    }

    /**
     * 限制平移范围，防止图片被移出可视区域
     */
    private fun clampImageZoomTranslate() {
        val scale = imageZoomScale
        if (scale <= 1f) return
        val contentHeight = textPage.height.coerceAtLeast(height.toFloat())
        val maxX = (scale - 1f) * width
        val maxY = (scale - 1f) * contentHeight
        imageZoomTranslateX = imageZoomTranslateX.coerceIn(-maxX, 0f)
        imageZoomTranslateY = imageZoomTranslateY.coerceIn(-maxY, 0f)
    }

    /**
     * 重置图片缩放
     */
    fun resetImageZoom() {
        imageZooming = false
        imageZoomScale = 1f
        imageZoomTranslateX = 0f
        imageZoomTranslateY = 0f
        imageZoomPanMoved = false
        imageZoomLastTapTime = 0L
        postInvalidate()
    }

    /**
     * 选择文字
     */
    fun selectText(
        x: Float,
        y: Float,
        select: (textPos: TextPos) -> Unit,
    ) {
        touchRough(x, y) { _, textPos, _, _, column ->
            if (column is TextColumn) {
                column.selected = true
                select(textPos)
            }
        }
    }

    /**
     * 开始选择符移动
     */
    fun selectStartMove(x: Float, y: Float) {
        touchRough(x, y) { _, textPos, _, _, _ ->
            if (selectStart.compare(textPos) == 0) {
                return@touchRough
            }
            if (textPos.compare(selectEnd) <= 0) {
                selectStartMoveIndex(textPos)
            } else {
                touchRough(x - 2 * cursorWidth, y) { _, textPos, _, _, _ ->
                    if (textPos.compare(selectEnd) > 0) {
                        reverseStartCursor = true
                        reverseEndCursor = false
                        selectEnd.columnIndex++
                        selectStartMoveIndex(selectEnd)
                        selectEndMoveIndex(textPos)
                    }
                }
            }
        }
    }

    /**
     * 结束选择符移动
     */
    fun selectEndMove(x: Float, y: Float) {
        touchRough(x, y) { _, textPos, _, _, _ ->
            if (textPos.compare(selectEnd) == 0) {
                return@touchRough
            }
            if (textPos.compare(selectStart) >= 0) {
                selectEndMoveIndex(textPos)
            } else {
                touchRough(x + 2 * cursorWidth, y) { _, textPos, _, _, _ ->
                    if (textPos.compare(selectStart) < 0) {
                        reverseEndCursor = true
                        reverseStartCursor = false
                        selectStart.columnIndex--
                        selectEndMoveIndex(selectStart)
                        selectStartMoveIndex(textPos)
                    }
                }
            }
        }
    }

    /**
     * 触碰位置信息
     * @param touched 回调
     */
    private fun touch(
        x: Float,
        y: Float,
        touched: (
            relativeOffset: Float,
            textPos: TextPos,
            textPage: TextPage,
            textLine: TextLine,
            column: BaseColumn
        ) -> Unit
    ) {
        if (!visibleRect.contains(x, y)) return
        var relativeOffset: Float
        for (relativePos in 0..2) {
            relativeOffset = relativeOffset(relativePos)
            if (relativePos > 0) {
                //滚动翻页
                if (!callBack.isScroll) return
                if (relativeOffset >= ChapterProvider.visibleHeight) return
            }
            val textPage = relativePage(relativePos)
            for ((lineIndex, textLine) in textPage.lines.withIndex()) {
                if (textLine.isTouch(x, y, relativeOffset)) {
                    for ((charIndex, textColumn) in textLine.columns.withIndex()) {
                        if (textColumn.isTouch(x)) {
                            touched.invoke(
                                relativeOffset,
                                TextPos(relativePos, lineIndex, charIndex),
                                textPage, textLine, textColumn
                            )
                            return
                        }
                    }
                    return
                }
            }
        }
    }

    /**
     * 触碰位置信息
     * 文本选择专用
     * @param touched 回调
     */
    private fun touchRough(
        x: Float,
        y: Float,
        touched: (
            relativeOffset: Float,
            textPos: TextPos,
            textPage: TextPage,
            textLine: TextLine,
            column: BaseColumn
        ) -> Unit
    ) {
        var relativeOffset: Float
        for (relativePos in 0..2) {
            relativeOffset = relativeOffset(relativePos)
            if (relativePos > 0) {
                //滚动翻页
                if (!callBack.isScroll) return
                if (relativeOffset >= ChapterProvider.visibleHeight) return
            }
            val textPage = relativePage(relativePos)
            for (lineIndex in textPage.lines.indices) {
                val textLine = textPage.getLine(lineIndex)
                if (textLine.isTouchY(y, relativeOffset)) {
                    if (textPage.doublePage) {
                        val halfWidth = width / 2
                        if (textLine.isLeftLine && x > halfWidth) {
                            continue
                        }
                        if (!textLine.isLeftLine && x < halfWidth) {
                            continue
                        }
                    }
                    val columns = textLine.columns
                    for (charIndex in columns.indices) {
                        val textColumn = columns[charIndex]
                        if (textColumn.isTouch(x)) {
                            touched.invoke(
                                relativeOffset,
                                TextPos(relativePos, lineIndex, charIndex),
                                textPage, textLine, textColumn
                            )
                            return
                        }
                    }
                    val isLast = columns.first().start < x
                    val charIndex = if (isLast) columns.lastIndex + 1 else -1
                    val textColumn = if (isLast) columns.last() else columns.first()
                    touched.invoke(
                        relativeOffset,
                        TextPos(relativePos, lineIndex, charIndex),
                        textPage, textLine, textColumn
                    )
                    return
                }
            }
        }
    }

    fun getCurVisiblePage(): TextPage {
        val visiblePage = TextPage()
        var relativeOffset: Float
        for (relativePos in 0..2) {
            relativeOffset = relativeOffset(relativePos)
            if (relativePos > 0) {
                //滚动翻页
                if (!callBack.isScroll) break
                if (relativeOffset >= ChapterProvider.visibleHeight) break
            }
            val textPage = relativePage(relativePos)
            val lines = textPage.lines
            for (i in lines.indices) {
                val textLine = lines[i]
                if (textLine.isVisible(relativeOffset)) {
                    val visibleLine = textLine.copy().apply {
                        lineTop += relativeOffset
                        lineBottom += relativeOffset
                    }
                    visiblePage.addLine(visibleLine)
                }
            }
        }
        return visiblePage
    }

    fun getReadAloudPos(): Pair<Int, TextLine>? {
        var relativeOffset: Float
        for (relativePos in 0..2) {
            relativeOffset = relativeOffset(relativePos)
            if (relativePos > 0) {
                //滚动翻页
                if (!callBack.isScroll) break
                if (relativeOffset >= ChapterProvider.visibleHeight) break
            }
            val textPage = relativePage(relativePos)
            val lines = textPage.lines
            for (i in lines.indices) {
                val textLine = lines[i]
                if (textLine.isVisible(relativeOffset)) {
                    val visibleLine = textLine.copy().apply {
                        lineTop += relativeOffset
                        lineBottom += relativeOffset
                    }
                    return textPage.chapterIndex to visibleLine
                }
            }
        }
        return null
    }

    /**
     * 选择开始文字
     */
    fun selectStartMoveIndex(
        relativePagePos: Int,
        lineIndex: Int,
        charIndex: Int,
    ) {
        selectStart.relativePagePos = relativePagePos
        selectStart.lineIndex = lineIndex
        selectStart.columnIndex = max(0, charIndex)
        val textLine = relativePage(relativePagePos).getLine(lineIndex)
        val textColumn = textLine.getColumn(charIndex)
        upSelectedStart(
            if (charIndex < textLine.columns.size) textColumn.start else textColumn.end,
            textLine.lineBottom + relativeOffset(relativePagePos),
            textLine.lineTop + relativeOffset(relativePagePos)
        )
        upSelectChars()
    }

    fun selectStartMoveIndex(textPos: TextPos) = textPos.run {
        selectStartMoveIndex(relativePagePos, lineIndex, columnIndex)
    }

    /**
     * 选择结束文字
     */
    fun selectEndMoveIndex(
        relativePage: Int,
        lineIndex: Int,
        charIndex: Int,
    ) {
        selectEnd.relativePagePos = relativePage
        selectEnd.lineIndex = lineIndex
        val textLine = relativePage(relativePage).getLine(lineIndex)
        selectEnd.columnIndex = min(charIndex, textLine.columns.lastIndex)
        val textColumn = textLine.getColumn(charIndex)
        upSelectedEnd(
            if (charIndex > -1) textColumn.end else textColumn.start,
            textLine.lineBottom + relativeOffset(relativePage)
        )
        upSelectChars()
    }

    fun selectEndMoveIndex(textPos: TextPos) = textPos.run {
        selectEndMoveIndex(relativePagePos, lineIndex, columnIndex)
    }

    private fun upSelectChars() {
        if (!selectStart.isSelected() && !selectEnd.isSelected()) {
            return
        }
        val last = if (callBack.isScroll) 2 else 0
        val textPos = TextPos(0, 0, 0)
        for (relativePos in 0..last) {
            textPos.relativePagePos = relativePos
            val textPage = relativePage(relativePos)
            for ((lineIndex, textLine) in textPage.lines.withIndex()) {
                textPos.lineIndex = lineIndex
                for ((charIndex, column) in textLine.columns.withIndex()) {
                    textPos.columnIndex = charIndex
                    if (column is TextColumn) {
                        val compareStart = textPos.compare(selectStart)
                        val compareEnd = textPos.compare(selectEnd)
                        column.selected = compareStart >= 0 && compareEnd <= 0
                        column.isSearchResult =
                            column.selected && callBack.isSelectingSearchResult
                        if (column.isSearchResult) {
                            textPage.searchResult.add(column)
                        }
                    }
                }
            }
        }
        postInvalidate()
    }

    private fun upSelectedStart(x: Float, y: Float, top: Float) {
        callBack.run {
            upSelectedStart(x, y + headerHeight, top + headerHeight)
        }
    }

    private fun upSelectedEnd(x: Float, y: Float) {
        callBack.run {
            upSelectedEnd(x, y + headerHeight)
        }
    }

    fun resetReverseCursor() {
        reverseStartCursor = false
        reverseEndCursor = false
    }

    fun cancelSelect(clearSearchResult: Boolean = false) {
        val last = if (callBack.isScroll) 2 else 0
        for (relativePos in 0..last) {
            val textPage = relativePage(relativePos)
            textPage.lines.forEach { textLine ->
                textLine.columns.forEach {
                    if (it is TextColumn) {
                        it.selected = false
                        if (clearSearchResult) {
                            it.isSearchResult = false
                            textPage.searchResult.remove(it)
                        }
                    }
                }
            }
        }
        selectStart.reset()
        selectEnd.reset()
        postInvalidate()
        callBack.onCancelSelect()
    }

    fun getSelectedText(): String {
        val textPos = TextPos(0, 0, 0)
        val builder = StringBuilder()
        for (relativePos in selectStart.relativePagePos..selectEnd.relativePagePos) {
            val textPage = relativePage(relativePos)
            textPos.relativePagePos = relativePos
            textPage.lines.forEachIndexed { lineIndex, textLine ->
                textPos.lineIndex = lineIndex
                textLine.columns.forEachIndexed { charIndex, column ->
                    textPos.columnIndex = charIndex
                    val compareStart = textPos.compare(selectStart)
                    val compareEnd = textPos.compare(selectEnd)
                    if (column is TextColumn) {
                        when {
                            compareStart == -1 -> if (
                                selectStart.columnIndex == textLine.columns.size
                                && charIndex == textLine.columns.lastIndex
                            ) {
                                builder.append("\n")
                            }

                            compareEnd == 1 -> if (selectEnd.columnIndex == -1 && charIndex == 0) {
                                builder.append("\n")
                            }

                            compareStart >= 0 && compareEnd <= 0 -> {
                                builder.append(column.charData)
                                if (
                                    textLine.isParagraphEnd
                                    && charIndex == textLine.columns.lastIndex
                                    && compareEnd != 0
                                ) {
                                    builder.append("\n")
                                }
                            }
                        }
                    }
                }
            }
        }
        return builder.toString()
    }

    fun createBookmark(): Bookmark? {
        val page = relativePage(selectStart.relativePagePos)
        page.getTextChapter().let { chapter ->
            ReadBook.book?.let { book ->
                return book.createBookMark().apply {
                    chapterIndex = page.chapterIndex
                    chapterPos = chapter.getReadLength(page.index) +
                            page.getPosByLineColumn(selectStart.lineIndex, selectStart.columnIndex)
                    chapterName = chapter.title
                    bookText = getSelectedText()
                }
            }
        }
        return null
    }

    private fun relativeOffset(relativePos: Int): Float {
        return when (relativePos) {
            0 -> pageOffset.toFloat()
            1 -> pageOffset + textPage.height
            else -> pageOffset + textPage.height + pageFactory.nextPage.height
        }
    }

    fun relativePage(relativePos: Int): TextPage {
        return when (relativePos) {
            0 -> textPage
            1 -> pageFactory.nextPage
            else -> pageFactory.nextPlusPage
        }
    }

    fun setAutoPager(autoPager: AutoPager?) {
        this.autoPager = autoPager
    }

    fun setIsScroll(value: Boolean) {
        isScroll = value
    }

    override fun canScrollVertically(direction: Int): Boolean {
        return callBack.isScroll && pageFactory.hasNext()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                longScreenshot = true
                scrollY = 0
            }

            MotionEvent.ACTION_UP -> {
                longScreenshot = false
                scrollY = 0
            }
        }
        return callBack.onLongScreenshotTouchEvent(event)
    }

    companion object {
        private val renderThread by lazy {
            Executors.newSingleThreadExecutor {
                Thread(it, "TextPageRender")
            }
        }
        private val cursorWidth = 24.dpToPx()
    }

    interface CallBack {
        val headerHeight: Int
        val pageFactory: TextPageFactory
        val pageDelegate: PageDelegate?
        val isScroll: Boolean
        var isSelectingSearchResult: Boolean
        fun upSelectedStart(x: Float, y: Float, top: Float)
        fun upSelectedEnd(x: Float, y: Float)
        fun onImageLongPress(x: Float, y: Float, src: String)
        fun onCancelSelect()
        fun onLongScreenshotTouchEvent(event: MotionEvent): Boolean
    }
}
