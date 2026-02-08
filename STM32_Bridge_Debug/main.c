/* USER CODE BEGIN Header */
/**
 ******************************************************************************
 * @file           : main.c
 * @brief          : Smart CAN-to-Serial Bridge (DEBUG VERSION)
 ******************************************************************************
 * FUNCTIONALITY:
 * 1. Receives CAN SDO Commands (ID: 0x601-0x604) from Pixhawk/App
 * 2. Converts SDO Data -> 5-Byte Serial Protocol
 * 3. Transmits to Servos via UART2 (RS232)
 * 4. Receives Feedback from Servo via UART2
 * 5. Sends Feedback on CAN (ID: 0x581-0x584)
 *
 * DEBUG LED PATTERNS:
 * - Command received: LED blinks (count = servo ID)
 * - Feedback received: LED double-flash ⚡⚡
 * - UART byte received: LED quick toggle
 *
 * HARDWARE:
 * - CAN1 on PB8 (RX) / PB9 (TX) via onboard Transceiver.
 * - UART2 on PA2 (TX) / PA3 (RX) via MAX3232 to RS232 Servo.
 ******************************************************************************
 */
/* USER CODE END Header */
/* Includes ------------------------------------------------------------------*/
#include "main.h"

/* Private includes ----------------------------------------------------------*/
/* USER CODE BEGIN Includes */
#include <stdio.h>
#include <string.h>

/* USER CODE END Includes */

/* Private typedef -----------------------------------------------------------*/
/* USER CODE BEGIN PTD */

/* USER CODE END PTD */

/* Private define ------------------------------------------------------------*/
/* USER CODE BEGIN PD */
#define FEEDBACK_FRAME_LEN 7
#define FEEDBACK_RX_OFFSET 0x580
/* USER CODE END PD */

/* Private macro -------------------------------------------------------------*/
/* USER CODE BEGIN PM */

/* USER CODE END PM */

/* Private variables ---------------------------------------------------------*/
CAN_HandleTypeDef hcan1;
UART_HandleTypeDef huart2;
UART_HandleTypeDef huart3;

/* USER CODE BEGIN PV */
// ===== FEEDBACK PARSER VARIABLES =====
volatile uint8_t feedbackBuffer[FEEDBACK_FRAME_LEN];
volatile uint8_t feedbackIndex = 0;
volatile uint8_t feedbackReady = 0;
uint8_t rxByte = 0;
volatile uint8_t blinkServoId = 0;

// ===== DEBUG COUNTERS =====
volatile uint32_t uartRxCount = 0;        // Total UART bytes received
volatile uint32_t feedbackFrameCount = 0; // Complete feedback frames
volatile uint8_t feedbackDebugBlink = 0;  // Signal for feedback LED
/* USER CODE END PV */

/* Private function prototypes -----------------------------------------------*/
void SystemClock_Config(void);
static void MX_GPIO_Init(void);
static void MX_CAN1_Init(void);
static void MX_USART2_UART_Init(void);
static void MX_USART3_UART_Init(void);
/* USER CODE BEGIN PFP */
void LED_ON(void);
void LED_OFF(void);
void LED_Blink(uint32_t onTime, uint32_t offTime);
void ConvertSDOtoSerial(uint8_t *canData, uint8_t servoId);
void ProcessFeedback(void);
/* USER CODE END PFP */

/* Private user code ---------------------------------------------------------*/
/* USER CODE BEGIN 0 */

// ===== LED HELPER FUNCTIONS =====
void LED_ON(void) { HAL_GPIO_WritePin(LED_GPIO_Port, LED_Pin, GPIO_PIN_RESET); }

void LED_OFF(void) { HAL_GPIO_WritePin(LED_GPIO_Port, LED_Pin, GPIO_PIN_SET); }

void LED_Blink(uint32_t onTime, uint32_t offTime) {
  LED_ON();
  HAL_Delay(onTime);
  LED_OFF();
  HAL_Delay(offTime);
}

// ===== DEBUG: Double Flash for Feedback =====
void LED_FeedbackFlash(void) {
  LED_OFF();
  HAL_Delay(20);
  LED_ON();
  HAL_Delay(20);
  LED_OFF();
  HAL_Delay(20);
  LED_ON();
  HAL_Delay(20);
}

