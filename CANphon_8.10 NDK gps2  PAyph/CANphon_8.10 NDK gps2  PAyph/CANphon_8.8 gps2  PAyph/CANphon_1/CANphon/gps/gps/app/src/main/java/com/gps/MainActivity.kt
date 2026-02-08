package com.gps

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gps.parser.GpsResult
import com.gps.serial.SerialGpsService
import kotlin.math.sqrt

/**
 * النشاط الرئيسي - واجهة عرض GPS مطابقة لتطبيق Python
 * Main Activity - GPS Viewer matching Python tkinter app
 */
class MainActivity : ComponentActivity() {
    
    private lateinit var gpsService: SerialGpsService
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        gpsService = SerialGpsService(this)
        
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                GpsViewerScreen(gpsService)
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        gpsService.start()
    }
    
    override fun onPause() {
        super.onPause()
        gpsService.stop()
    }
}

// Colors matching Python app
private val BgColor = Color(0xFF1A1A2E)
private val CardBg = Color(0xFF16213E)
private val AccentColor = Color(0xFF00D9FF)
private val ValueColor = Color(0xFF00FF88)
private val ErrorColor = Color(0xFFFF6B6B)

@Composable
fun GpsViewerScreen(gpsService: SerialGpsService) {
    val connectionState by gpsService.connectionState.collectAsState()
    val gpsData by gpsService.gpsData.collectAsState()
    val rawNavData by gpsService.rawNavData.collectAsState()
    val statistics by gpsService.statistics.collectAsState()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        // Title
        Text(
            text = "🛰️ KCA GPS Viewer",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = AccentColor,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        
        // === Connection Section ===
        ConnectionSection(connectionState) { gpsService.reconnect() }
        
        Spacer(modifier = Modifier.height(10.dp))
        
        // === Position Section ===
        SectionCard(title = "📍 الموقع") {
            gpsData?.let { data ->
                DataRow("خط العرض (°)", String.format("%.7f", data.navData.latitude))
                DataRow("خط الطول (°)", String.format("%.7f", data.navData.longitude))
                DataRow("الارتفاع (م)", String.format("%.2f", data.altitudeM))
                HorizontalDivider(color = Color(0xFF0F3460), modifier = Modifier.padding(vertical = 6.dp))
                DataRow("X ECEF (م)", String.format("%.2f", data.navData.x))
                DataRow("Y ECEF (م)", String.format("%.2f", data.navData.y))
                DataRow("Z ECEF (م)", String.format("%.2f", data.navData.z))
            } ?: run {
                DataRow("خط العرض (°)", "--")
                DataRow("خط الطول (°)", "--")
                DataRow("الارتفاع (م)", "--")
            }
        }
        
        Spacer(modifier = Modifier.height(10.dp))
        
        // === Velocity Section ===
        SectionCard(title = "🚀 السرعة") {
            gpsData?.let { data ->
                val speed3d = sqrt(
                    data.navData.vx * data.navData.vx +
                    data.navData.vy * data.navData.vy +
                    data.navData.vz * data.navData.vz
                )
                DataRow("Vx (م/ث)", String.format("%.3f", data.navData.vx))
                DataRow("Vy (م/ث)", String.format("%.3f", data.navData.vy))
                DataRow("Vz (م/ث)", String.format("%.3f", data.navData.vz))
                DataRow("السرعة 3D (م/ث)", String.format("%.3f", speed3d))
            } ?: run {
                DataRow("Vx (م/ث)", "--")
                DataRow("Vy (م/ث)", "--")
                DataRow("Vz (م/ث)", "--")
                DataRow("السرعة 3D (م/ث)", "--")
            }
        }
        
        Spacer(modifier = Modifier.height(10.dp))
        
        // === Satellites Section ===
        SectionCard(title = "🛰️ الأقمار") {
            rawNavData?.let { nav ->
                DataRow("GPS مستخدمة", "${nav.usedSatCount}")
                DataRow("GLONASS مستخدمة", "${nav.glonassUsedSatCount}")
                DataRow("المجموع", "${nav.totalUsedSatellites}")
            } ?: run {
                DataRow("GPS مستخدمة", "--")
                DataRow("GLONASS مستخدمة", "--")
                DataRow("المجموع", "--")
            }
        }
        
        Spacer(modifier = Modifier.height(10.dp))
        
        // === DOP Section ===
        SectionCard(title = "📊 دقة الموقع (DOP)") {
            rawNavData?.let { nav ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    DopItem("GDOP", nav.gdop.toInt() and 0xFF)
                    DopItem("PDOP", nav.pdop.toInt() and 0xFF)
                    DopItem("HDOP", nav.hdop.toInt() and 0xFF)
                    DopItem("VDOP", nav.vdop.toInt() and 0xFF)
                }
            } ?: run {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    DopItem("GDOP", null)
                    DopItem("PDOP", null)
                    DopItem("HDOP", null)
                    DopItem("VDOP", null)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(10.dp))
        
        // === Statistics Section ===
        SectionCard(title = "📈 الإحصائيات") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem("الرسائل", "${statistics.messagesReceived}")
                StatItem("أخطاء CRC", "${statistics.frameErrors}")
                
                val stateText = rawNavData?.let { nav ->
                    when (nav.state.toInt()) {
                        0 -> "❌ لا إصلاح"
                        1 -> "✅ إصلاح 3D"
                        else -> "⚠️ ${nav.state}"
                    }
                } ?: "--"
                StatItem("الحالة", stateText)
                
                val temp = rawNavData?.temperature?.toInt() ?: "--"
                StatItem("الحرارة °C", "$temp")
            }
        }
        
        Spacer(modifier = Modifier.height(10.dp))
        
        // === Acceleration Section ===
        SectionCard(title = "📐 التسارع") {
            rawNavData?.let { nav ->
                DataRow("Ax (م/ث²)", String.format("%.3f", nav.ax))
                DataRow("Ay (م/ث²)", String.format("%.3f", nav.ay))
                DataRow("Az (م/ث²)", String.format("%.3f", nav.az))
            } ?: run {
                DataRow("Ax (م/ث²)", "--")
                DataRow("Ay (م/ث²)", "--")
                DataRow("Az (م/ث²)", "--")
            }
        }
    }
}

