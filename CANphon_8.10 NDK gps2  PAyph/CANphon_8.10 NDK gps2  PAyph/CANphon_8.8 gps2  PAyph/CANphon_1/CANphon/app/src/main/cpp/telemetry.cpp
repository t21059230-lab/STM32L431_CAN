/**
 * telemetry.cpp
 * High-Performance Telemetry Frame Builder (C++)
 * 
 * Protocol: 73-byte binary frame @ 60Hz
 * Converted from TelemetryStreamer.kt - frame building only
 * 
 * USB Serial handling remains in Kotlin (requires Android API)
 */

#include "telemetry.h"
#include <cstring>
#include <android/log.h>

#define LOG_TAG "NativeTelemetry"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// ═══════════════════════════════════════════════════════════════════════════
// Global Telemetry State
// ═══════════════════════════════════════════════════════════════════════════

static TelemetryData telem = {0};
static uint64_t frameCount = 0;

// ═══════════════════════════════════════════════════════════════════════════
// Initialization
// ═══════════════════════════════════════════════════════════════════════════

extern "C" void telemetryInit() {
    memset(&telem, 0, sizeof(TelemetryData));
    frameCount = 0;
    LOGI("✅ Native Telemetry initialized");
}

// ═══════════════════════════════════════════════════════════════════════════
// Data Setters
// ═══════════════════════════════════════════════════════════════════════════

extern "C" void telemetrySetTimestamp(uint32_t ts) {
    telem.timestamp = ts;
}

extern "C" void telemetrySetOrientation(float roll, float pitch, float yaw) {
    telem.roll = (int16_t)(roll * 10.0f);
    telem.pitch = (int16_t)(pitch * 10.0f);
    telem.yaw = (int16_t)(yaw * 10.0f);
}

extern "C" void telemetrySetAccelerometer(float x, float y, float z) {
    telem.accX = (int16_t)(x * 100.0f);
    telem.accY = (int16_t)(y * 100.0f);
    telem.accZ = (int16_t)(z * 100.0f);
}

extern "C" void telemetrySetPressure(float pressureHpa, float altitudeM) {
    telem.pressure = (uint16_t)pressureHpa;
    telem.baroAltitude = (int16_t)(altitudeM * 10.0f);
}

extern "C" void telemetrySetGPS(double lat, double lon, float alt, float speed, float heading,
                                 int satellites, int fix, float hdop) {
    telem.latitude = (int32_t)(lat * 10000000.0);
    telem.longitude = (int32_t)(lon * 10000000.0);
    telem.gpsAltitude = (int16_t)alt;
    telem.speed = (uint16_t)(speed * 100.0f);
    telem.heading = (uint16_t)(heading * 10.0f);
    telem.satellites = (uint8_t)satellites;
    telem.gpsFix = (uint8_t)fix;
    telem.hdop = (uint16_t)(hdop * 100.0f);
}

extern "C" void telemetrySetServoCmd(float s1, float s2, float s3, float s4) {
    telem.s1Cmd = (int16_t)(s1 * 10.0f);
    telem.s2Cmd = (int16_t)(s2 * 10.0f);
    telem.s3Cmd = (int16_t)(s3 * 10.0f);
    telem.s4Cmd = (int16_t)(s4 * 10.0f);
}

extern "C" void telemetrySetServoFb(float s1, float s2, float s3, float s4) {
    telem.s1Fb = (int16_t)(s1 * 10.0f);
    telem.s2Fb = (int16_t)(s2 * 10.0f);
    telem.s3Fb = (int16_t)(s3 * 10.0f);
    telem.s4Fb = (int16_t)(s4 * 10.0f);
}

extern "C" void telemetrySetServoStatus(int online) {
    telem.servoOnline = (uint8_t)online;
}

extern "C" void telemetrySetTracking(int x, int y, int w, int h) {
    telem.targetX = (int16_t)x;
    telem.targetY = (int16_t)y;
    telem.targetW = (uint16_t)w;
    telem.targetH = (uint16_t)h;
}

extern "C" void telemetrySetBattery(int percent, int charging, int voltageMv) {
    telem.batteryPercent = (uint8_t)percent;
    telem.isCharging = (uint8_t)charging;
    telem.batteryVoltage = (uint16_t)voltageMv;
}

extern "C" void telemetrySetTemperature(float tempC) {
    telem.temperature = (int16_t)(tempC * 10.0f);
}

// ═══════════════════════════════════════════════════════════════════════════
// Frame Building (High Performance - no allocations)
// ═══════════════════════════════════════════════════════════════════════════

// Write little-endian int16
static inline void writeLE16(uint8_t* buf, int16_t val) {
    buf[0] = val & 0xFF;
    buf[1] = (val >> 8) & 0xFF;
}

// Write little-endian uint16
static inline void writeLE16U(uint8_t* buf, uint16_t val) {
    buf[0] = val & 0xFF;
    buf[1] = (val >> 8) & 0xFF;
}