// ===== SMART BRIDGE LOGIC =====
// CONVERTER: CAN SDO -> Serial 5-Byte
void ConvertSDOtoSerial(uint8_t *canData, uint8_t servoId) {
  // Check for SDO Signature (0x22, 0x03, 0x60) -> Write Position
  if (canData[0] == 0x22 && canData[1] == 0x03 && canData[2] == 0x60) {

    // 1. Extract Position from CAN SDO (4 bytes little-endian, signed)
    int32_t canValue = (int32_t)canData[4] | ((int32_t)canData[5] << 8) |
                       ((int32_t)canData[6] << 16) |
                       ((int32_t)canData[7] << 24);

    // 2. Convert CAN value to Serial position
    // pos = (canValue * 4) + 8191
    int32_t position = (canValue * 4) + 8191;

    // Clamp to valid range (0-16383)
    if (position < 0)
      position = 0;
    if (position > 16383)
      position = 16383;

    // 3. Build Serial Packet (5 Bytes)
    uint8_t syncId = 0x80 | 0x08 | ((servoId >> 7) & 0x03);
    uint8_t id = servoId & 0x7F;
    uint8_t hPos = (position >> 7) & 0x7F;
    uint8_t lPos = position & 0x7F;
    uint8_t checksum = (syncId ^ id ^ hPos ^ lPos) & 0x7F;

    uint8_t packet[5] = {syncId, id, hPos, lPos, checksum};

    // 4. Send to BOTH UARTs (UART2 and UART3) to ensure delivery
    HAL_UART_Transmit(&huart2, packet, 5, 10);
    HAL_UART_Transmit(&huart3, packet, 5, 10);

    // 5. Visual Feedback: Blink count = servo ID
    blinkServoId = servoId;
  }
}

// ===== FEEDBACK PARSER (Serial RX -> CAN TX) =====
void ProcessFeedback(void) {
  // 🔦 DEBUG: Signal that ProcessFeedback was called
  feedbackDebugBlink = 1;
  feedbackFrameCount++;

  uint8_t byte0 = feedbackBuffer[0];
  uint8_t byte1 = feedbackBuffer[1];
  uint8_t byte2 = feedbackBuffer[2];
  uint8_t byte3 = feedbackBuffer[3];

  // Extract Servo ID: use UPPER 4 bits of byte1 (e.g. 0x47 -> 4)
  uint8_t servoId = (byte1 >> 4) & 0x0F;

  // Validate servo ID (must be 1-4)
  if (servoId < 1 || servoId > 4) {
    servoId = 1; // Default to servo 1 if invalid
  }

  // Extract Position: 14-bit value from byte2 and byte3
  uint16_t rawPosition = ((byte2 & 0x7F) << 7) | (byte3 & 0x7F);

  CAN_TxHeaderTypeDef TxHeader;
  uint8_t TxData[8] = {0};
  uint32_t TxMailbox;

  TxHeader.StdId = FEEDBACK_RX_OFFSET + servoId;
  TxHeader.ExtId = 0;
  TxHeader.IDE = CAN_ID_STD;
  TxHeader.RTR = CAN_RTR_DATA;
  TxHeader.DLC = 8;

  // Send position and raw bytes for debugging
  TxData[0] = rawPosition & 0xFF;
  TxData[1] = (rawPosition >> 8) & 0xFF;
  TxData[2] = byte0;                            // Debug: original byte0
  TxData[3] = byte1;                            // Debug: original byte1
  TxData[4] = byte2;                            // Debug: original byte2
  TxData[5] = byte3;                            // Debug: original byte3
  TxData[6] = (feedbackFrameCount >> 0) & 0xFF; // Debug: frame counter
  TxData[7] = (feedbackFrameCount >> 8) & 0xFF;

  HAL_CAN_AddTxMessage(&hcan1, &TxHeader, TxData, &TxMailbox);
}

// ===== CAN RX CALLBACK =====
void HAL_CAN_RxFifo0MsgPendingCallback(CAN_HandleTypeDef *hcan) {
  CAN_RxHeaderTypeDef RxHeader;
  uint8_t RxData[8];

  if (HAL_CAN_GetRxMessage(hcan, CAN_RX_FIFO0, &RxHeader, RxData) == HAL_OK) {
    // SDO Command (ID 0x601 - 0x604)
    if (RxHeader.StdId >= 0x601 && RxHeader.StdId <= 0x604 &&
        RxHeader.DLC == 8) {
      uint8_t servoId = RxHeader.StdId - 0x600;
      ConvertSDOtoSerial(RxData, servoId);
    }
  }
}

