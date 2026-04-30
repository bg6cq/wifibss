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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * RSSI 信号强度图表视图
 * 显示最近 10 分钟的信号强度变化曲线，支持多 AP 同时显示
 */
class RssiChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        // 时间范围
        const val MAX_DURATION_MS = 10 * 60 * 1000L
        const val ONE_MINUTE_MS = 60000L

        // 图表边距
        const val MARGIN_TOP = 2f
        const val MARGIN_BOTTOM = 2f
        const val MARGIN_LEFT = 50f
        const val MARGIN_RIGHT = 20f
        const val BOTTOM_ZONE_HEIGHT = 65f

        // 颜色
        const val COLOR_GRID = "#E0E0E0"
        const val COLOR_TIME_LINE = "#CCCCCC"
        const val COLOR_TEXT = "#999999"
        const val COLOR_BSSID_CHANGED = "#F44336"

        // 画笔
        const val STROKE_WIDTH_THIN = 1f
        const val STROKE_WIDTH_LINE = 4f
        const val DASH_WIDTH = 10f
        const val DASH_GAP = 10f

        // 文字
        const val TEXT_SIZE_LABEL = 24f
        const val TEXT_SIZE_LEGEND = 26f
        const val MAX_AP_NAME_CHARS = 30

        // 数据点半径
        const val RADIUS_DATA_POINT = 3f
        const val RADIUS_NEARBY_POINT = 5f
        const val RADIUS_BSSID_MARKER = 8f
        const val RADIUS_LEGEND_DOT = 5f

        // 坐标偏移
        const val GRID_TEXT_OFFSET = 2f
        const val TIME_LABEL_Y_OFFSET = 2f
        const val AP_NAME_Y_OFFSET = 7f
        const val LEGEND_DOT_X_OFFSET = 10f
        const val LEGEND_DOT_Y_OFFSET = 8f
        const val LEGEND_TEXT_X_OFFSET = 22f

        // RSSI
        const val RSSI_MAX = -30
        const val RSSI_MIN = -90
        const val RSSI_RANGE = 60f
        const val DEFAULT_RSSI = -100

        // 网格线
        val GRID_RSSI_LINES = listOf(-30, -40, -50, -60, -70, -80, -90)

        // AP 颜色
        val AP_COLORS = listOf(
            Color.parseColor("#2196F3"),
            Color.parseColor("#FF9800"),
            Color.parseColor("#4CAF50"),
            Color.parseColor("#9C27B0"),
            Color.parseColor("#F44336")
        )

        // 附近 AP 最大显示数
        const val MAX_NEARBY_APS = 2
    }

    data class RssiDataPoint(
        val timestamp: Long,
        val rssi: Int
    )

    data class BssidChangeMarker(
        val timestamp: Long,
        val rssi: Int
    )

    /**
     * AP 数据系列：存储单个 AP 的 RSSI 历史数据
     */
    data class ApDataSeries(
        val apId: String,           // 唯一标识（BSSID）
        var apName: String,         // AP 名称/楼名
        val bssid: String,          // BSSID 用于显示
        var isCurrentAp: Boolean,   // 是否为当前连接的 AP
        val lineColor: Int,         // 线条颜色
        val dataPoints: ConcurrentLinkedQueue<RssiDataPoint> = ConcurrentLinkedQueue(),
        val bssidChangeMarkers: MutableList<BssidChangeMarker> = mutableListOf()
    ) {
        fun addDataPoint(rssi: Int) {
            val now = System.currentTimeMillis()
            dataPoints.offer(RssiDataPoint(now, rssi))
            cleanupOldData()
        }

        fun cleanupOldData(maxDurationMs: Long = MAX_DURATION_MS) {
            val now = System.currentTimeMillis()
            while (dataPoints.isNotEmpty()) {
                val ts = dataPoints.peek()?.timestamp ?: break
                if (now - ts <= maxDurationMs) break
                dataPoints.poll()
            }
            bssidChangeMarkers.removeAll { now - it.timestamp > maxDurationMs }
        }

        fun getRecentRssi(): Int {
            return dataPoints.lastOrNull()?.rssi ?: DEFAULT_RSSI
        }
    }

    // 多 AP 数据系列列表
    private val apSeries = CopyOnWriteArrayList<ApDataSeries>()
    private val maxDurationMs = MAX_DURATION_MS

    // 图表边距
    private val marginTop = MARGIN_TOP
    private val marginBottom = MARGIN_BOTTOM
    private val marginLeft = MARGIN_LEFT
    private val marginRight = MARGIN_RIGHT
    private val bottomZoneHeight = BOTTOM_ZONE_HEIGHT

    // 画笔
    private val gridPaint = Paint().apply {
        color = Color.parseColor(COLOR_GRID)
        strokeWidth = STROKE_WIDTH_THIN
        style = Paint.Style.STROKE
    }

    private val timeLinePaint = Paint().apply {
        color = Color.parseColor(COLOR_TIME_LINE)
        strokeWidth = STROKE_WIDTH_THIN
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(DASH_WIDTH, DASH_GAP), 0f)
    }

    private val textPaint = Paint().apply {
        color = Color.parseColor(COLOR_TEXT)
        textSize = TEXT_SIZE_LABEL
        isAntiAlias = true
    }

    private val legendPaint = Paint().apply {
        textSize = TEXT_SIZE_LEGEND
        isAntiAlias = true
    }

    private val bssidChangedPaint = Paint().apply {
        color = Color.parseColor(COLOR_BSSID_CHANGED)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    // 添加或更新 AP 数据系列
    fun addOrUpdateApSeries(
        apId: String,
        apName: String,
        bssid: String,  // 仅用于显示，不用于标识数据流
        isCurrentAp: Boolean,
        rssi: Int,
        bssidChanged: Boolean = false
    ) {
        val existing = apSeries.find { it.apId == apId }
        if (existing != null) {
            existing.apName = apName
            existing.addDataPoint(rssi)
            if (bssidChanged && existing.isCurrentAp) {
                existing.bssidChangeMarkers.add(
                    BssidChangeMarker(System.currentTimeMillis(), rssi)
                )
            }
        } else {
            val newSeries = ApDataSeries(
                apId = apId,
                apName = apName.ifEmpty { "未知 AP" },
                bssid = bssid,
                isCurrentAp = isCurrentAp,
                lineColor = getApColor(apSeries.size),
            )
            newSeries.addDataPoint(rssi)
            if (bssidChanged && newSeries.isCurrentAp) {
                newSeries.bssidChangeMarkers.add(
                    BssidChangeMarker(System.currentTimeMillis(), rssi)
                )
            }
            apSeries.add(newSeries)
        }
        postInvalidate()
    }

    // 根据索引获取颜色
    private fun getApColor(index: Int): Int =
        AP_COLORS.getOrElse(index % AP_COLORS.size) { Color.GRAY }

    // 清空所有数据
    fun clearData() {
        apSeries.clear()
        postInvalidate()
    }

    // 移除指定的 AP 系列
    fun removeApSeries(apId: String) {
        apSeries.removeAll { it.apId == apId }
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()

        if (apSeries.isEmpty()) {
            return
        }

        val chartWidth = width - marginLeft - marginRight
        val chartHeight = height - marginTop - marginBottom - bottomZoneHeight

        if (chartWidth <= 0 || chartHeight <= 0) {
            return
        }

        // 绘制区域
        val chartRect = RectF(marginLeft, marginTop, marginLeft + chartWidth, marginTop + chartHeight)

        // 计算底部文字位置
        val timeLabelY = chartRect.bottom + marginBottom + textPaint.textSize + TIME_LABEL_Y_OFFSET
        val apNameY = timeLabelY + textPaint.textSize + AP_NAME_Y_OFFSET

        // 绘制网格线和时间竖线
        drawGridLines(canvas, chartRect)
        drawTimeLines(canvas, chartRect, timeLabelY)

        // 先画附近 AP 的点（非当前 AP），再画当前 AP 的曲线
        for (series in apSeries) {
            if (!series.isCurrentAp) {
                drawApDotsOnly(canvas, series, chartRect)
            }
        }
        for (series in apSeries) {
            if (series.isCurrentAp) {
                drawApSeriesWithLine(canvas, series, chartRect)
            }
        }

        // 绘制 AP 名称（在时间标签下方）
        drawApNames(canvas, width, apNameY)
    }

    private fun drawGridLines(canvas: Canvas, rect: RectF) {
        val gridLines = GRID_RSSI_LINES
        for (rssi in gridLines) {
            val y = getYForRssi(rssi.toFloat(), rect)
            canvas.drawLine(rect.left, y, rect.right, y, gridPaint)
            canvas.drawText("$rssi", rect.left + GRID_TEXT_OFFSET, y - GRID_TEXT_OFFSET, textPaint)
        }
    }

    private fun drawTimeLines(canvas: Canvas, rect: RectF, labelY: Float) {
        val now = System.currentTimeMillis()
        val startTime = now - maxDurationMs
        val startMinute = (startTime / ONE_MINUTE_MS) * ONE_MINUTE_MS + ONE_MINUTE_MS

        var time = startMinute
        while (time < now) {
            val x = getXForTimestamp(time, startTime, now, rect.right - rect.left) + rect.left
            canvas.drawLine(x, rect.top, x, rect.bottom, timeLinePaint)
            time += ONE_MINUTE_MS
        }

        // 在图表下方绘制时间标签
        textPaint.textAlign = Paint.Align.CENTER
        var labelTime = startMinute
        while (labelTime < now) {
            val x = getXForTimestamp(labelTime, startTime, now, rect.right - rect.left) + rect.left
            canvas.drawText(dateFormat.format(Date(labelTime)), x, labelY, textPaint)
            labelTime += ONE_MINUTE_MS
        }
        textPaint.textAlign = Paint.Align.LEFT
    }

    /**
     * 仅为当前 AP 绘制完整曲线（实线 + 数据点 + BSSID 切换标记）
     */
    private fun drawApSeriesWithLine(canvas: Canvas, series: ApDataSeries, rect: RectF) {
        val dataList = series.dataPoints.toList()
        if (dataList.isEmpty()) return

        val now = System.currentTimeMillis()
        val startTime = now - maxDurationMs
        val chartWidth = rect.right - rect.left

        // 计算路径
        val path = Path()
        val points = mutableListOf<Pair<Float, Float>>()

        for (point in dataList) {
            val x = getXForTimestamp(point.timestamp, startTime, now, chartWidth) + rect.left
            val y = getYForRssi(point.rssi.toFloat(), rect)
            points.add(x to y)
        }

        if (points.isEmpty()) return

        // 绘制实线
        val seriesPaint = Paint().apply {
            color = series.lineColor
            strokeWidth = STROKE_WIDTH_LINE
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        path.moveTo(points[0].first, points[0].second)
        for (i in 1 until points.size) {
            path.lineTo(points[i].first, points[i].second)
        }
        canvas.drawPath(path, seriesPaint)

        // 绘制数据点
        val pointPaint = Paint().apply {
            color = series.lineColor
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        for ((x, y) in points) {
            canvas.drawCircle(x, y, RADIUS_DATA_POINT, pointPaint)
        }

        // 绘制 BSSID 切换标记（红色大圆点）
        for (marker in series.bssidChangeMarkers) {
            if (marker.timestamp < startTime) continue
            val x = getXForTimestamp(marker.timestamp, startTime, now, chartWidth) + rect.left
            val y = getYForRssi(marker.rssi.toFloat(), rect)
            canvas.drawCircle(x, y, RADIUS_BSSID_MARKER, bssidChangedPaint)
        }
    }

    /**
     * 仅为附近 AP 绘制离散点（不连线）
     */
    private fun drawApDotsOnly(canvas: Canvas, series: ApDataSeries, rect: RectF) {
        val dataList = series.dataPoints.toList()
        if (dataList.isEmpty()) return

        val now = System.currentTimeMillis()
        val startTime = now - maxDurationMs
        val chartWidth = rect.right - rect.left

        val pointPaint = Paint().apply {
            color = series.lineColor
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        for (point in dataList) {
            val x = getXForTimestamp(point.timestamp, startTime, now, chartWidth) + rect.left
            val y = getYForRssi(point.rssi.toFloat(), rect)
            canvas.drawCircle(x, y, RADIUS_NEARBY_POINT, pointPaint)
        }
    }

    private fun drawApNames(canvas: Canvas, width: Float, yPosition: Float) {
        // 只显示非当前 AP 的名称（最多 2 个）, 不显示 dBm
        val nearbySeries = apSeries.filter { !it.isCurrentAp }.take(MAX_NEARBY_APS)
        if (nearbySeries.isEmpty()) return

        val halfWidth = width / 2

        for ((index, series) in nearbySeries.withIndex()) {
            val startX = if (index == 0) marginLeft else halfWidth
            // 绘制颜色点
            val dotPaint = Paint().apply {
                color = series.lineColor
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            canvas.drawCircle(startX + LEGEND_DOT_X_OFFSET, yPosition - LEGEND_DOT_Y_OFFSET, RADIUS_LEGEND_DOT, dotPaint)

            // AP 名称（一行显示，允许更长文字）
            val displayName = series.apName.take(MAX_AP_NAME_CHARS)
            canvas.drawText(displayName, startX + LEGEND_TEXT_X_OFFSET, yPosition, legendPaint)
        }
    }

    private fun getXForTimestamp(timestamp: Long, startTime: Long, now: Long, chartWidth: Float): Float {
        val ratio = (timestamp - startTime).toFloat() / (now - startTime).toFloat()
        return ratio * chartWidth
    }

    private fun getYForRssi(rssi: Float, rect: RectF): Float {
        val normalized = (rssi - RSSI_MIN) / RSSI_RANGE
        return rect.bottom - (normalized * (rect.bottom - rect.top))
    }
}
