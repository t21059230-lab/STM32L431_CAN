#include "can_bridge.h"
#include "led_manager.h" // For LED effects
#include "servo_driver.h"
#include <stdio.h>

/**
 * @brief  Converts incoming CAN SDO (ID 0x601-0x604) to Serial Servo Protocol.
 */
void Bridge_ConvertSDOtoSerial(uint8_t *canData, uint8_t servoId) {
  if (canData[0] == 0x22 && canData[1] == 0x03 && canData[2] == 0x60) {
    int32_t canValue = (int32_t)canData[4] | ((int32_t)canData[5] << 8) |
                       ((int32_t)canData[6] << 16) |
                       ((int32_t)canData[7] << 24);

    int32_t position = (canValue * 4) + SERVO_CENTER_POS;

    uint8_t packet[5];
    Servo_BuildPacket(servoId, position, packet);

    HAL_UART_Transmit(&huart2, packet, 5, 10);

    blinkServoId = servoId;
  }
}

/**
 * @brief  Processes received Serial feedback and forwards it to CAN.
 */
void Bridge_ProcessFeedback(uint8_t *buffer) {
  feedbackDebugBlink = 1;

  uint8_t byte1 = buffer[1];
  uint8_t byte2 = buffer[2];
  uint8_t byte3 = buffer[3];

  uint8_t servoId = byte1 & 0x0F;
  if (servoId < 1 || servoId > 4)
    servoId = 1;

  uint16_t rawPosition = Servo_ExtractPosition(byte2, byte3);

  if (HAL_CAN_GetTxMailboxesFreeLevel(&hcan1) == 0)
    return;

  CAN_TxHeaderTypeDef TxHeader;
  uint8_t TxData[8] = {0};
  uint32_t TxMailbox;

  TxHeader.StdId = FEEDBACK_RX_OFFSET + servoId;
  TxHeader.IDE = CAN_ID_STD;
  TxHeader.RTR = CAN_RTR_DATA;
  TxHeader.DLC = 8;

  TxData[0] = rawPosition & 0xFF;
  TxData[1] = (rawPosition >> 8) & 0xFF;

  HAL_CAN_AddTxMessage(&hcan1, &TxHeader, TxData, &TxMailbox);
}