// ===== UART RX CALLBACK =====
void HAL_UART_RxCpltCallback(UART_HandleTypeDef *huart) {
  if (huart->Instance == USART2) {
    uartRxCount++; // Debug counter

    // Quick LED toggle to show UART activity
    HAL_GPIO_TogglePin(LED_GPIO_Port, LED_Pin);

    if ((rxByte & 0x80) == 0x80 && feedbackIndex == 0) {
      // Start of new frame (bit 7 set)
      feedbackBuffer[feedbackIndex++] = rxByte;
    } else if (feedbackIndex > 0 && feedbackIndex < FEEDBACK_FRAME_LEN) {
      // Continue collecting frame
      feedbackBuffer[feedbackIndex++] = rxByte;
      if (feedbackIndex >= FEEDBACK_FRAME_LEN) {
        feedbackReady = 1;
        feedbackIndex = 0;
      }
    }
    // Always re-enable UART receive
    HAL_UART_Receive_IT(&huart2, &rxByte, 1);
  }
}

/* USER CODE END 0 */

/**
 * @brief  The application entry point.
 * @retval int
 */
int main(void) {
  /* USER CODE BEGIN 1 */

  /* USER CODE END 1 */

  /* MCU Configuration--------------------------------------------------------*/

  /* Reset of all peripherals, Initializes the Flash interface and the Systick.
   */
  HAL_Init();

  /* USER CODE BEGIN Init */
  // Early LED init for alive blink
  __HAL_RCC_GPIOB_CLK_ENABLE();
  GPIO_InitTypeDef GPIO_InitStruct = {0};
  GPIO_InitStruct.Pin = GPIO_PIN_6;
  GPIO_InitStruct.Mode = GPIO_MODE_OUTPUT_PP;
  GPIO_InitStruct.Pull = GPIO_NOPULL;
  GPIO_InitStruct.Speed = GPIO_SPEED_FREQ_LOW;
  HAL_GPIO_Init(GPIOB, &GPIO_InitStruct);
  LED_Blink(200, 200);
  /* USER CODE END Init */

  /* Configure the system clock */
  SystemClock_Config();

  /* USER CODE BEGIN SysInit */

  /* USER CODE END SysInit */

  /* Initialize all configured peripherals */
  MX_GPIO_Init();
  MX_CAN1_Init();
  MX_USART2_UART_Init();
  MX_USART3_UART_Init();

  /* USER CODE BEGIN 2 */
  LED_Blink(200, 200);

  // CAN Filter - Allow Everything
  CAN_FilterTypeDef canfilterconfig = {0};
  canfilterconfig.FilterActivation = CAN_FILTER_ENABLE;
  canfilterconfig.FilterBank = 0;
  canfilterconfig.FilterFIFOAssignment = CAN_RX_FIFO0;
  canfilterconfig.FilterIdHigh = 0x0000;
  canfilterconfig.FilterIdLow = 0x0000;
  canfilterconfig.FilterMaskIdHigh = 0x0000;
  canfilterconfig.FilterMaskIdLow = 0x0000;
  canfilterconfig.FilterMode = CAN_FILTERMODE_IDMASK;
  canfilterconfig.FilterScale = CAN_FILTERSCALE_32BIT;

  if (HAL_CAN_ConfigFilter(&hcan1, &canfilterconfig) != HAL_OK) {
    while (1) {
      LED_Blink(50, 50);
    }
  }

  if (HAL_CAN_Start(&hcan1) != HAL_OK) {
    while (1) {
      LED_Blink(200, 200);
    }
  }

  HAL_CAN_ActivateNotification(&hcan1, CAN_IT_RX_FIFO0_MSG_PENDING);

  // READY SIGNAL: 5 Quick Blinks
  for (int i = 0; i < 5; i++) {
    LED_Blink(100, 100);
  }

  // Start UART2 RX Interrupt for Feedback
  HAL_UART_Receive_IT(&huart2, &rxByte, 1);

  // LED Steady ON = System Ready
  LED_ON();
  /* USER CODE END 2 */

  /* Infinite loop */
  /* USER CODE BEGIN WHILE */
  while (1) {
    // Handle Command LED Blink Pattern (blink count = servo ID)
    if (blinkServoId > 0) {
      for (int i = 0; i < blinkServoId; i++) {
        LED_OFF();
        HAL_Delay(30);
        LED_ON();
        HAL_Delay(30);
      }
      HAL_Delay(200);
      blinkServoId = 0;
    }

    // 🔦 DEBUG: Double-flash when feedback is processed
    if (feedbackDebugBlink > 0) {
      LED_FeedbackFlash();
      feedbackDebugBlink = 0;
    }

    // Process Servo Feedback
    if (feedbackReady) {
      ProcessFeedback();
      feedbackReady = 0;
    }

    // Check for CAN Errors
    uint32_t canError = HAL_CAN_GetError(&hcan1);
    if (canError != HAL_CAN_ERROR_NONE) {
      for (int i = 0; i < 10; i++) {
        LED_Blink(30, 30);
      }
      HAL_CAN_ResetError(&hcan1);
    }

    LED_ON();
    HAL_Delay(10);
    /* USER CODE END WHILE */

    /* USER CODE BEGIN 3 */
  }
  /* USER CODE END 3 */
}

