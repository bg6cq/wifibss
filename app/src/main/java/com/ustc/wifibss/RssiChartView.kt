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

    data class RssiDataPoint(
        val timestamp: Long,
        val rssi: Int
    )

    /**
     * AP 数据系列：存储单个 AP 的 RSSI 历史数据
     */
    data class ApDataSeries(
        val apId: String,           // 唯一标识（BSSID）
        val apName: String,         // AP 名称/楼名
        val bssid: String,          // BSSID 用于显示
        val isCurrentAp: Boolean,   // 是否为当前连接的 AP
        val lineColor: Int,         // 线条颜色
        val isDashed: Boolean,      // 是否虚线
        val dataPoints: ConcurrentLinkedQueue<RssiDataPoint> = ConcurrentLinkedQueue()
    ) {
        fun addDataPoint(rssi: Int) {
            val now = System.currentTimeMillis()
            dataPoints.offer(RssiDataPoint(now, rssi))
            cleanupOldData()
        }

        fun cleanupOldData(maxDurationMs: Long = 10 * 60 * 1000L) {
            val now = System.currentTimeMillis()
            while (dataPoints.isNotEmpty() && now - dataPoints.peek().timestamp > maxDurationMs) {
                dataPoints.poll()
            }
        }

        fun getRecentRssi(): Int {
            return dataPoints.lastOrNull()?.rssi ?: -100
        }
    }

    // 多 AP 数据系列列表
    private val apSeries = CopyOnWriteArrayList<ApDataSeries>()
    private val maxDurationMs = 10 * 60 * 1000L // 10 分钟

    // 图表边距
    private val marginTop = 20f
    private val marginBottom = 50f
    private val marginLeft = 50f
    private val marginRight = 20f
    private val legendHeight = 80f // 图例区域高度

    // 画笔
    private val gridPaint = Paint().apply {
        color = Color.parseColor("#E0E0E0")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val timeLinePaint = Paint().apply {
        color = Color.parseColor("#CCCCCC")
        strokeWidth = 1f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    private val textPaint = Paint().apply {
        color = Color.parseColor("#999999")
        textSize = 24f
        isAntiAlias = true
    }

    private val legendPaint = Paint().apply {
        textSize = 28f
        isAntiAlias = true
    }

    private val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    // 添加或更新 AP 数据系列
    fun addOrUpdateApSeries(apId: String, apName: String, bssid: String, isCurrentAp: Boolean, rssi: Int) {
        val existing = apSeries.find { it.apId == apId }
        if (existing != null) {
            existing.addDataPoint(rssi)
        } else {
            // 新 AP，创建新系列
            val newSeries = ApDataSeries(
                apId = apId,
                apName = apName.ifEmpty { "未知 AP" },
                bssid = bssid,
                isCurrentAp = isCurrentAp,
                lineColor = getApColor(apSeries.size),
                isDashed = !isCurrentAp
            )
            newSeries.addDataPoint(rssi)
            apSeries.add(newSeries)
        }
        postInvalidate()
    }

    // 根据索引获取颜色
    private fun getApColor(index: Int): Int {
        val colors = listOf(
            Color.parseColor("#2196F3"), // 蓝色 - 当前 AP
            Color.parseColor("#FF9800"), // 橙色 - 其他 AP 1
            Color.parseColor("#4CAF50"), // 绿色 - 其他 AP 2
            Color.parseColor("#9C27B0"), // 紫色 - 其他 AP 3
            Color.parseColor("#F44336"), // 红色 - 其他 AP 4
        )
        return colors.getOrElse(index % colors.size) { Color.GRAY }
    }

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

    // 更新单个 AP 的 RSSI
    fun updateRssi(apId: String, rssi: Int) {
        apSeries.find { it.apId == apId }?.addDataPoint(rssi)
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
        val chartHeight = height - marginTop - marginBottom - legendHeight

        if (chartWidth <= 0 || chartHeight <= 0) {
            return
        }

        // 绘制区域
        val chartRect = RectF(marginLeft, marginTop, marginLeft + chartWidth, marginTop + chartHeight)

        // 绘制网格线
        drawGridLines(canvas, chartRect)

        // 绘制时间竖线
        drawTimeLines(canvas, chartRect)

        // 绘制所有 AP 的曲线
        for (series in apSeries) {
            drawApSeries(canvas, series, chartRect)
        }

        // 绘制图例
        drawLegend(canvas, width, height - legendHeight)
    }

    private fun drawGridLines(canvas: Canvas, rect: RectF) {
        val gridLines = listOf(-30, -40, -50, -60, -70, -80, -90)
        for (rssi in gridLines) {
            val y = getYForRssi(rssi.toFloat(), rect)
            canvas.drawLine(rect.left, y, rect.right, y, gridPaint)
            canvas.drawText("$rssi", rect.left + 2f, y - 2f, textPaint)
        }
    }

    private fun drawTimeLines(canvas: Canvas, rect: RectF) {
        val now = System.currentTimeMillis()
        val startTime = now - maxDurationMs
        val startMinute = (startTime / 60000L) * 60000L + 60000L

        var time = startMinute
        while (time < now) {
            val x = getXForTimestamp(time, startTime, now, rect.right - rect.left) + rect.left
            canvas.drawLine(x, rect.top, x, rect.bottom, timeLinePaint)
            time += 60000L
        }
    }

    private fun drawApSeries(canvas: Canvas, series: ApDataSeries, rect: RectF) {
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

        // 设置线型
        val seriesPaint = Paint().apply {
            color = series.lineColor
            strokeWidth = 4f
            style = Paint.Style.STROKE
            isAntiAlias = true
            if (series.isDashed) {
                pathEffect = DashPathEffect(floatArrayOf(15f, 10f), 0f)
            }
        }

        // 绘制折线
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
            canvas.drawCircle(x, y, 3f, pointPaint)
        }
    }

    private fun drawLegend(canvas: Canvas, width: Float, yPosition: Float) {
        if (apSeries.isEmpty()) return

        var currentX = marginLeft
        var currentY = yPosition + 10f
        val lineHeight = 35f
        val maxPerRow = 2 // 每行最多 2 个图例

        for ((index, series) in apSeries.withIndex()) {
            val column = index % maxPerRow
            val row = index / maxPerRow

            currentX = if (column == 0) {
                marginLeft
            } else {
                width / 2
            }
            currentY = yPosition + 10f + row * lineHeight

            // 绘制颜色点
            val dotPaint = Paint().apply {
                color = series.lineColor
                style = if (series.isDashed) Paint.Style.STROKE else Paint.Style.FILL
                strokeWidth = 2f
                isAntiAlias = true
            }
            canvas.drawCircle(currentX + 15f, currentY - 10f, 6f, dotPaint)

            // 绘制文字
            val rssi = series.getRecentRssi()
            val legendText = "${series.apName.take(10)} (${series.bssid.take(6)})  $rssi dBm"
            canvas.drawText(legendText, currentX + 30f, currentY, legendPaint)
        }
    }

    private fun getXForTimestamp(timestamp: Long, startTime: Long, now: Long, chartWidth: Float): Float {
        val ratio = (timestamp - startTime).toFloat() / (now - startTime).toFloat()
        return ratio * chartWidth
    }

    private fun getYForRssi(rssi: Float, rect: RectF): Float {
        // RSSI 范围：-30 (最强) 到 -90 (最弱)
        val normalized = (rssi + 90) / 60f // 0 ~ 1
        return rect.bottom - (normalized * (rect.bottom - rect.top))
    }
}
