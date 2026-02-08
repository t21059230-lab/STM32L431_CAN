#include "led_manager.h"

// Note: These must be defined in main.h or here if not global
// Assuming LED_GPIO_Port and LED_Pin are available via main.h

void LED_ON(void) { HAL_GPIO_WritePin(LED_GPIO_Port, LED_Pin, GPIO_PIN_RESET); }

void LED_OFF(void) { HAL_GPIO_WritePin(LED_GPIO_Port, LED_Pin, GPIO_PIN_SET); }

void LED_Toggle(void) { HAL_GPIO_TogglePin(LED_GPIO_Port, LED_Pin); }

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

void LED_SignalError(void) {
  for (int i = 0; i < 10; i++) {
    LED_Blink(30, 30);
  }
}
