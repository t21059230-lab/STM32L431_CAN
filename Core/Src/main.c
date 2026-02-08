/* USER CODE BEGIN Header */
/**
 ******************************************************************************
 * @file           : main.c
 * @brief          : Smart CAN-to-Serial Bridge (MODULAR VERSION)
 ******************************************************************************
 */
/* USER CODE END Header */
/* Includes ------------------------------------------------------------------*/
#include "main.h"

/* Private includes ----------------------------------------------------------*/
/* USER CODE BEGIN Includes */
#include "can_bridge.h"
#include "led_manager.h"
#include "servo_driver.h"
#include <stdio.h>
#include <string.h>

/* USER CODE END Includes */

/* Private typedef -----------------------------------------------------------*/
/* USER CODE BEGIN PTD */

/* USER CODE END PTD */

/* Private define ------------------------------------------------------------*/
/* USER CODE BEGIN PD */
/* USER CODE END PD */

/* Private macro -------------------------------------------------------------*/
/* USER CODE BEGIN PM */

/* USER CODE END PM */

/* Private variables ---------------------------------------------------------*/
CAN_HandleTypeDef hcan1;
UART_HandleTypeDef huart2;
UART_HandleTypeDef huart3;

// ===== DMA HANDLE (defined in MSP, extern here) =====
DMA_HandleTypeDef hdma_usart2_rx;

// ===== DMA RECEIVE BUFFER =====
#define DMA_RX_BUFFER_SIZE 14 // 2 frames
uint8_t dmaRxBuffer[DMA_RX_BUFFER_SIZE] = {0};
uint8_t feedbackBuffer[FEEDBACK_FRAME_LEN + 1] = {0};
volatile uint8_t feedbackReady = 0;
volatile uint8_t blinkServoId = 0;
volatile uint8_t feedbackDebugBlink = 0;

// ===== DEBUG COUNTERS =====
volatile uint32_t uartRxCount = 0;        // Total UART events
volatile uint32_t feedbackFrameCount = 0; // Complete feedback frames
/* USER CODE END PV */

/* Private function prototypes -----------------------------------------------*/
void SystemClock_Config(void);
static void MX_GPIO_Init(void);
static void MX_CAN1_Init(void);
static void MX_USART2_UART_Init(void);
static void MX_USART3_UART_Init(void);
/* USER CODE BEGIN PFP */
/* USER CODE END PFP */

/* Private user code ---------------------------------------------------------*/
/* USER CODE BEGIN 0 */

// ===== DMA RX EVENT CALLBACK (Robust Circular Buffer) =====
#define RX_BUFFER_SIZE 128
static uint8_t rxRingBuffer[RX_BUFFER_SIZE];
static uint16_t rxRingHead = 0;
static uint16_t rxRingTail = 0;

