# 🧠 دليل تكامل التعلم المعزز (Reinforcement Learning)
# SAQR Seeker RL Integration Guide

**الإصدار**: 1.0  
**التاريخ**: 2026-01-12  
**المشروع**: SAQR Seeker - نظام التحكم بالتعلم المعزز

---

# فهرس المحتويات

1. [مقدمة وفكرة المشروع](#الفصل-1-مقدمة-وفكرة-المشروع)
2. [المدخلات (Observation Space)](#الفصل-2-المدخلات-observation-space)
3. [المخرجات (Action Space)](#الفصل-3-المخرجات-action-space)
4. [دالة المكافأة (Reward Function)](#الفصل-4-دالة-المكافأة-reward-function)
5. [بناء البيئة (Environment)](#الفصل-5-بناء-البيئة-environment)
6. [التدريب والخوارزميات](#الفصل-6-التدريب-والخوارزميات)
7. [النشر على الهاردوير](#الفصل-7-النشر-على-الهاردوير)
8. [الملاحق](#الملاحق)

---

# الفصل 1: مقدمة وفكرة المشروع

## 1.1 الفكرة العامة

الهدف هو استبدال المتحكم التقليدي **PID** بنموذج **Reinforcement Learning** ليكون "العقل المتحكم" الرئيسي في نظام SAQR Seeker.

### المقارنة

| الجانب | PID التقليدي | RL Agent |
|--------|--------------|----------|
| **التكيف** | ثابت، يحتاج ضبط يدوي | يتعلم ويتكيف تلقائياً |
| **التعقيد** | محدود بالنموذج الخطي | يتعامل مع أنظمة غير خطية |
| **الاستباقية** | تفاعلي فقط | يتنبأ ويستبق |
| **الظروف المتغيرة** | يحتاج إعادة ضبط | يتكيف تلقائياً |

## 1.2 معمارية النظام

```
┌─────────────────────────────────────────────────────────────────┐
│                    الوضع الحالي (PID Controller)                │
└─────────────────────────────────────────────────────────────────┘
   Sensors → Detection → Tracking → [PID] → Servo Commands

┌─────────────────────────────────────────────────────────────────┐
│                    الوضع المطلوب (RL Controller)                │
└─────────────────────────────────────────────────────────────────┘
   Sensors → Detection → Tracking → [RL Agent] → Servo Commands
```

## 1.3 مراحل التنفيذ

```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│   المرحلة 1 │    │   المرحلة 2 │    │   المرحلة 3 │
│   Simulation│───▶│   Sim2Real  │───▶│   Hardware  │
│   Training  │    │   Transfer  │    │   Deploy    │
└─────────────┘    └─────────────┘    └─────────────┘
```

---

# الفصل 2: المدخلات (Observation Space)

## 2.1 نظرة عامة

الـ RL Agent يحتاج "رؤية" كاملة لحالة النظام ليتخذ قرارات صحيحة.

```
┌─────────────────────────────────────────────────────────────────┐
│                    Observation Vector (27-30 عنصر)              │
├─────────────────┬─────────────────┬─────────────────────────────┤
│   Target State  │   Servo State   │   Environment State         │
│   (9 عناصر)     │   (5 عناصر)     │   (13 عنصر)                 │
└─────────────────┴─────────────────┴─────────────────────────────┘
```

## 2.2 بيانات الهدف (Target State)

### الحقول
| الحقل | النوع | النطاق | الوصف | المصدر في المشروع |
|-------|-------|--------|-------|-------------------|
| `target_x` | float | -1 to 1 | موقع X بالنسبة للمركز | `TrackingResult.boundingBox.centerX()` |
| `target_y` | float | -1 to 1 | موقع Y بالنسبة للمركز | `TrackingResult.boundingBox.centerY()` |
| `target_width` | float | 0 to 1 | عرض الهدف (normalized) | `TrackingResult.boundingBox.width()` |
| `target_height` | float | 0 to 1 | ارتفاع الهدف (normalized) | `TrackingResult.boundingBox.height()` |
| `target_vx` | float | -10 to 10 | سرعة الهدف أفقياً | `ObjectTracker.velocity.x` |
| `target_vy` | float | -10 to 10 | سرعة الهدف عمودياً | `ObjectTracker.velocity.y` |
| `confidence` | float | 0 to 1 | ثقة الكشف | `TrackingResult.confidence` |
| `target_visible` | bool | 0 or 1 | هل الهدف مرئي | `StableTracker.isTracking` |
| `frames_since_lost` | int | 0 to 100 | إطارات منذ الفقدان | `StableTracker.lostFrames` |

### كود الاستخراج
```kotlin
data class TargetObservation(
    val targetX: Float,
    val targetY: Float,
    val targetWidth: Float,
    val targetHeight: Float,
    val targetVx: Float,
    val targetVy: Float,
    val confidence: Float,
    val targetVisible: Float,
    val framesSinceLost: Float
)

fun extractTargetState(
    result: TrackingResult?,
    tracker: ObjectTracker,
    frameWidth: Int,
    frameHeight: Int
): TargetObservation {
    if (result == null) {
        return TargetObservation(
            targetX = 0f,
            targetY = 0f,
            targetWidth = 0f,
            targetHeight = 0f,
            targetVx = 0f,
            targetVy = 0f,
            confidence = 0f,
            targetVisible = 0f,
            framesSinceLost = tracker.lostFrames.toFloat()
        )
    }
    
    val box = result.boundingBox
    val centerX = (box.centerX() / frameWidth) * 2 - 1  // Normalize to [-1, 1]
    val centerY = (box.centerY() / frameHeight) * 2 - 1
    
    return TargetObservation(
        targetX = centerX,
        targetY = centerY,
        targetWidth = box.width() / frameWidth,
        targetHeight = box.height() / frameHeight,
        targetVx = tracker.velocity.x / 10f,  // Normalize
        targetVy = tracker.velocity.y / 10f,
        confidence = result.confidence,
        targetVisible = 1f,
        framesSinceLost = 0f
    )
}
```

---

## 2.3 بيانات السيرفو (Servo State)

### الحقول
| الحقل | النوع | النطاق | الوصف | المصدر في المشروع |
|-------|-------|--------|-------|-------------------|
| `yaw_angle` | float | -100 to 100 | زاوية Yaw الحالية | `SharedBusManager.servoFeedback[0]` |
| `pitch_angle` | float | -100 to 100 | زاوية Pitch الحالية | `SharedBusManager.servoFeedback[1]` |
| `roll_angle` | float | -100 to 100 | زاوية Roll الحالية | `SharedBusManager.servoFeedback[2]` |
| `yaw_velocity` | float | -10 to 10 | سرعة Yaw | محسوب من التغير |
| `pitch_velocity` | float | -10 to 10 | سرعة Pitch | محسوب من التغير |

### كود الاستخراج
```kotlin
data class ServoObservation(
    val yawAngle: Float,
    val pitchAngle: Float,
    val rollAngle: Float,
    val yawVelocity: Float,
    val pitchVelocity: Float
)

class ServoStateExtractor {
    private var lastYaw = 0f
    private var lastPitch = 0f
    private var lastTimestamp = 0L
    
    fun extract(busManager: SharedBusManager): ServoObservation {
        val currentTime = System.currentTimeMillis()
        val dt = (currentTime - lastTimestamp) / 1000f
        
        val yaw = busManager.getServoFeedback(1)
        val pitch = busManager.getServoFeedback(2)
        val roll = busManager.getServoFeedback(3)
        
        val yawVel = if (dt > 0) (yaw - lastYaw) / dt else 0f
        val pitchVel = if (dt > 0) (pitch - lastPitch) / dt else 0f
        
        lastYaw = yaw
        lastPitch = pitch
        lastTimestamp = currentTime
        
        return ServoObservation(
            yawAngle = yaw / 100f,      // Normalize to [-1, 1]
            pitchAngle = pitch / 100f,
            rollAngle = roll / 100f,
            yawVelocity = yawVel / 10f,
            pitchVelocity = pitchVel / 10f
        )
    }
}
```

---

## 2.4 بيانات IMU (Environment State)

### الحقول
| الحقل | النوع | النطاق | الوصف | المصدر في المشروع |
|-------|-------|--------|-------|-------------------|
| `gyro_x` | float | -5 to 5 | سرعة الدوران X | `GyroManager.gyroX` |
| `gyro_y` | float | -5 to 5 | سرعة الدوران Y | `GyroManager.gyroY` |
| `gyro_z` | float | -5 to 5 | سرعة الدوران Z | `GyroManager.gyroZ` |
| `accel_x` | float | -20 to 20 | التسارع X | `AccelerometerManager.x` |
| `accel_y` | float | -20 to 20 | التسارع Y | `AccelerometerManager.y` |
| `accel_z` | float | -20 to 20 | التسارع Z | `AccelerometerManager.z` |
| `shake_level` | float | 0 to 1 | مستوى الاهتزاز | `SensorFusionLayer.shakeLevel` |

### كود الاستخراج
```kotlin
data class IMUObservation(
    val gyroX: Float,
    val gyroY: Float,
    val gyroZ: Float,
    val accelX: Float,
    val accelY: Float,
    val accelZ: Float,
    val shakeLevel: Float
)

fun extractIMUState(
    gyroManager: GyroManager,
    accelManager: AccelerometerManager
): IMUObservation {
    val gyro = gyroManager.getLatestValues()
    val accel = accelManager.getLatestValues()
    
    // Calculate shake level from acceleration variance
    val shakeLevel = sqrt(
        (accel.x - 0).pow(2) + 
        (accel.y - 0).pow(2) + 
        (accel.z - 9.8f).pow(2)
    ) / 10f
    
    return IMUObservation(
        gyroX = gyro.x / 5f,      // Normalize
        gyroY = gyro.y / 5f,
        gyroZ = gyro.z / 5f,
        accelX = accel.x / 20f,
        accelY = accel.y / 20f,
        accelZ = accel.z / 20f,
        shakeLevel = shakeLevel.coerceIn(0f, 1f)
    )
}
```

---

## 2.5 بيانات الخطأ (Error State)

### الحقول
| الحقل | النوع | النطاق | الوصف |
|-------|-------|--------|-------|
| `error_x` | float | -1 to 1 | الخطأ الأفقي |
| `error_y` | float | -1 to 1 | الخطأ العمودي |
| `error_integral_x` | float | -10 to 10 | تراكم الخطأ X |
| `error_integral_y` | float | -10 to 10 | تراكم الخطأ Y |
| `error_derivative_x` | float | -10 to 10 | تغير الخطأ X |
| `error_derivative_y` | float | -10 to 10 | تغير الخطأ Y |
| `time_on_target` | int | 0 to 1000 | إطارات على الهدف |

### كود الاستخراج
```kotlin
class ErrorStateExtractor {
    private var integralX = 0f
    private var integralY = 0f
    private var lastErrorX = 0f
    private var lastErrorY = 0f
    private var timeOnTarget = 0
    
    fun extract(targetX: Float, targetY: Float): ErrorObservation {
        val errorX = -targetX  // Error = desired (0) - actual
        val errorY = -targetY
        
        integralX += errorX * 0.033f  // dt ≈ 33ms
        integralY += errorY * 0.033f
        
        val derivativeX = (errorX - lastErrorX) / 0.033f
        val derivativeY = (errorY - lastErrorY) / 0.033f
        
        // Clamp integral to prevent windup
        integralX = integralX.coerceIn(-10f, 10f)
        integralY = integralY.coerceIn(-10f, 10f)
        
        // Track time on target
        if (abs(errorX) < 0.1f && abs(errorY) < 0.1f) {
            timeOnTarget++
        } else {
            timeOnTarget = 0
        }
        
        lastErrorX = errorX
        lastErrorY = errorY
        
        return ErrorObservation(
            errorX = errorX,
            errorY = errorY,
            errorIntegralX = integralX / 10f,
            errorIntegralY = integralY / 10f,
            errorDerivativeX = derivativeX / 10f,
            errorDerivativeY = derivativeY / 10f,
            timeOnTarget = (timeOnTarget / 1000f).coerceIn(0f, 1f)
        )
    }
}
```

---

## 2.6 تجميع الـ Observation الكامل

```kotlin
class ObservationBuilder(
    private val servoExtractor: ServoStateExtractor,
    private val errorExtractor: ErrorStateExtractor
) {
    fun build(
        trackingResult: TrackingResult?,
        tracker: ObjectTracker,
        busManager: SharedBusManager,
        gyroManager: GyroManager,
        accelManager: AccelerometerManager,
        frameWidth: Int,
        frameHeight: Int
    ): FloatArray {
        val target = extractTargetState(trackingResult, tracker, frameWidth, frameHeight)
        val servo = servoExtractor.extract(busManager)
        val imu = extractIMUState(gyroManager, accelManager)
        val error = errorExtractor.extract(target.targetX, target.targetY)
        
        return floatArrayOf(
            // Target (9)
            target.targetX, target.targetY,
            target.targetWidth, target.targetHeight,
            target.targetVx, target.targetVy,
            target.confidence, target.targetVisible, target.framesSinceLost,
            
            // Servo (5)
            servo.yawAngle, servo.pitchAngle, servo.rollAngle,
            servo.yawVelocity, servo.pitchVelocity,
            
            // IMU (7)
            imu.gyroX, imu.gyroY, imu.gyroZ,
            imu.accelX, imu.accelY, imu.accelZ,
            imu.shakeLevel,
            
            // Error (7)
            error.errorX, error.errorY,
            error.errorIntegralX, error.errorIntegralY,
            error.errorDerivativeX, error.errorDerivativeY,
            error.timeOnTarget
        )
    }
}
```

---

# الفصل 3: المخرجات (Action Space)

## 3.1 أنواع مساحة الإجراءات

### الخيار 1: تحكم بالزاوية المطلقة (Position Control)
```python
# Action = الزاوية المستهدفة مباشرة
action_space = spaces.Box(
    low=np.array([-100, -100, -100]),   # Yaw, Pitch, Roll (degrees)
    high=np.array([100, 100, 100]),
    dtype=np.float32
)
```

### الخيار 2: تحكم بالتغيير (Delta Control) ✅ موصى به
```python
# Action = التغيير في الزاوية
action_space = spaces.Box(
    low=np.array([-1, -1, -1]),   # Normalized
    high=np.array([1, 1, 1]),
    dtype=np.float32
)
# ثم يُضرب في scale (مثلاً 5°)
```

### الخيار 3: تحكم بالسرعة (Velocity Control)
```python
# Action = سرعة الدوران
action_space = spaces.Box(
    low=np.array([-1, -1, -1]),   # Normalized velocity
    high=np.array([1, 1, 1]),
    dtype=np.float32
)
# ثم يُضرب في max_velocity (مثلاً 100°/s)
```

---

## 3.2 تحويل Action إلى أوامر سيرفو

```kotlin
class ActionExecutor(
    private val busManager: SharedBusManager
) {
    private val ACTION_SCALE = 5f  // ±5° per action unit
    private val MAX_ANGLE = 100f
    private val MIN_ANGLE = -100f
    
    private var currentYaw = 0f
    private var currentPitch = 0f
    private var currentRoll = 0f
    
    fun execute(action: FloatArray) {
        // Scale actions
        val deltaYaw = action[0] * ACTION_SCALE
        val deltaPitch = action[1] * ACTION_SCALE
        val deltaRoll = action[2] * ACTION_SCALE
        
        // Apply deltas with clamping
        currentYaw = (currentYaw + deltaYaw).coerceIn(MIN_ANGLE, MAX_ANGLE)
        currentPitch = (currentPitch + deltaPitch).coerceIn(MIN_ANGLE, MAX_ANGLE)
        currentRoll = (currentRoll + deltaRoll).coerceIn(MIN_ANGLE, MAX_ANGLE)
        
        // Send to servos
        busManager.moveServo(1, currentYaw)
        busManager.moveServo(2, currentPitch)
        busManager.moveServo(3, currentRoll)
    }
    
    fun reset() {
        currentYaw = 0f
        currentPitch = 0f
        currentRoll = 0f
        busManager.moveServo(1, 0f)
        busManager.moveServo(2, 0f)
        busManager.moveServo(3, 0f)
    }
}
```

---

## 3.3 للتحكم بالطائرة (Drone)

```python
# Action Space للطيران
action_space = spaces.Box(
    low=np.array([0, -1, -1, -1]),    # [Throttle, Roll, Pitch, Yaw]
    high=np.array([1, 1, 1, 1]),
    dtype=np.float32
)
```

```kotlin
class DroneActionExecutor(
    private val flightController: STM32FlightController
) {
    fun execute(action: FloatArray) {
        val throttle = (action[0] * 1000).toInt()  // 0-1000
        val roll = (action[1] * 500).toInt()       // -500 to 500
        val pitch = (action[2] * 500).toInt()      // -500 to 500
        val yaw = (action[3] * 500).toInt()        // -500 to 500
        
        flightController.sendCommand(throttle, roll, pitch, yaw)
    }
}
```

---

# الفصل 4: دالة المكافأة (Reward Function)

## 4.1 المبادئ الأساسية

| المبدأ | الوصف |
|--------|-------|
| **الهدف في المركز** | مكافأة كبيرة عند تقليل الخطأ |
| **الاستقرار** | عقوبة على الحركات المفرطة |
| **المتابعة المستمرة** | مكافأة على الحفاظ على الهدف |
| **فقدان الهدف** | عقوبة كبيرة |

## 4.2 دالة المكافأة الكاملة

```python
def calculate_reward(
    state: np.ndarray,
    action: np.ndarray,
    next_state: np.ndarray,
    info: dict
) -> float:
    reward = 0.0
    
    # ═══════════════════════════════════════════════════════
    # 1. مكافأة على تقليل الخطأ (MAIN OBJECTIVE)
    # ═══════════════════════════════════════════════════════
    error_x = next_state[20]  # error_x index
    error_y = next_state[21]  # error_y index
    error_distance = np.sqrt(error_x**2 + error_y**2)
    
    # Exponential reward: max 1.0 when on target
    tracking_reward = np.exp(-5 * error_distance)
    reward += tracking_reward * 2.0  # Weight: 2.0
    
    # ═══════════════════════════════════════════════════════
    # 2. مكافأة على الدقة العالية (PRECISION BONUS)
    # ═══════════════════════════════════════════════════════
    if error_distance < 0.05:  # Within 5% of center
        reward += 3.0  # Big bonus for precision
    elif error_distance < 0.1:  # Within 10%
        reward += 1.5
    elif error_distance < 0.2:  # Within 20%
        reward += 0.5
    
    # ═══════════════════════════════════════════════════════
    # 3. مكافأة على رؤية الهدف (VISIBILITY)
    # ═══════════════════════════════════════════════════════
    target_visible = next_state[7]  # target_visible index
    if target_visible > 0.5:
        reward += 0.5
    else:
        reward -= 2.0  # Penalty for losing target
    
    # ═══════════════════════════════════════════════════════
    # 4. عقوبة على الحركة الزائدة (SMOOTHNESS)
    # ═══════════════════════════════════════════════════════
    action_magnitude = np.sum(np.abs(action))
    reward -= 0.1 * action_magnitude
    
    # Extra penalty for jerky movements
    if np.max(np.abs(action)) > 0.8:
        reward -= 0.3
    
    # ═══════════════════════════════════════════════════════
    # 5. مكافأة على تحسين الخطأ (IMPROVEMENT)
    # ═══════════════════════════════════════════════════════
    prev_error_x = state[20]
    prev_error_y = state[21]
    prev_error_dist = np.sqrt(prev_error_x**2 + prev_error_y**2)
    
    improvement = prev_error_dist - error_distance
    reward += improvement * 5.0  # Reward improvement
    
    # ═══════════════════════════════════════════════════════
    # 6. عقوبة على فقدان الهدف طويلاً (TIMEOUT)
    # ═══════════════════════════════════════════════════════
    frames_since_lost = next_state[8]
    if frames_since_lost > 10:
        reward -= 0.5 * (frames_since_lost / 10)
    if frames_since_lost > 30:
        reward -= 10.0  # Heavy penalty
    
    # ═══════════════════════════════════════════════════════
    # 7. مكافأة على الوقت على الهدف (TIME ON TARGET)
    # ═══════════════════════════════════════════════════════
    time_on_target = next_state[26]  # time_on_target index
    reward += time_on_target * 2.0
    
    # ═══════════════════════════════════════════════════════
    # 8. عقوبة على الاهتزاز العالي (STABILITY)
    # ═══════════════════════════════════════════════════════
    shake_level = next_state[19]  # shake_level index
    if shake_level > 0.5:
        reward -= shake_level * 0.5
    
    return reward
```

---

## 4.3 Reward Shaping للتدريب السريع

```python
class RewardShaper:
    def __init__(self):
        self.curriculum_stage = 0
        self.episodes_at_stage = 0
        
    def get_reward(self, state, action, next_state, info):
        base_reward = calculate_reward(state, action, next_state, info)
        
        # Curriculum Learning Stages
        if self.curriculum_stage == 0:
            # Stage 0: Just track stationary target
            return base_reward * 2.0  # Amplify for easier learning
            
        elif self.curriculum_stage == 1:
            # Stage 1: Track slow-moving target
            return base_reward * 1.5
            
        elif self.curriculum_stage == 2:
            # Stage 2: Track fast-moving target
            return base_reward * 1.2
            
        else:
            # Stage 3: Full complexity
            return base_reward
    
    def maybe_advance_stage(self, success_rate):
        self.episodes_at_stage += 1
        if success_rate > 0.8 and self.episodes_at_stage > 100:
            self.curriculum_stage = min(self.curriculum_stage + 1, 3)
            self.episodes_at_stage = 0
            print(f"Advanced to curriculum stage {self.curriculum_stage}")
```

---

# الفصل 5: بناء البيئة (Environment)

## 5.1 بيئة المحاكاة (Simulation)

```python
import gymnasium as gym
from gymnasium import spaces
import numpy as np

class SAQRGimbalEnv(gym.Env):
    """
    بيئة محاكاة لنظام SAQR Gimbal للتدريب.
    """
    
    metadata = {"render_modes": ["human", "rgb_array"], "render_fps": 30}
    
    def __init__(self, render_mode=None):
        super().__init__()
        
        self.render_mode = render_mode
        
        # ═══════════════════════════════════════════════════
        # Observation Space (27 elements)
        # ═══════════════════════════════════════════════════
        self.observation_space = spaces.Box(
            low=np.array([
                # Target (9)
                -1, -1,           # target_x, target_y
                0, 0,             # target_w, target_h
                -1, -1,           # target_vx, target_vy
                0,                # confidence
                0,                # target_visible
                0,                # frames_since_lost
                # Servo (5)
                -1, -1, -1,       # yaw, pitch, roll
                -1, -1,           # yaw_vel, pitch_vel
                # IMU (7)
                -1, -1, -1,       # gyro
                -1, -1, -1,       # accel
                0,                # shake
                # Error (7)
                -1, -1,           # error
                -1, -1,           # integral
                -1, -1,           # derivative
                0,                # time_on_target
            ], dtype=np.float32),
            high=np.array([
                # Target (9)
                1, 1,             # target_x, target_y
                1, 1,             # target_w, target_h
                1, 1,             # target_vx, target_vy
                1,                # confidence
                1,                # target_visible
                1,                # frames_since_lost
                # Servo (5)
                1, 1, 1,          # yaw, pitch, roll
                1, 1,             # yaw_vel, pitch_vel
                # IMU (7)
                1, 1, 1,          # gyro
                1, 1, 1,          # accel
                1,                # shake
                # Error (7)
                1, 1,             # error
                1, 1,             # integral
                1, 1,             # derivative
                1,                # time_on_target
            ], dtype=np.float32),
        )
        
        # ═══════════════════════════════════════════════════
        # Action Space (3 elements: yaw_delta, pitch_delta, roll_delta)
        # ═══════════════════════════════════════════════════
        self.action_space = spaces.Box(
            low=-1.0, high=1.0, shape=(3,), dtype=np.float32
        )
        
        # Internal state
        self.reset()
    
    def reset(self, seed=None, options=None):
        super().reset(seed=seed)
        
        # Initialize target at random position
        self.target_x = self.np_random.uniform(-0.5, 0.5)
        self.target_y = self.np_random.uniform(-0.5, 0.5)
        self.target_vx = self.np_random.uniform(-0.02, 0.02)
        self.target_vy = self.np_random.uniform(-0.02, 0.02)
        
        # Initialize gimbal at center
        self.gimbal_yaw = 0.0
        self.gimbal_pitch = 0.0
        self.gimbal_roll = 0.0
        
        # Initialize counters
        self.steps = 0
        self.time_on_target = 0
        self.frames_since_lost = 0
        self.error_integral = np.array([0.0, 0.0])
        self.last_error = np.array([0.0, 0.0])
        
        observation = self._get_observation()
        info = {}
        
        return observation, info
    
    def step(self, action):
        self.steps += 1
        
        # ═══════════════════════════════════════════════════
        # 1. Apply action to gimbal
        # ═══════════════════════════════════════════════════
        action_scale = 0.05  # 5% of range per action
        self.gimbal_yaw += action[0] * action_scale
        self.gimbal_pitch += action[1] * action_scale
        self.gimbal_roll += action[2] * action_scale
        
        # Clamp gimbal angles
        self.gimbal_yaw = np.clip(self.gimbal_yaw, -1, 1)
        self.gimbal_pitch = np.clip(self.gimbal_pitch, -1, 1)
        self.gimbal_roll = np.clip(self.gimbal_roll, -1, 1)
        
        # ═══════════════════════════════════════════════════
        # 2. Update target position
        # ═══════════════════════════════════════════════════
        self.target_x += self.target_vx
        self.target_y += self.target_vy
        
        # Bounce off edges
        if abs(self.target_x) > 0.9:
            self.target_vx *= -1
        if abs(self.target_y) > 0.9:
            self.target_vy *= -1
        
        # Random velocity changes
        if self.np_random.random() < 0.05:
            self.target_vx += self.np_random.uniform(-0.01, 0.01)
            self.target_vy += self.np_random.uniform(-0.01, 0.01)
        
        # ═══════════════════════════════════════════════════
        # 3. Calculate apparent target position (relative to gimbal)
        # ═══════════════════════════════════════════════════
        apparent_x = self.target_x - self.gimbal_yaw
        apparent_y = self.target_y - self.gimbal_pitch
        
        # ═══════════════════════════════════════════════════
        # 4. Check if target is visible
        # ═══════════════════════════════════════════════════
        target_visible = abs(apparent_x) < 0.5 and abs(apparent_y) < 0.5
        
        if target_visible:
            self.frames_since_lost = 0
            error = np.sqrt(apparent_x**2 + apparent_y**2)
            if error < 0.1:
                self.time_on_target += 1
            else:
                self.time_on_target = max(0, self.time_on_target - 1)
        else:
            self.frames_since_lost += 1
            self.time_on_target = 0
        
        # ═══════════════════════════════════════════════════
        # 5. Calculate reward
        # ═══════════════════════════════════════════════════
        observation = self._get_observation()
        reward = self._calculate_reward(action, apparent_x, apparent_y, target_visible)
        
        # ═══════════════════════════════════════════════════
        # 6. Check termination
        # ═══════════════════════════════════════════════════
        terminated = self.frames_since_lost > 30
        truncated = self.steps >= 1000
        
        info = {
            "error": np.sqrt(apparent_x**2 + apparent_y**2),
            "target_visible": target_visible,
            "time_on_target": self.time_on_target,
        }
        
        return observation, reward, terminated, truncated, info
    
    def _get_observation(self):
        apparent_x = self.target_x - self.gimbal_yaw
        apparent_y = self.target_y - self.gimbal_pitch
        target_visible = abs(apparent_x) < 0.5 and abs(apparent_y) < 0.5
        
        # Calculate error derivatives
        error = np.array([apparent_x, apparent_y])
        self.error_integral += error * 0.033
        self.error_integral = np.clip(self.error_integral, -1, 1)
        error_derivative = (error - self.last_error) / 0.033
        self.last_error = error.copy()
        
        return np.array([
            # Target (9)
            apparent_x if target_visible else 0,
            apparent_y if target_visible else 0,
            0.1, 0.1,  # target size
            self.target_vx, self.target_vy,
            0.9 if target_visible else 0,  # confidence
            1.0 if target_visible else 0,
            self.frames_since_lost / 30.0,
            # Servo (5)
            self.gimbal_yaw,
            self.gimbal_pitch,
            self.gimbal_roll,
            0, 0,  # velocities
            # IMU (7)
            0, 0, 0,  # gyro
            0, 0, 1.0,  # accel (gravity)
            0,  # shake
            # Error (7)
            apparent_x if target_visible else 0,
            apparent_y if target_visible else 0,
            self.error_integral[0],
            self.error_integral[1],
            np.clip(error_derivative[0], -1, 1),
            np.clip(error_derivative[1], -1, 1),
            self.time_on_target / 100.0,
        ], dtype=np.float32)
    
    def _calculate_reward(self, action, apparent_x, apparent_y, target_visible):
        reward = 0.0
        
        error_dist = np.sqrt(apparent_x**2 + apparent_y**2)
        
        # Tracking reward
        if target_visible:
            reward += np.exp(-5 * error_dist) * 2.0
            if error_dist < 0.05:
                reward += 3.0
            elif error_dist < 0.1:
                reward += 1.5
        else:
            reward -= 2.0
        
        # Smoothness penalty
        reward -= 0.1 * np.sum(np.abs(action))
        
        # Time on target bonus
        reward += self.time_on_target * 0.02
        
        return reward
```

---

## 5.2 تسجيل البيئة

```python
from gymnasium.envs.registration import register

register(
    id="SAQRGimbal-v0",
    entry_point="saqr_env:SAQRGimbalEnv",
    max_episode_steps=1000,
)
```

---

# الفصل 6: التدريب والخوارزميات

## 6.1 اختيار الخوارزمية

| الخوارزمية | المميزات | العيوب | الاستخدام |
|------------|----------|--------|-----------|
| **PPO** | مستقر، سهل الضبط | أبطأ من Off-Policy | ✅ للبداية |
| **SAC** | Sample Efficient | أكثر تعقيداً | ✅ للدقة العالية |
| **TD3** | مناسب للـ Continuous | حساس للـ Hyperparameters | للسرعة |
| **DDPG** | بسيط | غير مستقر | غير موصى |

## 6.2 التدريب باستخدام PPO

```python
from stable_baselines3 import PPO
from stable_baselines3.common.env_util import make_vec_env
from stable_baselines3.common.callbacks import EvalCallback, CheckpointCallback

# Create vectorized environment
env = make_vec_env("SAQRGimbal-v0", n_envs=8)

# Create evaluation environment
eval_env = make_vec_env("SAQRGimbal-v0", n_envs=1)

# Callbacks
eval_callback = EvalCallback(
    eval_env,
    best_model_save_path="./models/best/",
    log_path="./logs/",
    eval_freq=10000,
    deterministic=True,
    render=False,
)

checkpoint_callback = CheckpointCallback(
    save_freq=50000,
    save_path="./models/checkpoints/",
    name_prefix="ppo_gimbal",
)

# Create and train model
model = PPO(
    "MlpPolicy",
    env,
    learning_rate=3e-4,
    n_steps=2048,
    batch_size=64,
    n_epochs=10,
    gamma=0.99,
    gae_lambda=0.95,
    clip_range=0.2,
    ent_coef=0.01,
    vf_coef=0.5,
    max_grad_norm=0.5,
    verbose=1,
    tensorboard_log="./logs/tensorboard/",
)

# Train
model.learn(
    total_timesteps=5_000_000,
    callback=[eval_callback, checkpoint_callback],
    progress_bar=True,
)

# Save final model
model.save("models/ppo_gimbal_final")
```

---

## 6.3 التدريب باستخدام SAC

```python
from stable_baselines3 import SAC

model = SAC(
    "MlpPolicy",
    env,
    learning_rate=3e-4,
    buffer_size=1_000_000,
    learning_starts=10000,
    batch_size=256,
    tau=0.005,
    gamma=0.99,
    train_freq=1,
    gradient_steps=1,
    ent_coef="auto",
    verbose=1,
    tensorboard_log="./logs/tensorboard/",
)

model.learn(total_timesteps=2_000_000)
model.save("models/sac_gimbal_final")
```

---

## 6.4 Hyperparameter Tuning

```python
import optuna
from stable_baselines3 import PPO
from stable_baselines3.common.evaluation import evaluate_policy

def objective(trial):
    # Sample hyperparameters
    learning_rate = trial.suggest_float("learning_rate", 1e-5, 1e-3, log=True)
    n_steps = trial.suggest_categorical("n_steps", [512, 1024, 2048, 4096])
    batch_size = trial.suggest_categorical("batch_size", [32, 64, 128, 256])
    gamma = trial.suggest_float("gamma", 0.95, 0.999)
    gae_lambda = trial.suggest_float("gae_lambda", 0.9, 0.99)
    clip_range = trial.suggest_float("clip_range", 0.1, 0.3)
    ent_coef = trial.suggest_float("ent_coef", 0.001, 0.1, log=True)
    
    env = make_vec_env("SAQRGimbal-v0", n_envs=4)
    
    model = PPO(
        "MlpPolicy",
        env,
        learning_rate=learning_rate,
        n_steps=n_steps,
        batch_size=batch_size,
        gamma=gamma,
        gae_lambda=gae_lambda,
        clip_range=clip_range,
        ent_coef=ent_coef,
        verbose=0,
    )
    
    model.learn(total_timesteps=100_000)
    
    mean_reward, _ = evaluate_policy(model, env, n_eval_episodes=10)
    
    return mean_reward

study = optuna.create_study(direction="maximize")
study.optimize(objective, n_trials=50)

print("Best hyperparameters:", study.best_params)
```

---

# الفصل 7: النشر على الهاردوير

## 7.1 تحويل النموذج لـ TFLite

```python
import tensorflow as tf
from stable_baselines3 import PPO
import numpy as np

# Load trained model
model = PPO.load("models/ppo_gimbal_final")

# Extract policy network
policy = model.policy

# Create TF function
class PolicyWrapper(tf.Module):
    def __init__(self, policy):
        self.policy = policy
        
    @tf.function(input_signature=[tf.TensorSpec([1, 27], tf.float32)])
    def predict(self, observation):
        action, _ = self.policy.predict(observation, deterministic=True)
        return action

wrapper = PolicyWrapper(policy)

# Convert to TFLite
converter = tf.lite.TFLiteConverter.from_concrete_functions(
    [wrapper.predict.get_concrete_function()]
)
converter.target_spec.supported_ops = [
    tf.lite.OpsSet.TFLITE_BUILTINS,
    tf.lite.OpsSet.SELECT_TF_OPS,
]
converter.optimizations = [tf.lite.Optimize.DEFAULT]

tflite_model = converter.convert()

# Save
with open("models/rl_policy.tflite", "wb") as f:
    f.write(tflite_model)
```

---

## 7.2 تشغيل النموذج على Android

```kotlin
class RLController(context: Context) {
    private val interpreter: Interpreter
    private val inputBuffer: ByteBuffer
    private val outputBuffer: ByteBuffer
    
    init {
        // Load TFLite model
        val modelFile = loadModelFile(context, "rl_policy.tflite")
        interpreter = Interpreter(modelFile)
        
        // Allocate buffers
        inputBuffer = ByteBuffer.allocateDirect(27 * 4)  // 27 floats
        inputBuffer.order(ByteOrder.nativeOrder())
        
        outputBuffer = ByteBuffer.allocateDirect(3 * 4)  // 3 floats
        outputBuffer.order(ByteOrder.nativeOrder())
    }
    
    fun predict(observation: FloatArray): FloatArray {
        // Prepare input
        inputBuffer.rewind()
        for (value in observation) {
            inputBuffer.putFloat(value)
        }
        
        // Run inference
        inputBuffer.rewind()
        outputBuffer.rewind()
        interpreter.run(inputBuffer, outputBuffer)
        
        // Extract output
        outputBuffer.rewind()
        val action = FloatArray(3)
        for (i in 0 until 3) {
            action[i] = outputBuffer.getFloat()
        }
        
        return action
    }
    
    private fun loadModelFile(context: Context, filename: String): MappedByteBuffer {
        val assetManager = context.assets
        val fileDescriptor = assetManager.openFd(filename)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            fileDescriptor.startOffset,
            fileDescriptor.declaredLength
        )
    }
}
```

---

## 7.3 تكامل مع النظام الحالي

```kotlin
class HybridController(
    context: Context,
    private val busManager: SharedBusManager,
    private val observationBuilder: ObservationBuilder
) {
    private val rlController = RLController(context)
    private val actionExecutor = ActionExecutor(busManager)
    
    private var useRL = true  // Toggle between RL and PID
    
    fun update(
        trackingResult: TrackingResult?,
        tracker: ObjectTracker,
        gyroManager: GyroManager,
        accelManager: AccelerometerManager,
        frameWidth: Int,
        frameHeight: Int
    ) {
        if (useRL) {
            // Build observation
            val observation = observationBuilder.build(
                trackingResult, tracker, busManager,
                gyroManager, accelManager, frameWidth, frameHeight
            )
            
            // Get RL action
            val action = rlController.predict(observation)
            
            // Execute action
            actionExecutor.execute(action)
        } else {
            // Fallback to PID
            pidController.update(trackingResult)
        }
    }
    
    fun toggleMode() {
        useRL = !useRL
        Log.d("Controller", "Mode: ${if (useRL) "RL" else "PID"}")
    }
}
```

---

# الملاحق

## ملحق أ: ملخص المدخلات والمخرجات

### جدول المدخلات (27 عنصر)
| الفهرس | الحقل | النطاق | المصدر |
|--------|-------|--------|--------|
| 0-1 | target_x, target_y | [-1, 1] | TrackingResult |
| 2-3 | target_w, target_h | [0, 1] | TrackingResult |
| 4-5 | target_vx, target_vy | [-1, 1] | ObjectTracker |
| 6 | confidence | [0, 1] | TrackingResult |
| 7 | target_visible | [0, 1] | StableTracker |
| 8 | frames_since_lost | [0, 1] | StableTracker |
| 9-11 | yaw, pitch, roll | [-1, 1] | SharedBusManager |
| 12-13 | yaw_vel, pitch_vel | [-1, 1] | computed |
| 14-16 | gyro_x, y, z | [-1, 1] | GyroManager |
| 17-19 | accel_x, y, z | [-1, 1] | AccelerometerManager |
| 20 | shake_level | [0, 1] | SensorFusionLayer |
| 21-22 | error_x, error_y | [-1, 1] | computed |
| 23-24 | integral_x, integral_y | [-1, 1] | computed |
| 25-26 | derivative_x, derivative_y | [-1, 1] | computed |

### جدول المخرجات (3 عناصر)
| الفهرس | الحقل | النطاق | الوجهة |
|--------|-------|--------|--------|
| 0 | yaw_delta | [-1, 1] | Servo 1 |
| 1 | pitch_delta | [-1, 1] | Servo 2 |
| 2 | roll_delta | [-1, 1] | Servo 3 |

---

## ملحق ب: متطلبات التدريب

| المتطلب | الحد الأدنى | الموصى |
|---------|-------------|--------|
| **GPU** | GTX 1060 | RTX 3080+ |
| **RAM** | 16 GB | 32 GB |
| **Python** | 3.8 | 3.10 |
| **PyTorch** | 1.13 | 2.0+ |
| **Stable-Baselines3** | 2.0 | 2.1+ |
| **وقت التدريب** | 2-4 ساعات | 6-12 ساعة |
| **عدد الخطوات** | 1M | 5M+ |

---

## ملحق ج: المكتبات المطلوبة

```bash
# Python packages
pip install gymnasium
pip install stable-baselines3[extra]
pip install tensorboard
pip install optuna
pip install numpy
pip install torch

# For Android deployment
pip install tensorflow
pip install tensorflow-lite
```

---

## ملحق د: هيكل المجلدات

```
rl_project/
├── envs/
│   ├── __init__.py
│   └── saqr_gimbal_env.py
├── models/
│   ├── checkpoints/
│   ├── best/
│   └── rl_policy.tflite
├── logs/
│   └── tensorboard/
├── scripts/
│   ├── train_ppo.py
│   ├── train_sac.py
│   └── export_tflite.py
├── android/
│   └── app/src/main/java/.../
│       ├── RLController.kt
│       ├── ObservationBuilder.kt
│       └── ActionExecutor.kt
└── README.md
```

---

# نهاية الدليل

**الإصدار**: 1.0  
**آخر تحديث**: 2026-01-12  
**المؤلف**: SAQR Seeker Development Team

---

> **ملاحظة**: هذا الدليل يوفر الأساس لتدريب ونشر نموذج RL للتحكم في نظام SAQR Seeker. للحصول على أفضل النتائج، يُنصح بالبدء بالمحاكاة ثم الانتقال تدريجياً للهاردوير الحقيقي.