// Write little-endian int32
static inline void writeLE32(uint8_t* buf, int32_t val) {
    buf[0] = val & 0xFF;
    buf[1] = (val >> 8) & 0xFF;
    buf[2] = (val >> 16) & 0xFF;
    buf[3] = (val >> 24) & 0xFF;
}

// Write little-endian uint32
static inline void writeLE32U(uint8_t* buf, uint32_t val) {
    buf[0] = val & 0xFF;
    buf[1] = (val >> 8) & 0xFF;
    buf[2] = (val >> 16) & 0xFF;
    buf[3] = (val >> 24) & 0xFF;
}

extern "C" int telemetryBuildFrame(uint8_t* outBuffer, int maxLen) {
    if (maxLen < TELEMETRY_FRAME_SIZE) {
        return -1;
    }
    
    int idx = 0;
    
    // Header (2 bytes)
    outBuffer[idx++] = TELEMETRY_HEADER_1;
    outBuffer[idx++] = TELEMETRY_HEADER_2;
    
    // Length (1 byte) - 70 bytes after header
    outBuffer[idx++] = (uint8_t)(TELEMETRY_FRAME_SIZE - 3);
    
    // Timestamp (4 bytes)
    writeLE32U(&outBuffer[idx], telem.timestamp);
    idx += 4;
    
    // Orientation (6 bytes)
    writeLE16(&outBuffer[idx], telem.roll); idx += 2;
    writeLE16(&outBuffer[idx], telem.pitch); idx += 2;
    writeLE16(&outBuffer[idx], telem.yaw); idx += 2;
    
    // Accelerometer (6 bytes)
    writeLE16(&outBuffer[idx], telem.accX); idx += 2;
    writeLE16(&outBuffer[idx], telem.accY); idx += 2;
    writeLE16(&outBuffer[idx], telem.accZ); idx += 2;
    
    // Pressure (2 bytes)
    writeLE16U(&outBuffer[idx], telem.pressure); idx += 2;
    
    // Baro Altitude (2 bytes)
    writeLE16(&outBuffer[idx], telem.baroAltitude); idx += 2;
    
    // GPS Latitude (4 bytes)
    writeLE32(&outBuffer[idx], telem.latitude); idx += 4;
    
    // GPS Longitude (4 bytes)
    writeLE32(&outBuffer[idx], telem.longitude); idx += 4;
    
    // GPS Altitude (2 bytes)
    writeLE16(&outBuffer[idx], telem.gpsAltitude); idx += 2;
    
    // Speed (2 bytes)
    writeLE16U(&outBuffer[idx], telem.speed); idx += 2;
    
    // Heading (2 bytes)
    writeLE16U(&outBuffer[idx], telem.heading); idx += 2;
    
    // Satellites (1 byte)
    outBuffer[idx++] = telem.satellites;
    
    // GPS Fix (1 byte)
    outBuffer[idx++] = telem.gpsFix;
    
    // HDOP (2 bytes)
    writeLE16U(&outBuffer[idx], telem.hdop); idx += 2;
    
    // Servo Commands (8 bytes)
    writeLE16(&outBuffer[idx], telem.s1Cmd); idx += 2;
    writeLE16(&outBuffer[idx], telem.s2Cmd); idx += 2;
    writeLE16(&outBuffer[idx], telem.s3Cmd); idx += 2;
    writeLE16(&outBuffer[idx], telem.s4Cmd); idx += 2;
    
    // Servo Feedback (8 bytes)
    writeLE16(&outBuffer[idx], telem.s1Fb); idx += 2;
    writeLE16(&outBuffer[idx], telem.s2Fb); idx += 2;
    writeLE16(&outBuffer[idx], telem.s3Fb); idx += 2;
    writeLE16(&outBuffer[idx], telem.s4Fb); idx += 2;
    
    // Servo Status (1 byte)
    outBuffer[idx++] = telem.servoOnline;
    
    // Tracking (8 bytes)
    writeLE16(&outBuffer[idx], telem.targetX); idx += 2;
    writeLE16(&outBuffer[idx], telem.targetY); idx += 2;
    writeLE16U(&outBuffer[idx], telem.targetW); idx += 2;
    writeLE16U(&outBuffer[idx], telem.targetH); idx += 2;
    
    // Battery (4 bytes)
    outBuffer[idx++] = telem.batteryPercent;
    outBuffer[idx++] = telem.isCharging;
    writeLE16U(&outBuffer[idx], telem.batteryVoltage); idx += 2;
    
    // Temperature (2 bytes)
    writeLE16(&outBuffer[idx], telem.temperature); idx += 2;
    
    // Checksum (XOR of all bytes)
    uint8_t checksum = 0;
    for (int i = 0; i < idx; i++) {
        checksum ^= outBuffer[i];
    }
    outBuffer[idx++] = checksum;
    
    frameCount++;
    
    // Log every 60 frames
    if (frameCount % 60 == 0) {
        LOGD("📡 Frame %llu built, roll=%d, pitch=%d", 
             (unsigned long long)frameCount, telem.roll, telem.pitch);
    }
    
    return idx;
}

extern "C" void telemetryGetData(TelemetryData* outData) {
    *outData = telem;
}
