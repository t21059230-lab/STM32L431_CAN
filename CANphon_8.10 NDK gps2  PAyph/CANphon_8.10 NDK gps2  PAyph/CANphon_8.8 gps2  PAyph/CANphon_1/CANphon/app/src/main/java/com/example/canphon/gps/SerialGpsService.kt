package com.example.canphon.gps
import com.example.canphon.R
import com.example.canphon.ui.*
import com.example.canphon.managers.*
import com.example.canphon.protocols.*
import com.example.canphon.drivers.*
import com.example.canphon.data.*

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.concurrent.Executors

/**
 * خدمة استقبال GPS عبر USB Serial
 * GPS Serial Service for receiving GPS data via USB
 * 
 * يكتشف محول USB-TTL ويقرأ بيانات GPS عبر بروتوكول KCA
 */
class SerialGpsService(private val context: Context) {
    
    companion object {
        private const val TAG = "SerialGpsService"
        private const val ACTION_USB_PERMISSION = "com.example.canphon.gps.USB_PERMISSION"
        
        // Default serial settings for KCA GPS
        private const val DEFAULT_BAUD_RATE = 115200
        private const val DEFAULT_DATA_BITS = 8
        private const val DEFAULT_STOP_BITS = UsbSerialPort.STOPBITS_1
        private const val DEFAULT_PARITY = UsbSerialPort.PARITY_NONE
        
        // Singleton instance
        @Volatile
        private var instance: SerialGpsService? = null
        
        fun getInstance(context: Context): SerialGpsService {
            return instance ?: synchronized(this) {
                instance ?: SerialGpsService(context.applicationContext).also { instance = it }
            }
        }
    }
    
    // USB components
    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbSerialPort: UsbSerialPort? = null
    private var connection: UsbDeviceConnection? = null
    private var ioManager: SerialInputOutputManager? = null
    
    // Parser components
    private val gpsAnalyzer = GpsAnalyzer()
    private lateinit var kcaParser: KcaParser
    
    // State
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _gpsData = MutableStateFlow<GpsResult?>(null)
    val gpsData: StateFlow<GpsResult?> = _gpsData.asStateFlow()
    
    private val _rawNavData = MutableStateFlow<NavData?>(null)
    val rawNavData: StateFlow<NavData?> = _rawNavData.asStateFlow()
    
    private val _statistics = MutableStateFlow(Statistics())
    val statistics: StateFlow<Statistics> = _statistics.asStateFlow()
    
    // For SensorCollector integration
    val isConnected: Boolean
        get() = _connectionState.value == ConnectionState.CONNECTED
    
    val lastGpsResult: GpsResult?
        get() = _gpsData.value
    
    // Callback
    var onGpsDataReceived: ((GpsResult) -> Unit)? = null
    
    // List of device names to exclude (e.g., servo adapter)
    var excludedDeviceNames: List<String> = emptyList()
    
    // Prevent double registration
    private var isStarted = false
    
    init {
        kcaParser = KcaParser { navData ->
            _rawNavData.value = navData
            val result = gpsAnalyzer.analyze(navData)
            if (result != null && result.isValid) {
                _gpsData.value = result
                onGpsDataReceived?.invoke(result)
            }
            updateStatistics()
        }
    }
    