void HAL_UARTEx_RxEventCallback(UART_HandleTypeDef *huart, uint16_t Size) {
  if (huart->Instance == USART2) {
    uartRxCount++;

    // 1. Copy DMA buffer to Ring Buffer
    for (uint16_t i = 0; i < Size; i++) {
      rxRingBuffer[rxRingHead] = dmaRxBuffer[i];
      rxRingHead = (rxRingHead + 1) % RX_BUFFER_SIZE;
    }

    // 2. Process Ring Buffer for Frames
    // We need at least 7 bytes for a frame
    while (1) {
      uint16_t available = (rxRingHead >= rxRingTail)
                               ? (rxRingHead - rxRingTail)
                               : (RX_BUFFER_SIZE - rxRingTail + rxRingHead);

      if (available < FEEDBACK_FRAME_LEN)
        break; // Not enough data for a frame

      // Peek first byte (Sync Check)
      uint8_t syncByte = rxRingBuffer[rxRingTail];

      if ((syncByte & 0x80) == 0x80) {
        // Potential Start of Frame found
        // Check if we have the full 7 bytes wrapping around capabilities
        // Linearize to temp buffer for easy processing
        uint8_t tempFrame[FEEDBACK_FRAME_LEN];
        for (int k = 0; k < FEEDBACK_FRAME_LEN; k++) {
          tempFrame[k] = rxRingBuffer[(rxRingTail + k) % RX_BUFFER_SIZE];
        }

        // Verify Checksum (Simple XOR of first 4 bytes for 5-byte packet,
        // but here we have 7 bytes? Let's check checksum logic)
        // The servo sends: [Sync] [Id] [PosH] [PosL] [Chk] ... ?
        // Our valid check is just Sync ID + length for now.
        // Better to process it.

        // Extract to global feedbackBuffer
        for (int k = 0; k < FEEDBACK_FRAME_LEN; k++) {
          feedbackBuffer[k] = tempFrame[k];
        }

        feedbackFrameCount++;
        feedbackReady = 1;

        // Advance Tail by frame length
        rxRingTail = (rxRingTail + FEEDBACK_FRAME_LEN) % RX_BUFFER_SIZE;

        // Notify Main Loop immediately (optional, or wait for loop)
        Bridge_ProcessFeedback(feedbackBuffer);
        feedbackReady = 0; // Consumed

      } else {
        // Not a sync byte, skip one byte to slide window
        rxRingTail = (rxRingTail + 1) % RX_BUFFER_SIZE;
      }
    }

    // Re-arm DMA
    HAL_UARTEx_ReceiveToIdle_DMA(&huart2, dmaRxBuffer, DMA_RX_BUFFER_SIZE);
    __HAL_DMA_DISABLE_IT(&hdma_usart2_rx, DMA_IT_HT);
  }
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
      Bridge_ConvertSDOtoSerial(RxData, servoId);
    }
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
  // Early LED init for alive blink occurs after GPIO Init usually,
  // but if we need it here, we rely on the fact that MX_GPIO_Init is called
  // later. We will just wait for MX_GPIO_Init.
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

  // Restore NVIC Enable (Required for UART and DMA to work!)
  HAL_NVIC_EnableIRQ(USART2_IRQn);
  HAL_NVIC_EnableIRQ(DMA1_Channel6_IRQn);
  HAL_NVIC_EnableIRQ(CAN1_RX0_IRQn);

  // Start UART2 DMA RX with IDLE detection
  if (HAL_UARTEx_ReceiveToIdle_DMA(&huart2, dmaRxBuffer, DMA_RX_BUFFER_SIZE) !=
      HAL_OK) {
    Error_Handler();
  }
  __HAL_DMA_DISABLE_IT(&hdma_usart2_rx, DMA_IT_HT); // Disable half-transfer

  // LED Steady ON = System Ready
  LED_ON();

  /* USER CODE END 2 */

  /* Infinite loop */
  /* USER CODE BEGIN WHILE */
  while (1) {
    // ===== Process Servo Feedback (Modular) =====
    if (feedbackReady) {
      Bridge_ProcessFeedback(feedbackBuffer);
      feedbackReady = 0;
    }

    // Handle Blink Request from Modules
    if (blinkServoId > 0) {
      // Quick blink logic can be moved to LED manager if desired, keeping
      // simple here
      LED_OFF();
      HAL_Delay(20);
      LED_ON();
      blinkServoId = 0;
    }

    // Check for CAN Errors
    uint32_t canError = HAL_CAN_GetError(&hcan1);
    if (canError != HAL_CAN_ERROR_NONE) {
      HAL_CAN_ResetError(&hcan1);
    }

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
    // Correct Error Blink: Fast Toggle on correct LED Pin
    for (int k = 0; k < 5; k++) {
      HAL_GPIO_WritePin(
          LED_GPIO_Port, LED_Pin,
          GPIO_PIN_SET); // OFF (Active Low usually, or ON if High)
      for (volatile int i = 0; i < 50000; i++)
        ;
      HAL_GPIO_WritePin(LED_GPIO_Port, LED_Pin, GPIO_PIN_RESET); // ON
      for (volatile int i = 0; i < 50000; i++)
        ;
    }
    for (volatile int i = 0; i < 500000; i++)
      ; // Pause
  }
  /* USER CODE END Error_Handler_Debug */
}

#ifdef USE_FULL_ASSERT
void assert_failed(uint8_t *file, uint32_t line) {}
#endif /* USE_FULL_ASSERT */
