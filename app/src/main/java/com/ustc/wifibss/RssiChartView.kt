package com.ustc.wifibss

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import java.util.concurrent.ConcurrentLinkedQueue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RSSI 信号强度图表视图
 * 显示最近 10 分钟的信号强度变化曲线
 */
class RssiChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class RssiDataPoint(
        val timestamp: Long,
        val rssi: Int,
        val bssidChanged: Boolean = false
    )

    // 数据队列：存储最近 10 分钟的数据点
    private val dataPoints = ConcurrentLinkedQueue<RssiDataPoint>()
    private val maxDurationMs = 10 * 60 * 1000L // 10 分钟

    // 画笔
    private val linePaint = Paint().apply {
        color = Color.parseColor("#2196F3")
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val pointPaint = Paint().apply {
        color = Color.parseColor("#2196F3")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val changedPointPaint = Paint().apply {
        color = Color.parseColor("#F44336")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val gridPaint = Paint().apply {
        color = Color.parseColor("#E0E0E0")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val timeLinePaint = Paint().apply {
        color = Color.parseColor("#CCCCCC")
        strokeWidth = 1f
        style = Paint.Style.STROKE
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    private val textPaint = Paint().apply {
        color = Color.parseColor("#999999")
        textSize = 24f
        isAntiAlias = true
    }

    private val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    // 添加数据点
    fun addDataPoint(rssi: Int, bssidChanged: Boolean = false) {
        val now = System.currentTimeMillis()
        dataPoints.offer(RssiDataPoint(now, rssi, bssidChanged))

        // 清理过期数据
        cleanupOldData()
        postInvalidate()
    }

    // 清空数据
    fun clearData() {
        dataPoints.clear()
        postInvalidate()
    }

    private fun cleanupOldData() {
        val now = System.currentTimeMillis()
        while (dataPoints.isNotEmpty() && now - dataPoints.peek().timestamp > maxDurationMs) {
            dataPoints.poll()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()

        if (dataPoints.isEmpty()) {
            return
        }

        val dataList = dataPoints.toList()
        val now = System.currentTimeMillis()
        val startTime = now - maxDurationMs

        // 绘制网格线
        val rssiRange = -30..-90
        val gridLines = listOf(-30, -40, -50, -60, -70, -80, -90)
        for (rssi in gridLines) {
            val y = getYForRssi(rssi.toFloat(), height)
            canvas.drawLine(0f, y, width, y, gridPaint)
            canvas.drawText("$rssi", 2f, y - 2f, textPaint)
        }

        // 绘制时间竖线（每分钟一条）
        drawTimeLines(canvas, width, height, startTime, now)

        // 计算路径
        val path = Path()
        val points = mutableListOf<Pair<Float, Float>>()

        for (point in dataList) {
            val x = getXForTimestamp(point.timestamp, startTime, now, width)
            val y = getYForRssi(point.rssi.toFloat(), height)
            points.add(x to y)
        }

        if (points.isEmpty()) return

        // 绘制折线
        path.moveTo(points[0].first, points[0].second)
        for (i in 1 until points.size) {
            path.lineTo(points[i].first, points[i].second)
        }
        canvas.drawPath(path, linePaint)

        // 绘制数据点
        for ((x, y) in points) {
            canvas.drawCircle(x, y, 3f, pointPaint)
        }

        // 绘制 BSSID 变化点（大圆点）
        for (point in dataList) {
            if (point.bssidChanged) {
                val x = getXForTimestamp(point.timestamp, startTime, now, width)
                val y = getYForRssi(point.rssi.toFloat(), height)
                canvas.drawCircle(x, y, 8f, changedPointPaint)
            }
        }
    }

    private fun getXForTimestamp(timestamp: Long, startTime: Long, now: Long, width: Float): Float {
        val ratio = (timestamp - startTime).toFloat() / (now - startTime).toFloat()
        return ratio * width
    }

    private fun getYForRssi(rssi: Float, height: Float): Float {
        // RSSI 范围：-30 (最强) 到 -90 (最弱)
        val normalized = (rssi + 90) / 60f // 0 ~ 1
        return height - (normalized * height)
    }

    /**
     * 绘制时间竖线（每分钟一条虚线）
     */
    private fun drawTimeLines(canvas: Canvas, width: Float, height: Float, startTime: Long, now: Long) {
        // 找到第一个整分钟时间点
        val startMinute = (startTime / 60000L) * 60000L + 60000L // 向上取整到下一个整分钟
        var time = startMinute
        while (time < now) {
            val x = getXForTimestamp(time, startTime, now, width)
            canvas.drawLine(x, 0f, x, height, timeLinePaint)
            time += 60000L // 下一分钟
        }
    }
}
