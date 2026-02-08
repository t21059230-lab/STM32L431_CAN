#include "servo_driver.h"

/**
 * @brief  Builds a 5-byte RS232 packet for the servo.
 */
void Servo_BuildPacket(uint8_t servoId, int32_t position, uint8_t *packetOut) {
  // Clamp to valid range
  if (position < 0)
    position = 0;
  if (position > SERVO_MAX_POS)
    position = SERVO_MAX_POS;

  // Calculate Bytes
  uint8_t syncId = 0x80 | 0x08 | ((servoId >> 7) & 0x03);
  uint8_t id = servoId & 0x7F;
  uint8_t hPos = (position >> 7) & 0x7F;
  uint8_t lPos = position & 0x7F;

  // Calculate Checksum: XOR of first 4 bytes
  uint8_t checksum = (syncId ^ id ^ hPos ^ lPos) & 0x7F;

  // Fill Buffer
  packetOut[0] = syncId;
  packetOut[1] = id;
  packetOut[2] = hPos;
  packetOut[3] = lPos;
  packetOut[4] = checksum;
}

/**
 * @brief  Parses raw feedback bytes to extract position.
 *         Logic: 7-bit encoding combination.
 */
uint16_t Servo_ExtractPosition(uint8_t byte2, uint8_t byte3) {
  // Combine 7-bit parts: (Byte2 << 7) | Byte3
  return ((byte2 & 0x7F) << 7) | (byte3 & 0x7F);
}