/**
 * @brief System Clock Configuration - 80MHz using HSI + PLL
 * @retval None
 */
void SystemClock_Config(void) {
  RCC_OscInitTypeDef RCC_OscInitStruct = {0};
  RCC_ClkInitTypeDef RCC_ClkInitStruct = {0};

  if (HAL_PWREx_ControlVoltageScaling(PWR_REGULATOR_VOLTAGE_SCALE1) != HAL_OK) {
    Error_Handler();
  }

  RCC_OscInitStruct.OscillatorType = RCC_OSCILLATORTYPE_HSI;
  RCC_OscInitStruct.HSIState = RCC_HSI_ON;
  RCC_OscInitStruct.HSICalibrationValue = RCC_HSICALIBRATION_DEFAULT;
  RCC_OscInitStruct.PLL.PLLState = RCC_PLL_ON;
  RCC_OscInitStruct.PLL.PLLSource = RCC_PLLSOURCE_HSI;
  RCC_OscInitStruct.PLL.PLLM = 2;
  RCC_OscInitStruct.PLL.PLLN = 20;
  RCC_OscInitStruct.PLL.PLLP = RCC_PLLP_DIV7;
  RCC_OscInitStruct.PLL.PLLQ = RCC_PLLQ_DIV2;
  RCC_OscInitStruct.PLL.PLLR = RCC_PLLR_DIV2;

  if (HAL_RCC_OscConfig(&RCC_OscInitStruct) != HAL_OK) {
    Error_Handler();
  }

  RCC_ClkInitStruct.ClockType = RCC_CLOCKTYPE_HCLK | RCC_CLOCKTYPE_SYSCLK |
                                RCC_CLOCKTYPE_PCLK1 | RCC_CLOCKTYPE_PCLK2;
  RCC_ClkInitStruct.SYSCLKSource = RCC_SYSCLKSOURCE_PLLCLK;
  RCC_ClkInitStruct.AHBCLKDivider = RCC_SYSCLK_DIV1;
  RCC_ClkInitStruct.APB1CLKDivider = RCC_HCLK_DIV1;
  RCC_ClkInitStruct.APB2CLKDivider = RCC_HCLK_DIV1;

  if (HAL_RCC_ClockConfig(&RCC_ClkInitStruct, FLASH_LATENCY_4) != HAL_OK) {
    Error_Handler();
  }
}

/**
 * @brief CAN1 Initialization Function - 500kbps @ 80MHz
 * @param None
 * @retval None
 */
static void MX_CAN1_Init(void) {
  hcan1.Instance = CAN1;
  hcan1.Init.Prescaler = 10;
  hcan1.Init.Mode = CAN_MODE_NORMAL;
  hcan1.Init.SyncJumpWidth = CAN_SJW_1TQ;
  hcan1.Init.TimeSeg1 = CAN_BS1_13TQ;
  hcan1.Init.TimeSeg2 = CAN_BS2_2TQ;
  hcan1.Init.TimeTriggeredMode = DISABLE;
  hcan1.Init.AutoBusOff = DISABLE;
  hcan1.Init.AutoWakeUp = DISABLE;
  hcan1.Init.AutoRetransmission = DISABLE;
  hcan1.Init.ReceiveFifoLocked = DISABLE;
  hcan1.Init.TransmitFifoPriority = DISABLE;
  if (HAL_CAN_Init(&hcan1) != HAL_OK) {
    Error_Handler();
  }
}

