package com.example.canphon

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet

/**
 * Graph Activity - 4 Servo Charts (Command vs Feedback)
 * Shows real-time graphs for all 4 servos in one screen
 * 
 * IMPORTANT: This activity also runs the servo control loop
 * to keep servos moving while viewing graphs!
 */
class GraphActivity : AppCompatActivity() {

    // Charts for each servo
    private lateinit var chart1: LineChart
    private lateinit var chart2: LineChart
    private lateinit var chart3: LineChart
    private lateinit var chart4: LineChart
    
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private val busManager = SharedBusManager.getInstance()
    private lateinit var gyroManager: GyroManager
    private var startTime = 0L

    private val UPDATE_INTERVAL = 50L // 20Hz for graphs
    private val CONTROL_INTERVAL = 16L // 60Hz for servo commands
    private val VISIBLE_RANGE = 10f   // 10 seconds window

    // Colors
    private val CMD_COLOR = Color.parseColor("#4ECCA3")  // Green
    private val FB_COLOR = Color.parseColor("#FF6B6B")   // Red
    private val BG_COLOR = Color.parseColor("#1A1A2E")   // Dark Blue
    private val CHART_BG = Color.parseColor("#16213E")   // Darker Blue

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Initialize gyro manager for servo control
        gyroManager = GyroManager(this)

        // Main Layout
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_COLOR)
            setPadding(16, 16, 16, 16)
        }

        // Title
        val title = TextView(this).apply {
            text = "📊 Servo Command vs Feedback (Real-Time)"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 16)
        }
        mainLayout.addView(title)

        // Legend
        val legend = TextView(this).apply {
            text = "🟢 Command  |  🔴 Feedback"
            textSize = 12f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }
        mainLayout.addView(legend)

        // Create 4 charts in a 2x2 grid
        val gridLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Row 1: Servo 1 & 2
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
        }
        chart1 = createChartWithLabel(row1, "S1 (FR)")
        chart2 = createChartWithLabel(row1, "S2 (FL)")
        gridLayout.addView(row1)

        // Row 2: Servo 3 & 4
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
        }
        chart3 = createChartWithLabel(row2, "S3 (BR)")
        chart4 = createChartWithLabel(row2, "S4 (BL)")
        gridLayout.addView(row2)

        mainLayout.addView(gridLayout)
        setContentView(mainLayout)

        // Setup all charts
        setupChart(chart1)
        setupChart(chart2)
        setupChart(chart3)
        setupChart(chart4)

        // Start gyro and control loop
        gyroManager.start()
        startGraphing()
        startControlLoop()
    }

    private fun createChartWithLabel(parent: LinearLayout, label: String): LineChart {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setPadding(4, 4, 4, 4)
        }

        val labelView = TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        container.addView(labelView)

        val chart = LineChart(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        container.addView(chart)

        parent.addView(container)
        return chart
    }

    private fun setupChart(chart: LineChart) {
        chart.description.isEnabled = false
        chart.setTouchEnabled(true)
        chart.isDragEnabled = true
        chart.setScaleEnabled(true)
        chart.setDrawGridBackground(false)
        chart.setPinchZoom(true)
        chart.setBackgroundColor(CHART_BG)

        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            textColor = Color.GRAY
            textSize = 8f
            setDrawGridLines(false)
        }

        chart.axisLeft.apply {
            textColor = Color.GRAY
            textSize = 8f
            setDrawGridLines(true)
            gridColor = Color.DKGRAY
            axisMaximum = 30f
            axisMinimum = -30f
        }

        chart.axisRight.isEnabled = false
        chart.legend.isEnabled = false

        chart.data = LineData()
    }

    private fun startGraphing() {
        isRunning = true
        startTime = System.currentTimeMillis()
        handler.post(updateRunnable)
    }

    // Graph update loop (20Hz)
    private val updateRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            addEntries()
            handler.postDelayed(this, UPDATE_INTERVAL)
        }
    }

    // Servo control loop (60Hz) - keeps servos moving!
    private val controlRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            
            if (busManager.isConnected) {
                val roll = gyroManager.roll
                val pitch = gyroManager.pitch
                val yaw = gyroManager.yaw
                
                // Send servo commands
                busManager.sendAllServoCommands(roll, pitch, yaw)
                
                // Process feedback
                busManager.processFeedback()
            }
            
            handler.postDelayed(this, CONTROL_INTERVAL)
        }
    }

    private fun startControlLoop() {
        handler.post(controlRunnable)
    }

    private fun addEntries() {
        val timeSec = (System.currentTimeMillis() - startTime) / 1000f

        // Servo 1
        addEntry(chart1, timeSec, busManager.lastS1Cmd, busManager.s1Feedback)
        
        // Servo 2
        addEntry(chart2, timeSec, busManager.lastS2Cmd, busManager.s2Feedback)
        
        // Servo 3
        addEntry(chart3, timeSec, busManager.lastS3Cmd, busManager.s3Feedback)
        
        // Servo 4
        addEntry(chart4, timeSec, busManager.lastS4Cmd, busManager.s4Feedback)
    }

    private fun addEntry(chart: LineChart, time: Float, cmd: Float, feedback: Float) {
        val data = chart.data ?: return

        var setCmd = data.getDataSetByIndex(0)
        var setFb = data.getDataSetByIndex(1)

        if (setCmd == null) {
            setCmd = createSet("Cmd", CMD_COLOR)
            data.addDataSet(setCmd)
        }
        
        if (setFb == null) {
            setFb = createSet("Fb", FB_COLOR)
            data.addDataSet(setFb)
        }

        // Add Command
        data.addEntry(Entry(time, cmd), 0)

        // Add Feedback (use 0 if invalid)
        val fb = if (feedback < -900) 0f else feedback
        data.addEntry(Entry(time, fb), 1)

        data.notifyDataChanged()
        chart.notifyDataSetChanged()
        chart.setVisibleXRangeMaximum(VISIBLE_RANGE)
        chart.moveViewToX(data.entryCount.toFloat())
    }

    private fun createSet(label: String, color: Int): LineDataSet {
        return LineDataSet(null, label).apply {
            this.color = color
            setCircleColor(color)
            lineWidth = 2f
            circleRadius = 0f
            setDrawCircles(false)
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacks(updateRunnable)
        handler.removeCallbacks(controlRunnable)
        gyroManager.stop()
    }
}
