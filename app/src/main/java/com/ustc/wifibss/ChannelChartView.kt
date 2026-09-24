package com.ustc.wifibss

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * 信道频谱图视图
 * 以频率为横轴，每个 AP 按其信道宽度绘制钟形曲线，峰值为 RSSI
 */
class ChannelChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        // 图表边距
        const val MARGIN_TOP = 6f
        const val MARGIN_BOTTOM = 4f
        const val MARGIN_LEFT = 46f
        const val MARGIN_RIGHT = 12f
        const val BOTTOM_ZONE_HEIGHT = 34f

        // 画笔
        const val STROKE_WIDTH_THIN = 1f
        const val STROKE_WIDTH_CURVE = 2.5f
        const val STROKE_WIDTH_CURRENT = 4f
        const val DASH_WIDTH = 8f
        const val DASH_GAP = 8f

        // 文字
        const val TEXT_SIZE_LABEL = 22f
        const val TEXT_SIZE_TICK = 22f
        const val MAX_LABEL_CHARS = 16

        // 坐标偏移
        const val GRID_TEXT_OFFSET = 2f
        const val TICK_Y_OFFSET = 2f
        const val LABEL_LINE_HEIGHT = 24f
        const val LABEL_GAP = 6f
        const val MAX_LABEL_LINES = 3

        // RSSI 范围
        const val RSSI_MAX = -30
        const val RSSI_MIN = -90
        const val RSSI_RANGE = 60f

        // 曲线采样点数（每个 AP）
        const val CURVE_SAMPLES = 40

        // 钟形曲线底部相对峰值的额外衰减（MHz 带宽越宽衰减越多）
        const val WIDTH_DECAY_FACTOR = 0.35f

        // 填充透明度
        const val FILL_ALPHA = 56
        const val FILL_ALPHA_CURRENT = 80

        // AP 颜色
        val AP_COLORS = listOf(
            Color.parseColor("#2196F3"),
            Color.parseColor("#FF9800"),
            Color.parseColor("#4CAF50"),
            Color.parseColor("#9C27B0"),
            Color.parseColor("#F44336"),
            Color.parseColor("#00BCD4"),
            Color.parseColor("#8BC34A"),
            Color.parseColor("#E91E63"),
            Color.parseColor("#3F51B5"),
            Color.parseColor("#FFEB3B")
        )

        // 网格线 RSSI 值
        val GRID_RSSI_LINES = listOf(-30, -40, -50, -60, -70, -80, -90)

        // 2.4GHz 信道号
        val CHANNELS_24G = (1..14).toList()

        // 5GHz 信道号：只列常用信道，中间 64→100 之间的 DFS 频段在国内基本空闲
        val CHANNELS_5G = listOf(36, 40, 44, 48, 52, 56, 60, 64, 149, 153, 157, 161, 165)

        // 左边界比信道 1（2412）多留 4 格（20MHz）：信道 1 上的 40MHz 曲线
        // 会向右占到信道 5，左侧留同样的空档才对称
        const val FREQ_MIN_24G = 2392
        const val FREQ_MAX_24G = 2484

        // 5GHz 横轴拆成两段连续区间，跳过中间无信道的 DFS 空档，避免图上大片空白。
        // 每段为 [起始频率, 结束频率]；宽信道曲线按真实频段绘制（主信道在最低端），
        // 最大占用到 ch64 上缘 5330 / ch165 上缘 5835，两端各留 20MHz 空档即可。
        val SEGMENTS_5G = listOf(
            5150 to 5350,   // 信道 36-64  (5170/5330 = ch36 下缘/ch64 上缘，各外扩 20)
            5715 to 5855    // 信道 149-165 (5735/5835 = ch149 下缘/ch165 上缘，各外扩 20)
        )

        // 最外侧留白：按像素给（dp），而非按频率宽度。
        // 若按 MHz 给，它会和同样按 MHz 计算的段内宽度抢同一份预算，
        // 而段间空档是按像素固定的，两者单位不同会让总预算永远配不平。
        const val SEGMENT_EDGE_PAD_DP = 14f

        // 与 SEGMENTS_5G 整体范围保持一致（无分段参数时的回退轴范围）
        const val FREQ_MIN_5G = 5150
        const val FREQ_MAX_5G = 5855

        // 分段之间折叠后的固定宽度占绘图区宽度的比例
        const val SEGMENT_GAP_RATIO = 0.06f
    }

    /**
     * 一条 AP 曲线
     */
    data class ApCurve(
        val bssid: String,
        val label: String,
        val freqMhz: Int,      // 中心频率
        val channel: Int,
        val rssi: Int,
        val widthMhz: Int,
        val color: Int,
        val isCurrent: Boolean
    )

    private var curves: List<ApCurve> = emptyList()
    private var minFreq = FREQ_MIN_24G
    private var maxFreq = FREQ_MAX_24G
    private var channels: List<Int> = CHANNELS_24G

    // 横轴分段。单段时为连续轴；多段时跳过段间空档
    private var segments: List<Pair<Int, Int>> = listOf(FREQ_MIN_24G to FREQ_MAX_24G)

    // 空数据提示
    private var emptyHint: String = ""

    // 颜色（主题感知，从资源加载）
    private var colorGrid: Int = 0
    private var colorTickLine: Int = 0
    private var colorText: Int = 0
    private var colorSecondary: Int = 0
    private var colorCurrentMarker: Int = Color.parseColor("#F44336")

    // 画笔（复用，避免 onDraw 中创建）
    private val gridPaint = Paint().apply {
        strokeWidth = STROKE_WIDTH_THIN
        style = Paint.Style.STROKE
    }

    private val tickLinePaint = Paint().apply {
        strokeWidth = STROKE_WIDTH_THIN
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(DASH_WIDTH, DASH_GAP), 0f)
    }

    private val textPaint = Paint().apply {
        textSize = TEXT_SIZE_LABEL
        isAntiAlias = true
    }

    private val tickPaint = Paint().apply {
        textSize = TEXT_SIZE_TICK
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    private val curveStrokePaint = Paint().apply {
        strokeWidth = STROKE_WIDTH_CURVE
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val curveFillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val currentMarkerPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val labelPaint = Paint().apply {
        textSize = TEXT_SIZE_LABEL
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    private val labelBgPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
        alpha = 180
    }

    private val curvePath = Path()
    private val fillPath = Path()

    // 绘图区（复用，避免 onDraw 中分配）
    private val chartRect = RectF()

    init {
        loadColors()
    }

    private fun loadColors() {
        colorGrid = context.getColor(R.color.chart_grid)
        colorTickLine = context.getColor(R.color.chart_time_line)
        colorText = context.getColor(R.color.chart_text)
        colorSecondary = context.getColor(R.color.text_secondary)
        gridPaint.color = colorGrid
        tickLinePaint.color = colorTickLine
        textPaint.color = colorText
        tickPaint.color = colorSecondary
        labelPaint.color = context.getColor(R.color.text_primary)
        labelBgPaint.color = context.getColor(R.color.input_background)
        currentMarkerPaint.color = colorCurrentMarker
    }

    /**
     * 设置要显示的 AP 曲线
     *
     * @param aps     曲线数据
     * @param minFreq 横轴最小频率 (MHz)
     * @param maxFreq 横轴最大频率 (MHz)
     * @param channels 底部刻度显示的信道号
     * @param emptyHint 无数据时显示的提示文字
     */
    fun setAps(
        aps: List<ApCurve>,
        minFreq: Int,
        maxFreq: Int,
        channels: List<Int>,
        emptyHint: String = "",
        segments: List<Pair<Int, Int>>? = null
    ) {
        this.curves = aps
        this.minFreq = minFreq
        this.maxFreq = maxFreq
        this.channels = channels
        this.emptyHint = emptyHint
        this.segments = segments?.takeIf { it.isNotEmpty() } ?: listOf(minFreq to maxFreq)
        postInvalidate()
    }

    fun clear() {
        curves = emptyList()
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0 || height <= 0) return

        val chartWidth = width - MARGIN_LEFT - MARGIN_RIGHT
        val chartHeight = height - MARGIN_TOP - MARGIN_BOTTOM - BOTTOM_ZONE_HEIGHT
        if (chartWidth <= 0 || chartHeight <= 0) return

        chartRect.set(MARGIN_LEFT, MARGIN_TOP, MARGIN_LEFT + chartWidth, MARGIN_TOP + chartHeight)

        drawGridLines(canvas, chartRect)
        drawChannelTicks(canvas, chartRect)

        if (curves.isEmpty()) {
            drawEmptyHint(canvas, chartRect)
            return
        }

        // 非当前 AP 先绘制（填充 + 描边），当前 AP 后绘制以保证在最上层
        for (curve in curves) {
            if (!curve.isCurrent) drawCurve(canvas, curve, chartRect, isCurrent = false)
        }
        for (curve in curves) {
            if (curve.isCurrent) drawCurve(canvas, curve, chartRect, isCurrent = true)
        }

        drawCurveLabels(canvas, chartRect)
    }

    private fun drawGridLines(canvas: Canvas, rect: RectF) {
        for (rssi in GRID_RSSI_LINES) {
            val y = getYForRssi(rssi.toFloat(), rect)
            canvas.drawLine(rect.left, y, rect.right, y, gridPaint)
            canvas.drawText("$rssi", rect.left - GRID_TEXT_OFFSET, y + TEXT_SIZE_LABEL / 3f, textPaint)
        }
    }

    /**
     * 底部通道刻度：竖直虚线 + 信道号
     */
    private fun drawChannelTicks(canvas: Canvas, rect: RectF) {
        val tickY = rect.bottom + TEXT_SIZE_TICK + TICK_Y_OFFSET
        for (ch in channels) {
            val freq = channelToFrequency(ch)
            if (!inAnySegment(freq.toFloat())) continue
            val x = getXForFreq(freq.toFloat(), rect)
            canvas.drawLine(x, rect.top, x, rect.bottom, tickLinePaint)
            canvas.drawText("$ch", x, tickY, tickPaint)
        }

        // 段间空档标记：画斜线断口，提示此处频率不连续
        if (segments.size > 1) {
            val bounds = computeSegmentBounds(rect)
            for (i in 0 until bounds.size - 1) {
                val gapStart = bounds[i].second
                val gapEnd = bounds[i + 1].first
                val midX = (gapStart + gapEnd) / 2f
                if (gapEnd - gapStart < 6f) continue
                val half = (gapEnd - gapStart) / 2f - 1f
                canvas.drawLine(midX - half, rect.bottom, midX + half, rect.top, gridPaint)
                canvas.drawLine(midX + half, rect.bottom, midX - half, rect.top, gridPaint)
            }
        }
    }

    /**
     * 绘制单个 AP 的钟形曲线
     */
    private fun drawCurve(canvas: Canvas, curve: ApCurve, rect: RectF, isCurrent: Boolean) {
        val centerFreq = curve.freqMhz.toFloat()
        val halfWidth = (curve.widthMhz / 2f).coerceAtLeast(5f)

        // 5G/6G 宽信道由主信道与更高编号信道绑定（如 ch36/160MHz = 36~64，
        // 主信道在频段最低端）：曲线只覆盖真实频段 [主信道下缘, 下缘+带宽]，
        // 峰在主信道、向右覆盖绑定的信道，不以主信道为中心对称绘制。
        // 2.4G 无法从扫描结果判定 HT40 向高/向低扩展，仍按对称绘制。
        val wideBonded = centerFreq > 3000f && curve.widthMhz > 20
        val bandLo = if (wideBonded) centerFreq - 10f else centerFreq - halfWidth
        val bandHi = if (wideBonded) centerFreq - 10f + curve.widthMhz else centerFreq + halfWidth

        // 峰高按带宽略作衰减，避免宽信道曲线视觉上完全淹没窄信道
        val peakRssi = curve.rssi.toFloat()
        val bottomRssi = (peakRssi - (curve.widthMhz / 20f) * WIDTH_DECAY_FACTOR * 10f)
            .coerceAtLeast(RSSI_MIN.toFloat())

        val bottomY = rect.bottom
        curveFillPaint.color = curve.color
        curveFillPaint.alpha = if (isCurrent) FILL_ALPHA_CURRENT else FILL_ALPHA
        curveStrokePaint.color = curve.color
        curveStrokePaint.strokeWidth = if (isCurrent) STROKE_WIDTH_CURRENT else STROKE_WIDTH_CURVE

        // 逐段绘制：曲线可能跨越段间空档，若用单条路径会把空档一起填满
        for ((segLo, segHi) in segments) {
            if (bandHi < segLo || bandLo > segHi) continue

            curvePath.reset()
            fillPath.reset()

            var started = false
            var lastX = 0f

            for (i in 0..CURVE_SAMPLES) {
                val t = i.toFloat() / CURVE_SAMPLES
                // 从曲线域左边缘采样到右边缘
                val freq = bandLo + t * (bandHi - bandLo)
                if (freq < segLo || freq > segHi) {
                    started = false
                    continue
                }

                val rssi = if (wideBonded) {
                    // 峰在主信道刻度处：左侧 10MHz 内落底（与窄信道观感一致），
                    // 右侧以升余弦长弧跨过全部绑定信道衰减到频段右缘
                    val side = if (freq <= centerFreq) centerFreq - bandLo else bandHi - centerFreq
                    val p = if (side <= 0f) 1f else abs((freq - centerFreq) / side).coerceIn(0f, 1f)
                    peakRssi - (1f - cos((PI * p).toDouble()).toFloat()) / 2f * (peakRssi - bottomRssi)
                } else {
                    // 升余弦：距离中心越远越接近 bottomRssi
                    val d = abs(freq - centerFreq) / halfWidth
                    val shape = (1f - cos((PI * d.coerceIn(0f, 1f)).toDouble()).toFloat()) / 2f
                    peakRssi - shape * (peakRssi - bottomRssi)
                }

                val x = getXForFreq(freq, rect)
                val y = getYForRssi(rssi, rect)

                if (!started) {
                    curvePath.moveTo(x, y)
                    fillPath.moveTo(x, bottomY)
                    fillPath.lineTo(x, y)
                    started = true
                } else {
                    curvePath.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
                lastX = x
            }

            if (curvePath.isEmpty) continue

            // 填充（半透明）：从最后一个采样点垂直回到基线闭合
            fillPath.lineTo(lastX, bottomY)
            fillPath.close()

            canvas.drawPath(fillPath, curveFillPaint)
            canvas.drawPath(curvePath, curveStrokePaint)
        }

        // 当前 AP 在峰值处画标记点（中心频率不在任何段内时跳过）
        if (isCurrent && inAnySegment(centerFreq)) {
            val x = getXForFreq(centerFreq, rect)
            val y = getYForRssi(peakRssi, rect)
            canvas.drawCircle(x, y, STROKE_WIDTH_CURRENT + 2f, currentMarkerPaint)
        }
    }

    /**
     * 绘制 AP 名称标签，贪心避让防止重叠
     */
    private fun drawCurveLabels(canvas: Canvas, rect: RectF) {
        // 按峰值 x 排序
        val sorted = curves
            .filter { inAnySegment(it.freqMhz.toFloat()) }
            .sortedBy { it.freqMhz }
        if (sorted.isEmpty()) return

        // 记录每一行最后一个标签的右边界
        val lineRightEdges = FloatArray(MAX_LABEL_LINES) { Float.NEGATIVE_INFINITY }

        for (curve in sorted) {
            val text = curve.label.take(MAX_LABEL_CHARS)
            if (text.isEmpty()) continue

            val textWidth = labelPaint.measureText(text)
            val centerX = getXForFreq(curve.freqMhz.toFloat(), rect)
            var left = centerX - textWidth / 2f
            var right = centerX + textWidth / 2f

            // 收边到所属分段：跨段的标签会被吸附到空档里、与曲线脱节，必须先夹到段边界
            val (segLeftX, segRightX) = segmentBoundsFor(curve.freqMhz.toFloat(), rect)
            if (right - left > segRightX - segLeftX) {
                // 段太窄装不下，左对齐到段起点
                left = segLeftX
                right = left + textWidth
            } else {
                if (left < segLeftX) {
                    left = segLeftX
                    right = left + textWidth
                }
                if (right > segRightX) {
                    right = segRightX
                    left = right - textWidth
                }
            }

            // 找一个不与上一标签重叠的行
            var line = 0
            while (line < MAX_LABEL_LINES && left < lineRightEdges[line] + LABEL_GAP) {
                line++
            }
            if (line >= MAX_LABEL_LINES) line = MAX_LABEL_LINES - 1

            lineRightEdges[line] = right

            val baseY = rect.top + TEXT_SIZE_LABEL + line * LABEL_LINE_HEIGHT
            // 标签底衬，避免与曲线糊在一起
            canvas.drawRect(
                left - 2f,
                baseY - TEXT_SIZE_LABEL + 2f,
                right + 2f,
                baseY + 3f,
                labelBgPaint
            )
            labelPaint.color = curve.color
            canvas.drawText(text, (left + right) / 2f, baseY, labelPaint)
        }
    }

    private fun drawEmptyHint(canvas: Canvas, rect: RectF) {
        if (emptyHint.isEmpty()) return
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(emptyHint, rect.centerX(), rect.centerY(), textPaint)
        textPaint.textAlign = Paint.Align.LEFT
    }

    /**
     * 每段在绘图区中占用的 [起始 x, 结束 x]。
     *
     * 单段时铺满整宽；多段时把内侧空档折叠成固定宽度，但每段最外侧仍保留
     * [SEGMENT_EDGE_PAD_MHZ] 对应的真实频率宽度，这样 36 左边 / 64 右边 /
     * 149 左边 / 165 右边都会留空，而不是被拉伸到贴着绘图区边缘。
     */
    private fun computeSegmentBounds(rect: RectF): List<Pair<Float, Float>> {
        val total = rect.right - rect.left
        if (segments.size <= 1) {
            return listOf(rect.left to rect.right)
        }

        // 两端留白按 dp 固定，段间空档按绘图区宽度的比例固定，剩下的才是段内可用宽度。
        // 三者单位统一为像素，预算才能配平（总占用恒等于 total）。
        val edgePx = SEGMENT_EDGE_PAD_DP * context.resources.displayMetrics.density
        val gapPx = total * SEGMENT_GAP_RATIO
        val reserved = 2f * edgePx + gapPx * (segments.size - 1)
        val body = (total - reserved).coerceAtLeast(1f)

        val sumSpan = segments.sumOf { (it.second - it.first).toDouble() }.toFloat()
        if (sumSpan <= 0f) return listOf(rect.left to rect.right)

        // 段内频率宽度按真实比例瓜分 body
        val pxPerMhz = body / sumSpan

        val bounds = mutableListOf<Pair<Float, Float>>()
        var x = rect.left + edgePx
        for (seg in segments) {
            val w = (seg.second - seg.first) * pxPerMhz
            bounds.add(x to (x + w))
            x += w + gapPx
        }
        return bounds
    }

    /**
     * 频率映射到 x。落在段间空档的频率，吸附到其左侧段的右边缘。
     */
    private fun getXForFreq(freq: Float, rect: RectF): Float {
        val bounds = computeSegmentBounds(rect)
        for ((i, seg) in segments.withIndex()) {
            val (lo, hi) = seg
            if (freq <= hi || i == segments.lastIndex) {
                val ratio = ((freq - lo) / (hi - lo).toFloat()).coerceIn(0f, 1f)
                val (x0, x1) = bounds[i]
                return x0 + ratio * (x1 - x0)
            }
        }
        return rect.left
    }

    /**
     * 判断频率是否落在某个段内（段间空档不算）
     */
    private fun inAnySegment(freq: Float): Boolean =
        segments.any { freq >= it.first && freq <= it.second }

    /**
     * 频率所属分段在绘图区占用的 [起始 x, 结束 x]。
     * 频率落在段间空档时，与 [getXForFreq] 保持一致，归属其左侧段。
     */
    private fun segmentBoundsFor(freq: Float, rect: RectF): Pair<Float, Float> {
        val bounds = computeSegmentBounds(rect)
        for ((i, seg) in segments.withIndex()) {
            if (freq <= seg.second || i == segments.lastIndex) return bounds[i]
        }
        return bounds.last()
    }

    private fun getYForRssi(rssi: Float, rect: RectF): Float {
        val clamped = rssi.coerceAtLeast(RSSI_MIN.toFloat())
        val normalized = (clamped - RSSI_MIN) / RSSI_RANGE
        return rect.bottom - (normalized * (rect.bottom - rect.top))
    }

    /**
     * 信道号转中心频率 (MHz)，与 WifiUtils.frequencyToChannel 互为反函数
     */
    private fun channelToFrequency(channel: Int): Int {
        return when {
            channel in 1..13 -> 2407 + channel * 5
            channel == 14 -> 2484
            channel in 32..177 -> 5000 + channel * 5   // 5GHz
            channel in 178..233 -> 5950 + (channel - 1) * 5  // 6GHz
            else -> -1
        }
    }
}
