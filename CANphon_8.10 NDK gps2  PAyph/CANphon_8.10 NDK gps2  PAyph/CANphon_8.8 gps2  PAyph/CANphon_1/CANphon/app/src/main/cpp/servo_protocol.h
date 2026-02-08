/**
 * servo_protocol.h
 * High-Performance Servo Protocol Formatter (C++)
 * 
 * Binary Frame Format: 5 bytes
 * [Syncid] [Id] [Hpos] [Lpos] [Checksum]
 * 
 * Compatible with STM32 CTRL.cpp
 */

#ifndef SERVO_PROTOCOL_H
#define SERVO_PROTOCOL_H

#include <cstdint>

#ifdef __cplusplus
extern "C" {
#endif

// Protocol constants
#define SERVO_FRAME_SIZE 5
#define SERVO_SYNC_BASE 0x80
#define SERVO_OPCODE_POSITION 0x08
#define SERVO_OPCODE_READ 0x00

// Position constants
#define POSITION_CENTER 8191
#define POSITION_MIN 0
#define POSITION_MAX 16383
#define UNITS_PER_DEGREE 40.0f

// ═══════════════════════════════════════════════════════════════════════════
// Command Formatting
// ═══════════════════════════════════════════════════════════════════════════

// Format position command: angle in degrees → 5-byte frame
// Returns frame size (5)
int servoFormatCommand(int servoId, float angleDegrees, uint8_t* outBuffer);

// Format position command with raw position value (0-16383)
int servoFormatPositionCommand(int servoId, int position, uint8_t* outBuffer);

// Format feedback request command
int servoFormatFeedbackRequest(int servoId, uint8_t* outBuffer);

// ═══════════════════════════════════════════════════════════════════════════
// Feedback Parsing
// ═══════════════════════════════════════════════════════════════════════════

// Feedback data structure
typedef struct {
    int servoId;
    int position;       // Raw position (0-16383)
    float angleDegrees; // Converted angle (-25 to +25)
    int valid;          // 1 if valid, 0 if invalid
} ServoFeedback;

// Parse feedback response (7 bytes expected)
// Returns 1 if valid, 0 if invalid
int servoParseFeedback(const uint8_t* data, int length, ServoFeedback* outFeedback);

// ═══════════════════════════════════════════════════════════════════════════
// Utility
// ═══════════════════════════════════════════════════════════════════════════

// Convert angle to position
int servoAngleToPosition(float angleDegrees);

// Convert position to angle
float servoPositionToAngle(int position);

#ifdef __cplusplus
}
#endif

#endif // SERVO_PROTOCOL_H
