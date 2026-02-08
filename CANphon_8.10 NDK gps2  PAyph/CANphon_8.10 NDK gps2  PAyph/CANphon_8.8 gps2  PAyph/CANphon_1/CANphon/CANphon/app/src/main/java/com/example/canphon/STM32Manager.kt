package com.example.canphon

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
    
    var isConnected: Boolean = false
        private set
    
    var isLedOn: Boolean = false
        private set

    /**
     * البحث عن جهاز STM32 والاتصال به
     */
    fun connect(): Boolean {
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
            // 1. أولاً ابحث عن VID = 0x0483 (ST Microelectronics)
            // 2. إذا لم يوجد، ابحث عن أي جهاز غير Waveshare (VID != 0x1A86)
            var stm32Driver = drivers.find { driver ->
                STM32_VIDS.contains(driver.device.vendorId)
            }
            
            if (stm32Driver == null) {
                // ابحث عن أي جهاز غير CH340/Waveshare
                stm32Driver = drivers.find { driver ->
                    driver.device.vendorId != CH340_VID
                }
            }
            
            if (stm32Driver == null) {
                Log.w(TAG, "❌ No STM32 device found (only Waveshare/CH340 detected)")
                return false
            }
            
            val device = stm32Driver.device
            Log.i(TAG, "📍 Selected STM32: VID=${String.format("%04X", device.vendorId)} PID=${String.format("%04X", device.productId)}")
            
            // Check permission
            if (!usbManager.hasPermission(device)) {
                Log.w(TAG, "⚠️ No permission for STM32 device")
                return false
            }
            
            usbConnection = usbManager.openDevice(device)
            if (usbConnection == null) {
                Log.e(TAG, "❌ Failed to open STM32 connection")
                return false
            }
            
            serialPort = stm32Driver.ports[0]
            serialPort?.open(usbConnection)
            serialPort?.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            
            isConnected = true
            Log.i(TAG, "✅ STM32 connected at $BAUD_RATE baud")
            return true
            
        } catch (e: Exception) {
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
    fun sendOnCommand(): Boolean = sendCommand("ON")
    
    /**
     * Send OF command
     */
    fun sendOffCommand(): Boolean = sendCommand("OF")
    
    private fun sendCommand(command: String): Boolean {
        val port = serialPort
        if (port == null || !isConnected) {
            Log.w(TAG, "STM32 not connected")
            return false
        }
        
        return try {
            val bytes = command.toByteArray(Charsets.US_ASCII)
            port.write(bytes, TIMEOUT_MS)
            Log.i(TAG, "📤 Sent: $command")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Send error", e)
            false
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
        isLedOn = false
        Log.i(TAG, "STM32 disconnected")
    }
}
