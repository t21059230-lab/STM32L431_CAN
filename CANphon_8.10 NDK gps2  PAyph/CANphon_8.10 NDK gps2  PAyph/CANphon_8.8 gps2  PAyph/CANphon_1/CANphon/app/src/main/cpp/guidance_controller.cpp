/**
 * guidance_controller.cpp
 * وحدة التحكم والتوجيه
 * 
 * يحتوي على:
 * - PID Controller
 * - X-Mixing للـ Servos
 * - Low-Pass Filter للتنعيم
 */

#include <cmath>
#include <android/log.h>

#define LOG_TAG "NativeGuidance"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// ═══════════════════════════════════════════════════════════════════════════
// PID Controller
// ═══════════════════════════════════════════════════════════════════════════

struct PIDController {
    // Gains
    float kp, ki, kd;
    
    // State
    float integral;
    float prevError;
    float prevOutput;
    
    // Limits
    float outputMin, outputMax;
    float integralMax;
    
    // Low-pass filter
    float alpha;
    
    bool initialized;
};

static PIDController pidX = {0}, pidY = {0};

extern "C" void pidInit(int axis, float kp, float ki, float kd, 
                        float outputMin, float outputMax, float alpha) {
    PIDController* pid = (axis == 0) ? &pidX : &pidY;
    
    pid->kp = kp;
    pid->ki = ki;
    pid->kd = kd;
    pid->outputMin = outputMin;
    pid->outputMax = outputMax;
    pid->integralMax = outputMax * 0.5f;  // Anti-windup
    pid->alpha = alpha;
    
    pid->integral = 0;
    pid->prevError = 0;
    pid->prevOutput = 0;
    pid->initialized = true;
    
    LOGI("PID[%d] initialized: Kp=%.2f, Ki=%.2f, Kd=%.2f, α=%.2f", 
         axis, kp, ki, kd, alpha);
}

extern "C" float pidUpdate(int axis, float error, float dt) {
    PIDController* pid = (axis == 0) ? &pidX : &pidY;
    
    if (!pid->initialized) return 0;
    if (dt <= 0) dt = 0.033f;  // Default 30fps
    
    // Proportional
    float pTerm = pid->kp * error;
    
    // Integral (with anti-windup)
    pid->integral += error * dt;
    if (pid->integral > pid->integralMax) pid->integral = pid->integralMax;
    if (pid->integral < -pid->integralMax) pid->integral = -pid->integralMax;
    float iTerm = pid->ki * pid->integral;
    
    // Derivative
    float derivative = (error - pid->prevError) / dt;
    float dTerm = pid->kd * derivative;
    pid->prevError = error;
    
    // Sum
    float output = pTerm + iTerm + dTerm;
    
    // Low-pass filter
    output = pid->alpha * output + (1.0f - pid->alpha) * pid->prevOutput;
    pid->prevOutput = output;
    
    // Clamp
    if (output < pid->outputMin) output = pid->outputMin;
    if (output > pid->outputMax) output = pid->outputMax;
    
    return output;
}

extern "C" void pidReset(int axis) {
    PIDController* pid = (axis == 0) ? &pidX : &pidY;
    pid->integral = 0;
    pid->prevError = 0;
    pid->prevOutput = 0;
}

// ═══════════════════════════════════════════════════════════════════════════
// Guidance Controller (combines PID for tracking)
// ═══════════════════════════════════════════════════════════════════════════

struct GuidanceState {
    // Raw error from camera
    float rawErrorX, rawErrorY;
    
    // Filtered error
    float filteredErrorX, filteredErrorY;
    
    // Commands
    float pitchCmd, yawCmd;
    
    // Servo angles (X-config)
    float servoAngles[4];
    
    // Filter alpha
    float alpha;
    
    // Command limits
    float cmdMin, cmdMax;
    
    bool tracking;
};

static GuidanceState guidance = {
    .alpha = 0.6f,  // Faster response (balanced)
    .cmdMin = -25.0f,
    .cmdMax = 25.0f,
    .tracking = false
};

extern "C" void guidanceInit(float alpha, float cmdMax) {
    guidance.alpha = alpha;
    guidance.cmdMax = cmdMax;
    guidance.cmdMin = -cmdMax;
    
    // Initialize PIDs
    pidInit(0, 0.5f, 0.0f, 0.1f, -cmdMax, cmdMax, alpha);  // Yaw (X axis)
    pidInit(1, 0.5f, 0.0f, 0.1f, -cmdMax, cmdMax, alpha);  // Pitch (Y axis)
    
    LOGI("Guidance initialized: α=%.2f, cmdMax=%.1f°", alpha, cmdMax);
}

extern "C" void guidanceStart() {
    guidance.tracking = true;
    guidance.filteredErrorX = 0;
    guidance.filteredErrorY = 0;
    pidReset(0);
    pidReset(1);
    LOGI("Guidance tracking started");
}

extern "C" void guidanceStop() {
    guidance.tracking = false;
    guidance.pitchCmd = 0;
    guidance.yawCmd = 0;
    for (int i = 0; i < 4; i++) {
        guidance.servoAngles[i] = 0;
    }
    LOGI("Guidance tracking stopped");
}

