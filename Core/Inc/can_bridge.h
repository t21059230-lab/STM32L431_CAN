#ifndef CAN_BRIDGE_H
#define CAN_BRIDGE_H

#include "main.h"

// ===== DEFINITIONS =====
#define FEEDBACK_RX_OFFSET 0x580
#define FEEDBACK_FRAME_LEN 7
#define DEBUG_ID 0x599

// ===== GLOBAL VARIABLES (Extern) =====
extern CAN_HandleTypeDef hcan1;
extern UART_HandleTypeDef huart2;
extern UART_HandleTypeDef huart3;
extern volatile uint32_t feedbackFrameCount;
extern volatile uint8_t blinkServoId;
extern volatile uint8_t feedbackDebugBlink;

// ===== FUNCTION PROTOTYPES =====

/**
 * @brief  Converts incoming CAN SDO (ID 0x601-0x604) to Serial Servo Protocol.
 * @param  canData: Pointer to 8-byte CAN data
 * @param  servoId: ID of the servo extracted from CAN ID
 */
void Bridge_ConvertSDOtoSerial(uint8_t *canData, uint8_t servoId);

/**
 * @brief  Processes received Serial feedback and forwards it to CAN.
 * @param  buffer: Pointer to the 7-byte feedback frame buffer
 */
void Bridge_ProcessFeedback(uint8_t *buffer);

#endif // CAN_BRIDGE_H