@Composable
fun ConnectionSection(
    state: SerialGpsService.ConnectionState,
    onReconnect: () -> Unit
) {
    val (statusText, statusColor) = when (state) {
        SerialGpsService.ConnectionState.CONNECTED -> "🟢 متصل" to ValueColor
        SerialGpsService.ConnectionState.CONNECTING -> "🟡 جاري الاتصال..." to Color(0xFFFFC107)
        SerialGpsService.ConnectionState.SEARCHING -> "🔵 البحث عن جهاز..." to Color(0xFF2196F3)
        SerialGpsService.ConnectionState.NO_DEVICE -> "🟠 لا يوجد جهاز" to Color(0xFFFF5722)
        SerialGpsService.ConnectionState.REQUESTING_PERMISSION -> "🟡 طلب الإذن..." to Color(0xFFFFC107)
        SerialGpsService.ConnectionState.DISCONNECTED -> "⚪ غير متصل" to Color(0xFF9E9E9E)
        is SerialGpsService.ConnectionState.ERROR -> "🔴 خطأ: ${state.message}" to ErrorColor
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("📡 الاتصال", color = AccentColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(statusText, color = statusColor, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            
            Button(
                onClick = onReconnect,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F3460)),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text("🔌 إعادة الاتصال", fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                color = AccentColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            content()
        }
    }
}

@Composable
fun DataRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.Gray, fontSize = 13.sp)
        Text(
            value,
            color = ValueColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun DopItem(label: String, value: Int?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value?.toString() ?: "--",
            color = ValueColor,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Text(label, color = Color.Gray, fontSize = 11.sp)
    }
}

@Composable
fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            color = ValueColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        Text(label, color = Color.Gray, fontSize = 10.sp)
    }
}
