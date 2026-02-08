package com.example.canphon.drivers
import com.example.canphon.R
import com.example.canphon.ui.*
import com.example.canphon.managers.*
import com.example.canphon.protocols.*
import com.example.canphon.drivers.*
import com.example.canphon.data.*

import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.Ch34xSerialDriver
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.ProlificSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialProber

/**
 * Custom USB Serial Prober
 * 
 * Adds support for additional USB-Serial chips that may not be
 * recognized by the default prober.
 */
object CustomProber {
    
    /**
     * Creates a prober that supports all common USB-Serial chips
     */
    fun getCustomProber(): UsbSerialProber {
        val customTable = ProbeTable()
        
        // CH340 / CH341 variants
        customTable.addProduct(0x1A86, 0x7523, Ch34xSerialDriver::class.java) // CH340
        customTable.addProduct(0x1A86, 0x5523, Ch34xSerialDriver::class.java) // CH341
        customTable.addProduct(0x1A86, 0x7522, Ch34xSerialDriver::class.java) // CH340K
        customTable.addProduct(0x1A86, 0x55D4, Ch34xSerialDriver::class.java) // CH9102
        customTable.addProduct(0x1A86, 0x55D2, Ch34xSerialDriver::class.java) // CH343
        
        // FTDI variants
        customTable.addProduct(0x0403, 0x6001, FtdiSerialDriver::class.java) // FT232R
        customTable.addProduct(0x0403, 0x6006, FtdiSerialDriver::class.java) // FT232H
        customTable.addProduct(0x0403, 0x6010, FtdiSerialDriver::class.java) // FT2232H
        customTable.addProduct(0x0403, 0x6011, FtdiSerialDriver::class.java) // FT4232H
        customTable.addProduct(0x0403, 0x6014, FtdiSerialDriver::class.java) // FT232H
        customTable.addProduct(0x0403, 0x6015, FtdiSerialDriver::class.java) // FT231X
        
        // Silicon Labs CP210x
        customTable.addProduct(0x10C4, 0xEA60, Cp21xxSerialDriver::class.java) // CP2102
        customTable.addProduct(0x10C4, 0xEA70, Cp21xxSerialDriver::class.java) // CP2105
        customTable.addProduct(0x10C4, 0xEA80, Cp21xxSerialDriver::class.java) // CP2108
        
        // Prolific PL2303
        customTable.addProduct(0x067B, 0x2303, ProlificSerialDriver::class.java) // PL2303
        customTable.addProduct(0x067B, 0x23A3, ProlificSerialDriver::class.java) // PL2303GS
        customTable.addProduct(0x067B, 0x23B3, ProlificSerialDriver::class.java) // PL2303GL
        customTable.addProduct(0x067B, 0x23C3, ProlificSerialDriver::class.java) // PL2303GT
        
        // STMicroelectronics CDC
        customTable.addProduct(0x0483, 0x5740, CdcAcmSerialDriver::class.java) // STM32 VCP
        customTable.addProduct(0x0483, 0x374B, CdcAcmSerialDriver::class.java) // ST-Link
        
        // Arduino
        customTable.addProduct(0x2341, 0x0043, CdcAcmSerialDriver::class.java) // Uno
        customTable.addProduct(0x2341, 0x0042, CdcAcmSerialDriver::class.java) // Mega2560
        customTable.addProduct(0x2341, 0x0010, CdcAcmSerialDriver::class.java) // Mega ADK
        customTable.addProduct(0x2A03, 0x0043, CdcAcmSerialDriver::class.java) // Arduino.org Uno
        
        // CANable
        customTable.addProduct(0x1D50, 0x606F, CdcAcmSerialDriver::class.java) // CANable
        
        // Generic CDC (fallback)
        customTable.addProduct(0x16D0, 0x0D9E, CdcAcmSerialDriver::class.java) // MCP2515
        
        return UsbSerialProber(customTable)
    }
}