extern "C" void guidanceUpdate(float errorX, float errorY, float dt) {
    if (!guidance.tracking) return;
    
    guidance.rawErrorX = errorX;
    guidance.rawErrorY = errorY;
    
    // Low-pass filter
    float a = guidance.alpha;
    guidance.filteredErrorX = a * errorX + (1-a) * guidance.filteredErrorX;
    guidance.filteredErrorY = a * errorY + (1-a) * guidance.filteredErrorY;
    
    // PID control
    // Yaw: errorX > 0 (target right) → yaw > 0
    // Pitch: errorY > 0 (target down) → pitch < 0 (inverted)
    guidance.yawCmd = pidUpdate(0, guidance.filteredErrorX, dt);
    guidance.pitchCmd = -pidUpdate(1, guidance.filteredErrorY, dt);  // Inverted!
    
    // X-Mixing for 4 servos
    float p = guidance.pitchCmd;
    float y = guidance.yawCmd;
    float min = guidance.cmdMin;
    float max = guidance.cmdMax;
    
    // Clamp with X-mixing
    float s0 = p + y;
    float s1 = p - y;
    float s2 = -p - y;
    float s3 = -p + y;
    
    guidance.servoAngles[0] = (s0 < min) ? min : (s0 > max) ? max : s0;
    guidance.servoAngles[1] = (s1 < min) ? min : (s1 > max) ? max : s1;
    guidance.servoAngles[2] = (s2 < min) ? min : (s2 > max) ? max : s2;
    guidance.servoAngles[3] = (s3 < min) ? min : (s3 > max) ? max : s3;
}

extern "C" void guidanceGetCommands(float* pitch, float* yaw) {
    *pitch = guidance.pitchCmd;
    *yaw = guidance.yawCmd;
}

extern "C" void guidanceGetServoAngles(float* angles) {
    for (int i = 0; i < 4; i++) {
        angles[i] = guidance.servoAngles[i];
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Sensor Fusion (IMU + GPS)
// ═══════════════════════════════════════════════════════════════════════════

struct SensorFusionState {
    // GPS reference position
    double gpsLat, gpsLon, gpsAlt;
    int64_t gpsTimestamp;
    
    // IMU integrated offsets
    double offsetN, offsetE, offsetD;  // North, East, Down
    
    // Velocity estimate
    double velN, velE, velD;
    
    // Fused position
    double fusedLat, fusedLon, fusedAlt;
    
    // Complementary filter alpha
    float alpha;
    
    bool hasGpsFix;
};

static SensorFusionState fusion = {
    .alpha = 0.98f,  // 98% IMU, 2% GPS correction
    .hasGpsFix = false
};

extern "C" void fusionInit(float alpha) {
    fusion.alpha = alpha;
    fusion.hasGpsFix = false;
    fusion.offsetN = 0;
    fusion.offsetE = 0;
    fusion.offsetD = 0;
    fusion.velN = 0;
    fusion.velE = 0;
    fusion.velD = 0;
    LOGI("Sensor Fusion initialized: α=%.2f", alpha);
}

extern "C" void fusionUpdateGps(double lat, double lon, double alt, int64_t timestamp) {
    fusion.gpsLat = lat;
    fusion.gpsLon = lon;
    fusion.gpsAlt = alt;
    fusion.gpsTimestamp = timestamp;
    
    // Reset offsets on GPS update (correction)
    fusion.offsetN *= (1.0 - (1.0 - fusion.alpha));
    fusion.offsetE *= (1.0 - (1.0 - fusion.alpha));
    fusion.offsetD *= (1.0 - (1.0 - fusion.alpha));
    
    fusion.hasGpsFix = true;
}

extern "C" void fusionIntegrateImu(float accelN, float accelE, float accelD, float dt) {
    if (!fusion.hasGpsFix) return;
    
    // Integrate acceleration to velocity
    fusion.velN += accelN * dt;
    fusion.velE += accelE * dt;
    fusion.velD += accelD * dt;
    
    // Integrate velocity to position offset
    fusion.offsetN += fusion.velN * dt;
    fusion.offsetE += fusion.velE * dt;
    fusion.offsetD += fusion.velD * dt;
    
    // Calculate fused position (GPS + IMU offset)
    // 1 degree ≈ 111,000 meters
    fusion.fusedLat = fusion.gpsLat + (fusion.offsetN / 111000.0);
    fusion.fusedLon = fusion.gpsLon + (fusion.offsetE / (111000.0 * cos(fusion.gpsLat * M_PI / 180.0)));
    fusion.fusedAlt = fusion.gpsAlt - fusion.offsetD;
}

extern "C" void fusionGetPosition(double* lat, double* lon, double* alt) {
    *lat = fusion.fusedLat;
    *lon = fusion.fusedLon;
    *alt = fusion.fusedAlt;
}

extern "C" void fusionGetVelocity(double* velN, double* velE, double* velD) {
    *velN = fusion.velN;
    *velE = fusion.velE;
    *velD = fusion.velD;
}

extern "C" bool fusionHasFix() {
    return fusion.hasGpsFix;
}
