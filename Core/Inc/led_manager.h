#ifndef LED_MANAGER_H
#define LED_MANAGER_H

#include "main.h"

// ===== LED FUNCTIONS =====
void LED_ON(void);
void LED_OFF(void);
void LED_Toggle(void);
void LED_Blink(uint32_t onTime, uint32_t offTime);
void LED_FeedbackFlash(void); // The "Double Flash" pattern
void LED_SignalError(void);

#endif // LED_MANAGER_H