/**
 * @brief USART2 Initialization Function - RS232 @ 115200
 * @param None
 * @retval None
 */
static void MX_USART2_UART_Init(void) {
  huart2.Instance = USART2;
  huart2.Init.BaudRate = 115200;
  huart2.Init.WordLength = UART_WORDLENGTH_8B;
  huart2.Init.StopBits = UART_STOPBITS_1;
  huart2.Init.Parity = UART_PARITY_NONE;
  huart2.Init.Mode = UART_MODE_TX_RX;
  huart2.Init.HwFlowCtl = UART_HWCONTROL_NONE;
  huart2.Init.OverSampling = UART_OVERSAMPLING_16;
  huart2.Init.OneBitSampling = UART_ONE_BIT_SAMPLE_DISABLE;
  huart2.AdvancedInit.AdvFeatureInit = UART_ADVFEATURE_NO_INIT;
  if (HAL_UART_Init(&huart2) != HAL_OK) {
    Error_Handler();
  }
}

/**
 * @brief USART3 Initialization Function - Debug @ 115200
 * @param None
 * @retval None
 */
static void MX_USART3_UART_Init(void) {
  huart3.Instance = USART3;
  huart3.Init.BaudRate = 115200;
  huart3.Init.WordLength = UART_WORDLENGTH_8B;
  huart3.Init.StopBits = UART_STOPBITS_1;
  huart3.Init.Parity = UART_PARITY_NONE;
  huart3.Init.Mode = UART_MODE_TX_RX;
  huart3.Init.HwFlowCtl = UART_HWCONTROL_NONE;
  huart3.Init.OverSampling = UART_OVERSAMPLING_16;
  huart3.Init.OneBitSampling = UART_ONE_BIT_SAMPLE_DISABLE;
  huart3.AdvancedInit.AdvFeatureInit = UART_ADVFEATURE_NO_INIT;
  if (HAL_UART_Init(&huart3) != HAL_OK) {
    Error_Handler();
  }
}

/**
 * @brief GPIO Initialization Function
 * @param None
 * @retval None
 */
static void MX_GPIO_Init(void) {
  GPIO_InitTypeDef GPIO_InitStruct = {0};
  /* USER CODE BEGIN MX_GPIO_Init_1 */
  /* USER CODE END MX_GPIO_Init_1 */

  __HAL_RCC_GPIOH_CLK_ENABLE();
  __HAL_RCC_GPIOA_CLK_ENABLE();
  __HAL_RCC_GPIOB_CLK_ENABLE();

  HAL_GPIO_WritePin(LED_GPIO_Port, LED_Pin, GPIO_PIN_RESET);

  GPIO_InitStruct.Pin = LED_Pin;
  GPIO_InitStruct.Mode = GPIO_MODE_OUTPUT_PP;
  GPIO_InitStruct.Pull = GPIO_NOPULL;
  GPIO_InitStruct.Speed = GPIO_SPEED_FREQ_LOW;
  HAL_GPIO_Init(LED_GPIO_Port, &GPIO_InitStruct);

  /* USER CODE BEGIN MX_GPIO_Init_2 */
  /* USER CODE END MX_GPIO_Init_2 */
}

/* USER CODE BEGIN 4 */

/* USER CODE END 4 */

/**
 * @brief  This function is executed in case of error occurrence.
 * @retval None
 */
void Error_Handler(void) {
  /* USER CODE BEGIN Error_Handler_Debug */
  __disable_irq();
  while (1) {
    GPIOB->ODR ^= GPIO_PIN_6;
    for (volatile int i = 0; i < 200000; i++)
      ;
  }
  /* USER CODE END Error_Handler_Debug */
}

#ifdef USE_FULL_ASSERT
void assert_failed(uint8_t *file, uint32_t line) {}
#endif /* USE_FULL_ASSERT */
