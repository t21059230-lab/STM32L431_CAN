#include "can_bridge.h"
#include "led_manager.h" // For LED effects
#include "servo_driver.h"
#include <stdio.h>

/**
 * @brief  Converts incoming CAN SDO (ID 0x601-0x604) to Serial Servo Protocol.
 */
void Bridge_ConvertSDOtoSerial(uint8_t *canData, uint8_t servoId) {
  // Check for SDO Signature (0x22, 0x03, 0x60) -> Write Position
  if (canData[0] == 0x22 && canData[1] == 0x03 && canData[2] == 0x60) {

    // 1. Extract Position from CAN SDO (4 bytes little-endian, signed)
    int32_t canValue = (int32_t)canData[4] | ((int32_t)canData[5] << 8) |
                       ((int32_t)canData[6] << 16) |
                       ((int32_t)canData[7] << 24);

    // 2. Convert CAN value to Serial position
    // Formula: pos = (canValue * 4) + 8191
    int32_t position = (canValue * 4) + SERVO_CENTER_POS;

    // 3. Build Serial Packet using Servo Driver Module
    uint8_t packet[5];
    Servo_BuildPacket(servoId, position, packet);

    // 4. Send to BOTH UARTs (UART2 and UART3) to ensure delivery
    HAL_UART_Transmit(&huart2, packet, 5, 10);
    HAL_UART_Transmit(&huart3, packet, 5, 10);

    // 5. Visual Feedback: Blink count = servo ID
    blinkServoId = servoId;
  }
}

/**
 * @brief  Processes received Serial feedback and forwards it to CAN.
 */
void Bridge_ProcessFeedback(uint8_t *buffer) {
  // 🔦 DEBUG: Signal that ProcessFeedback was called
  feedbackDebugBlink = 1;
  feedbackFrameCount++;

  uint8_t byte0 = buffer[0];
  uint8_t byte1 = buffer[1];
  uint8_t byte2 = buffer[2];
  uint8_t byte3 = buffer[3];
  uint8_t byte4 = buffer[4];
  uint8_t byte5 = buffer[5];
  uint8_t byte6 = buffer[6];

  // --- DEBUG: SEND RAW DATA ON ID 0x599 (Optional but helpful) ---
  CAN_TxHeaderTypeDef TxHeaderDbg;
  uint8_t TxDataDbg[8];
  uint32_t TxMailboxDbg;

  TxHeaderDbg.StdId = DEBUG_ID;
  TxHeaderDbg.IDE = CAN_ID_STD;
  TxHeaderDbg.RTR = CAN_RTR_DATA;
  TxHeaderDbg.DLC = 8;
  for (int i = 0; i < 7; i++)
    TxDataDbg[i] = buffer[i];
  TxDataDbg[7] = 0xAA; // Marker

  HAL_CAN_AddTxMessage(&hcan1, &TxHeaderDbg, TxDataDbg, &TxMailboxDbg);
  // ---------------------------------------------------------------

  // Extract Servo ID: use LOWER 4 bits of byte1 (since traces show 0x04)
  uint8_t servoId = byte1 & 0x0F;
  if (servoId < 1 || servoId > 4)
    servoId = 1;

  // Extract Position using Servo Driver Logic
  uint16_t rawPosition = Servo_ExtractPosition(byte2, byte3);

  // Prepare Feedback CAN Message
  CAN_TxHeaderTypeDef TxHeader;
  uint8_t TxData[8] = {0};
  uint32_t TxMailbox;

  TxHeader.StdId = FEEDBACK_RX_OFFSET + servoId;
  TxHeader.IDE = CAN_ID_STD;
  TxHeader.RTR = CAN_RTR_DATA;
  TxHeader.DLC = 8;

  // Send RAW POSITION directly (App expects 0-16383, 8191=0 deg)
  TxData[0] = rawPosition & 0xFF;
  TxData[1] = (rawPosition >> 8) & 0xFF;

  // Zero out remaining bytes
  TxData[2] = 0;
  TxData[3] = 0;
  TxData[4] = 0;
  TxData[5] = 0;
  TxData[6] = 0;
  TxData[7] = 0;

  HAL_CAN_AddTxMessage(&hcan1, &TxHeader, TxData, &TxMailbox);
}
