/**
 * servo_protocol.cpp
 * High-Performance Servo Protocol Formatter (C++)
 * 
 * Converted from UnifiedProtocol.kt
 * Compatible with STM32 CTRL.cpp
 * 
 * Protocol: Binary Frame (5 bytes)
 * [Syncid] [Id] [Hpos] [Lpos] [Checksum]
 */

#include "servo_protocol.h"
#include <android/log.h>

#define LOG_TAG "NativeServoProtocol"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// ═══════════════════════════════════════════════════════════════════════════
// Utility Functions
// ═══════════════════════════════════════════════════════════════════════════

static inline float clampf(float val, float minVal, float maxVal) {
    if (val < minVal) return minVal;
    if (val > maxVal) return maxVal;
    return val;
}

static inline int clampi(int val, int minVal, int maxVal) {
    if (val < minVal) return minVal;
    if (val > maxVal) return maxVal;
    return val;
}

extern "C" int servoAngleToPosition(float angleDegrees) {
    float clampedAngle = clampf(angleDegrees, -25.0f, 25.0f);
    int position = (int)(clampedAngle * UNITS_PER_DEGREE + POSITION_CENTER);
    return clampi(position, POSITION_MIN, POSITION_MAX);
}

extern "C" float servoPositionToAngle(int position) {
    int clampedPos = clampi(position, POSITION_MIN, POSITION_MAX);
    return (float)(clampedPos - POSITION_CENTER) / UNITS_PER_DEGREE;
}

// ═══════════════════════════════════════════════════════════════════════════
// Command Formatting
// ═══════════════════════════════════════════════════════════════════════════

extern "C" int servoFormatCommand(int servoId, float angleDegrees, uint8_t* outBuffer) {
    int position = servoAngleToPosition(angleDegrees);
    return servoFormatPositionCommand(servoId, position, outBuffer);
}

extern "C" int servoFormatPositionCommand(int servoId, int position, uint8_t* outBuffer) {
    int clampedPosition = clampi(position, POSITION_MIN, POSITION_MAX);
    
    // [Syncid] = 0x80 | 0x08 | ((servoId >> 7) & 0x03)
    uint8_t syncId = (uint8_t)(SERVO_SYNC_BASE | SERVO_OPCODE_POSITION | ((servoId >> 7) & 0x03));
    
    // [Id] = servoId & 0x7F
    uint8_t id = (uint8_t)(servoId & 0x7F);
    
    // [Hpos] = (position >> 7) & 0x7F
    uint8_t hPos = (uint8_t)((clampedPosition >> 7) & 0x7F);
    
    // [Lpos] = position & 0x7F
    uint8_t lPos = (uint8_t)(clampedPosition & 0x7F);
    
    // [Checksum] = (Syncid ^ Id ^ Hpos ^ Lpos) & 0x7F
    uint8_t checksum = (uint8_t)((syncId ^ id ^ hPos ^ lPos) & 0x7F);
    
    outBuffer[0] = syncId;
    outBuffer[1] = id;
    outBuffer[2] = hPos;
    outBuffer[3] = lPos;
    outBuffer[4] = checksum;
    
    return SERVO_FRAME_SIZE;
}

extern "C" int servoFormatFeedbackRequest(int servoId, uint8_t* outBuffer) {
    // OpCode = 0x00 for read request (no position change)
    uint8_t syncId = (uint8_t)(SERVO_SYNC_BASE | SERVO_OPCODE_READ | ((servoId >> 7) & 0x03));
    uint8_t id = (uint8_t)(servoId & 0x7F);
    uint8_t hPos = 0x00;
    uint8_t lPos = 0x00;
    uint8_t checksum = (uint8_t)((syncId ^ id ^ hPos ^ lPos) & 0x7F);
    
    outBuffer[0] = syncId;
    outBuffer[1] = id;
    outBuffer[2] = hPos;
    outBuffer[3] = lPos;
    outBuffer[4] = checksum;
    
    return SERVO_FRAME_SIZE;
}

// ═══════════════════════════════════════════════════════════════════════════
// Feedback Parsing
// ═══════════════════════════════════════════════════════════════════════════

extern "C" int servoParseFeedback(const uint8_t* data, int length, ServoFeedback* outFeedback) {
    // Initialize as invalid
    outFeedback->valid = 0;
    outFeedback->servoId = -1;
    outFeedback->position = 0;
    outFeedback->angleDegrees = 0.0f;
    
    // Feedback frame should be 7 bytes
    // [Header] [SyncId] [Id] [Hpos] [Lpos] [Checksum] [?]
    // Or simplified 5 bytes matching our format
    
    if (length < 5) {
        return 0;
    }
    
    // Find sync byte (starts with 0x8x)
    int startIdx = -1;
    for (int i = 0; i <= length - 5; i++) {
        if ((data[i] & 0x80) != 0) {
            startIdx = i;
            break;
        }
    }
    
    if (startIdx < 0) {
        return 0;
    }
    
    uint8_t syncId = data[startIdx + 0];
    uint8_t id = data[startIdx + 1];
    uint8_t hPos = data[startIdx + 2];
    uint8_t lPos = data[startIdx + 3];
    uint8_t checksum = data[startIdx + 4];
    
    // Verify checksum
    uint8_t expectedChecksum = (uint8_t)((syncId ^ id ^ hPos ^ lPos) & 0x7F);
    if (checksum != expectedChecksum) {
        LOGD("Checksum mismatch: expected %02X, got %02X", expectedChecksum, checksum);
        return 0;
    }
    
    // Extract servo ID (including high bits from sync)
    int servoIdHigh = (syncId & 0x03) << 7;
    int servoIdLow = id & 0x7F;
    outFeedback->servoId = servoIdHigh | servoIdLow;
    
    // Extract position
    int positionHigh = (hPos & 0x7F) << 7;
    int positionLow = lPos & 0x7F;
    outFeedback->position = positionHigh | positionLow;
    
    // Convert to angle
    outFeedback->angleDegrees = servoPositionToAngle(outFeedback->position);
    outFeedback->valid = 1;
    
    return 1;
}
