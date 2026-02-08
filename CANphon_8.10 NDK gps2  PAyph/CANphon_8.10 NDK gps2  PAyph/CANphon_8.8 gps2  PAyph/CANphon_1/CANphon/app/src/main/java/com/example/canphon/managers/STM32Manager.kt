package com.example.canphon.managers
import com.example.canphon.R
import com.example.canphon.ui.*
import com.example.canphon.managers.*
import com.example.canphon.protocols.*
import com.example.canphon.drivers.*
import com.example.canphon.data.*

import android.app.PendingIntent
import android.content.Context
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException

/**
 * STM32 UART Manager for LED Control
 * 
 * Production implementation with VID-based auto-discovery
 * Sends "ON" or "OF" commands to STM32 via separate USB UART
 * Baud Rate: 115200, 8N1
 */
class STM32Manager(private val usbManager: UsbManager) {
    
    companion object {
        private const val TAG = "STM32Manager"
        private const val BAUD_RATE = 115200
        private const val TIMEOUT_MS = 100
        
        // STM32 VID (ST Microelectronics)
        private val STM32_VIDS = listOf(0x0483)
        
        // Waveshare/CH340 VID (to exclude)
        private const val CH340_VID = 0x1A86
    }
    
    private var serialPort: UsbSerialPort? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var connectedDeviceName: String? = null
    
    var isConnected: Boolean = false
        private set
    
    var isLedOn: Boolean = false
        private set
        
    var lastError: String = ""
        private set

    /**
     * Get connected device name
     */
    fun getConnectedDeviceName(): String? = connectedDeviceName

    /**
     * البحث عن جهاز STM32 والاتصال به
     */
    fun connect(context: Context): Boolean {
        try {
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            
            Log.i(TAG, "🔍 Found ${drivers.size} USB devices:")
            drivers.forEachIndexed { index, driver ->
                val vid = driver.device.vendorId
                val pid = driver.device.productId
                val name = driver.device.deviceName
                Log.i(TAG, "   [$index] VID=${String.format("%04X", vid)} PID=${String.format("%04X", pid)} $name")
            }
            
            // البحث عن STM32:
            // 1. استبعاد جهاز CAN المتصل حالياً (لتجنب التضارب)
            val connectedCan = SharedBusManager.getInstance().getConnectedDeviceName()
            
            // 2. تصفية الأجهزة المحتملة (أي جهاز ليس CAN)
            val candidates = drivers.filter { 
                it.device.deviceName != connectedCan 
            }
            
            if (candidates.isEmpty()) {
                lastError = "لم يتم العثور على أجهزة مرشحة"
                Log.w(TAG, "❌ No candidate devices found")
                return false
            }
            
            Log.i(TAG, "🔎 Probing ${candidates.size} candidates for STM32 signature...")
            
            for (driver in candidates) {
                val device = driver.device
                
                // Check permission
                if (!usbManager.hasPermission(device)) {
                    lastError = "جاري طلب الإذن..."
                    Log.w(TAG, "⚠️ No permission for ${device.deviceName} - Requesting...")
                    val intent = android.app.PendingIntent.getBroadcast(
                        context, 0,
                        android.content.Intent("com.example.canphon.USB_PERMISSION"),
                        android.app.PendingIntent.FLAG_MUTABLE
                    )
                    usbManager.requestPermission(device, intent)
                    return false // Return false to wait for user permission
                }
                
                // Try to open and probe
                try {
                    val conn = usbManager.openDevice(device) ?: continue
                    val port = driver.ports[0]
                    port.open(conn)
                    port.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                    
                    // PROBE HANDSHAKE: Send "ID?" -> Expect "STM32"
                    val probeCmd = "ID?".toByteArray(Charsets.US_ASCII)
                    port.write(probeCmd, 100)
                    
                    val buffer = ByteArray(32)
                    val len = port.read(buffer, 200) // 200ms wait for response
                    
                    val response = if (len > 0) String(buffer, 0, len, Charsets.US_ASCII) else ""
                    Log.i(TAG, "👉 Probing ${device.deviceName}: Sent 'ID?', Received '$response'")
                    
                    if (response.contains("STM32")) {
                        // MATCH FOUND!
                        Log.i(TAG, "✅ STM32 Verified! (Device: ${device.deviceName})")
                        
                        usbConnection = conn
                        serialPort = port
                        isConnected = true
                        connectedDeviceName = device.deviceName
                        lastError = ""
                        return true
                    } else {
                        // Not our device, close and continue
                        port.close()
                        conn.close()
                    }
                    
                } catch (e: Exception) {
                    Log.w(TAG, "Probe failed for ${device.deviceName}: ${e.message}")
                }
            }
            
            lastError = "لم يتم العثور على STM32 (No response to 'ID?')"
            Log.w(TAG, "❌ Handshake failed on all candidates")
            return false
            
        } catch (e: Exception) {
            lastError = "خطأ: ${e.message}"
            Log.e(TAG, "STM32 connection error", e)
            return false
        }
    }

    /**
     * Toggle LED state
     */
    fun toggleLed(): Boolean {
        return if (isLedOn) {
            if (sendOffCommand()) { isLedOn = false; true } else false
        } else {
            if (sendOnCommand()) { isLedOn = true; true } else false
        }
    }

    /**
     * Send ON command
     */
    fun sendOnCommand(): Boolean = sendCommand("ON/a")
    
    /**
     * Send OF command
     */
    fun sendOffCommand(): Boolean = sendCommand("OF")
    
    private fun sendCommand(command: String): Boolean {
        val port = serialPort
        if (port == null || !isConnected) {
            lastError = "STM32 not connected"
            Log.w(TAG, "STM32 not connected")
            return false
        }
        
        return try {
            val bytes = command.toByteArray(Charsets.US_ASCII)
            port.write(bytes, TIMEOUT_MS)
            Log.i(TAG, "📤 Sent: $command")
            true
        } catch (e: Exception) {
            lastError = "فشل الإرسال: ${e.message}"
            Log.e(TAG, "Send error", e)
            disconnect()
            return false
        }
    }

    /**
     * Disconnect
     */
    fun disconnect() {
        try { serialPort?.close() } catch (e: Exception) { }
        serialPort = null
        usbConnection?.close()
        usbConnection = null
        isConnected = false
        connectedDeviceName = null
        isLedOn = false
        Log.i(TAG, "STM32 disconnected")
    }
}

