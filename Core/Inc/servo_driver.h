#ifndef SERVO_DRIVER_H
#define SERVO_DRIVER_H

#include "main.h"

// ===== DEFINITIONS =====
#define FEEDBACK_FRAME_LEN 7
#define SERVO_MAX_POS 16383
#define SERVO_CENTER_POS 8191

// ===== FUNCTION PROTOTYPES =====

/**
 * @brief  Builds a 5-byte RS232 packet for the servo.
 * @param  servoId: ID of the servo (1-4)
 * @param  position: Target position (0-16383)
 * @param  packetOut: Pointer to 5-byte buffer to store the result
 */
void Servo_BuildPacket(uint8_t servoId, int32_t position, uint8_t *packetOut);

/**
 * @brief  Parses raw feedback bytes to extract position.
 * @param  byte2: High byte (7-bit)
 * @param  byte3: Low byte (7-bit)
 * @return Raw position (0-16383)
 */
uint16_t Servo_ExtractPosition(uint8_t byte2, uint8_t byte3);

#endif // SERVO_DRIVER_H