    /**
     * BroadcastReceiver لأذونات USB
     */
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { openDevice(it) }
                    } else {
                        Log.w(TAG, "USB permission denied")
                        _connectionState.value = ConnectionState.ERROR("Permission denied")
                    }
                }
            }
        }
    }
    
    /**
     * بدء الخدمة والبحث عن أجهزة USB
     */
    fun start() {
        // Prevent double registration
        if (isStarted) {
            // Already started, just try to connect if not connected
            if (_connectionState.value == ConnectionState.DISCONNECTED || 
                _connectionState.value == ConnectionState.NO_DEVICE) {
                findAndConnect()
            }
            return
        }
        isStarted = true
        
        // Register receiver
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbPermissionReceiver, filter)
        }
        
        // Find and connect to GPS device
        findAndConnect()
    }
    
    /**
     * البحث عن جهاز GPS والاتصال به
     */
    fun findAndConnect() {
        _connectionState.value = ConnectionState.SEARCHING
        
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        
        if (availableDrivers.isEmpty()) {
            Log.w(TAG, "No USB serial devices found")
            _connectionState.value = ConnectionState.NO_DEVICE
            return
        }
        
        // Find first driver that is not in excluded list
        val driver = availableDrivers.find { drv ->
            !excludedDeviceNames.contains(drv.device.deviceName)
        }
        
        if (driver == null) {
            Log.w(TAG, "No available GPS device (all excluded)")
            _connectionState.value = ConnectionState.NO_DEVICE
            return
        }
        
        val device = driver.device
        Log.d(TAG, "Found GPS device: ${device.deviceName} VID=${String.format("0x%04X", device.vendorId)}")
        
        // Check if permission is needed
        if (!usbManager.hasPermission(device)) {
            Log.d(TAG, "Requesting USB permission")
            _connectionState.value = ConnectionState.REQUESTING_PERMISSION
            
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION), flags
            )
            usbManager.requestPermission(device, permissionIntent)
        } else {
            openDevice(device)
        }
    }
    
    /**
     * فتح اتصال مع جهاز USB
     */
    private fun openDevice(device: UsbDevice) {
        try {
            _connectionState.value = ConnectionState.CONNECTING
            
            val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
            if (driver == null) {
                _connectionState.value = ConnectionState.ERROR("No driver for device")
                return
            }
            
            connection = usbManager.openDevice(device)
            if (connection == null) {
                _connectionState.value = ConnectionState.ERROR("Could not open device")
                return
            }
            
            usbSerialPort = driver.ports[0]
            usbSerialPort?.open(connection)
            usbSerialPort?.setParameters(
                DEFAULT_BAUD_RATE,
                DEFAULT_DATA_BITS,
                DEFAULT_STOP_BITS,
                DEFAULT_PARITY
            )
            
            // Enable DTR/RTS
            try {
                usbSerialPort?.dtr = true
                usbSerialPort?.rts = true
            } catch (e: Exception) {
                Log.w(TAG, "DTR/RTS not supported")
            }
            
            // Start reading data
            startReading()
            
            _connectionState.value = ConnectionState.CONNECTED
            Log.i(TAG, "✅ Connected to GPS device: ${device.deviceName}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error opening device", e)
            _connectionState.value = ConnectionState.ERROR(e.message ?: "Unknown error")
        }
    }
    
    /**
     * بدء قراءة البيانات
     */
    private fun startReading() {
        val port = usbSerialPort ?: return
        
        ioManager = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                kcaParser.parseBytes(data)
            }
            
            override fun onRunError(e: Exception) {
                Log.e(TAG, "Serial read error", e)
                _connectionState.value = ConnectionState.ERROR(e.message ?: "Read error")
            }
        })
        
        Executors.newSingleThreadExecutor().submit(ioManager)
    }
    
    /**
     * تحديث الإحصائيات
     */
    private fun updateStatistics() {
        _statistics.value = Statistics(
            bytesReceived = kcaParser.byteCount,
            messagesReceived = kcaParser.messageCount,
            frameErrors = kcaParser.frameErrorCount,
            validGpsUpdates = gpsAnalyzer.gpsUpdateCount
        )
    }
    
    /**
     * الحصول على اسم الجهاز المتصل
     */
    fun getConnectedDeviceName(): String? {
        return if (isConnected) usbSerialPort?.device?.deviceName else null
    }
    
    /**
     * إيقاف الخدمة
     */
    fun stop() {
        if (!isStarted) return
        
        try {
            context.unregisterReceiver(usbPermissionReceiver)
        } catch (e: Exception) {
            // Receiver not registered
        }
        
        ioManager?.stop()
        ioManager = null
        
        try {
            usbSerialPort?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing port", e)
        }
        usbSerialPort = null
        
        connection?.close()
        connection = null
        
        _connectionState.value = ConnectionState.DISCONNECTED
        isStarted = false
    }
    
    /**
     * إعادة الاتصال
     */
    fun reconnect() {
        stop()
        kcaParser.reset()
        gpsAnalyzer.reset()
        start()
    }
    
    /**
     * حالات الاتصال
     */
    sealed class ConnectionState {
        object DISCONNECTED : ConnectionState()
        object SEARCHING : ConnectionState()
        object NO_DEVICE : ConnectionState()
        object REQUESTING_PERMISSION : ConnectionState()
        object CONNECTING : ConnectionState()
        object CONNECTED : ConnectionState()
        data class ERROR(val message: String) : ConnectionState()
    }
    
    /**
     * إحصائيات الاستقبال
     */
    data class Statistics(
        val bytesReceived: Long = 0,
        val messagesReceived: Long = 0,
        val frameErrors: Long = 0,
        val validGpsUpdates: Long = 0
    )
}

