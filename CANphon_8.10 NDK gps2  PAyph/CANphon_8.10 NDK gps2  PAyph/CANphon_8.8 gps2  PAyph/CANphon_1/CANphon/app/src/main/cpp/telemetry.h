/**
 * telemetry.h
 * High-Performance Telemetry Frame Builder (C++)
 * 
 * Protocol: 73-byte binary frame @ 60Hz
 */

#ifndef TELEMETRY_H
#define TELEMETRY_H

#include <cstdint>

#ifdef __cplusplus
extern "C" {
#endif

// Frame constants
#define TELEMETRY_FRAME_SIZE 73
#define TELEMETRY_HEADER_1 0xAA
#define TELEMETRY_HEADER_2 0x55

// ═══════════════════════════════════════════════════════════════════════════
// Telemetry Data Structure
// ═══════════════════════════════════════════════════════════════════════════

typedef struct {
    // Timestamp
    uint32_t timestamp;
    
    // Orientation (degrees × 10)
    int16_t roll;
    int16_t pitch;
    int16_t yaw;
    
    // Accelerometer (g × 100)
    int16_t accX;
    int16_t accY;
    int16_t accZ;
    
    // Pressure/Barometer
    uint16_t pressure;      // hPa
    int16_t baroAltitude;   // meters × 10
    
    // GPS
    int32_t latitude;       // degrees × 10^7
    int32_t longitude;      // degrees × 10^7
    int16_t gpsAltitude;    // meters
    uint16_t speed;         // cm/s
    uint16_t heading;       // degrees × 10
    uint8_t satellites;
    uint8_t gpsFix;         // 0=No, 1=2D, 2=3D
    uint16_t hdop;          // × 100
    
    // Servo Commands (degrees × 10)
    int16_t s1Cmd;
    int16_t s2Cmd;
    int16_t s3Cmd;
    int16_t s4Cmd;
    
    // Servo Feedback (degrees × 10)
    int16_t s1Fb;
    int16_t s2Fb;
    int16_t s3Fb;
    int16_t s4Fb;
    
    // Servo Status (bitmask)
    uint8_t servoOnline;
    
    // Tracking
    int16_t targetX;
    int16_t targetY;
    uint16_t targetW;
    uint16_t targetH;
    
    // Battery
    uint8_t batteryPercent;
    uint8_t isCharging;
    uint16_t batteryVoltage; // mV
    
    // Temperature (°C × 10)
    int16_t temperature;
    
} TelemetryData;

// ═══════════════════════════════════════════════════════════════════════════
// API
// ═══════════════════════════════════════════════════════════════════════════

// Initialize telemetry system
void telemetryInit();

// Update individual fields
void telemetrySetTimestamp(uint32_t ts);
void telemetrySetOrientation(float roll, float pitch, float yaw);
void telemetrySetAccelerometer(float x, float y, float z);
void telemetrySetPressure(float pressureHpa, float altitudeM);
void telemetrySetGPS(double lat, double lon, float alt, float speed, float heading,
                     int satellites, int fix, float hdop);
void telemetrySetServoCmd(float s1, float s2, float s3, float s4);
void telemetrySetServoFb(float s1, float s2, float s3, float s4);
void telemetrySetServoStatus(int online);
void telemetrySetTracking(int x, int y, int w, int h);
void telemetrySetBattery(int percent, int charging, int voltageMv);
void telemetrySetTemperature(float tempC);

// Build frame (returns frame size, writes to buffer)
int telemetryBuildFrame(uint8_t* outBuffer, int maxLen);

// Get raw telemetry data struct
void telemetryGetData(TelemetryData* outData);

#ifdef __cplusplus
}
#endif

#endif // TELEMETRY_H
