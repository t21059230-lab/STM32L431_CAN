/**
 * filters.cpp
 * مرشحات الإشارة عالية الأداء
 * 
 * يحتوي على:
 * - IIR Low-Pass Filter
 * - Moving Average Filter
 * - Complementary Filter
 */

#include <cmath>
#include <cstring>
#include <android/log.h>

#define LOG_TAG "NativeFilters"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// ═══════════════════════════════════════════════════════════════════════════
// IIR Low-Pass Filter
// Formula: y = α * x + (1-α) * y_prev
// ═══════════════════════════════════════════════════════════════════════════

struct IIRFilter {
    float alpha;     // Filter coefficient (0-1)
    float value;     // Current filtered value
    bool initialized;
};

static IIRFilter iirFilters[16] = {0};  // Up to 16 independent filters

extern "C" void iirInit(int id, float alpha) {
    if (id < 0 || id >= 16) return;
    iirFilters[id].alpha = alpha;
    iirFilters[id].value = 0;
    iirFilters[id].initialized = false;
}

extern "C" float iirUpdate(int id, float input) {
    if (id < 0 || id >= 16) return input;
    
    IIRFilter& f = iirFilters[id];
    
    if (!f.initialized) {
        f.value = input;
        f.initialized = true;
    } else {
        // IIR: y = α * x + (1-α) * y_prev
        f.value = f.alpha * input + (1.0f - f.alpha) * f.value;
    }
    
    return f.value;
}

extern "C" float iirGet(int id) {
    if (id < 0 || id >= 16) return 0;
    return iirFilters[id].value;
}

extern "C" void iirReset(int id) {
    if (id < 0 || id >= 16) return;
    iirFilters[id].initialized = false;
    iirFilters[id].value = 0;
}

// ═══════════════════════════════════════════════════════════════════════════
// Vector IIR Filter (for 3D data like gyro, accel)
// ═══════════════════════════════════════════════════════════════════════════

struct VectorFilter {
    float alpha;
    float x, y, z;
    bool initialized;
};

static VectorFilter vecFilters[8] = {0};

extern "C" void vecFilterInit(int id, float alpha) {
    if (id < 0 || id >= 8) return;
    vecFilters[id].alpha = alpha;
    vecFilters[id].x = 0;
    vecFilters[id].y = 0;
    vecFilters[id].z = 0;
    vecFilters[id].initialized = false;
}

extern "C" void vecFilterUpdate(int id, float inX, float inY, float inZ, 
                                 float* outX, float* outY, float* outZ) {
    if (id < 0 || id >= 8) {
        *outX = inX; *outY = inY; *outZ = inZ;
        return;
    }
    
    VectorFilter& f = vecFilters[id];
    
    if (!f.initialized) {
        f.x = inX;
        f.y = inY;
        f.z = inZ;
        f.initialized = true;
    } else {
        float a = f.alpha;
        float b = 1.0f - a;
        f.x = a * inX + b * f.x;
        f.y = a * inY + b * f.y;
        f.z = a * inZ + b * f.z;
    }
    
    *outX = f.x;
    *outY = f.y;
    *outZ = f.z;
}

// ═══════════════════════════════════════════════════════════════════════════
// Complementary Filter (for sensor fusion)
// Combines high-frequency and low-frequency data
// ═══════════════════════════════════════════════════════════════════════════

struct ComplementaryFilter {
    float alpha;     // Weight for first input (gyro)
    float value;
    bool initialized;
};

static ComplementaryFilter compFilters[8] = {0};

extern "C" void compFilterInit(int id, float alpha) {
    if (id < 0 || id >= 8) return;
    compFilters[id].alpha = alpha;
    compFilters[id].value = 0;
    compFilters[id].initialized = false;
}

extern "C" float compFilterUpdate(int id, float highFreq, float lowFreq) {
    if (id < 0 || id >= 8) return lowFreq;
    
    ComplementaryFilter& f = compFilters[id];
    
    if (!f.initialized) {
        f.value = lowFreq;
        f.initialized = true;
    } else {
        // Complementary: y = α * highFreq + (1-α) * lowFreq
        f.value = f.alpha * highFreq + (1.0f - f.alpha) * lowFreq;
    }
    
    return f.value;
}

// ═══════════════════════════════════════════════════════════════════════════
// Moving Average Filter
// ═══════════════════════════════════════════════════════════════════════════

#define MA_MAX_SIZE 64

struct MovingAverage {
    float buffer[MA_MAX_SIZE];
    int size;
    int index;
    float sum;
    int count;
};

static MovingAverage maFilters[8] = {0};

extern "C" void maInit(int id, int size) {
    if (id < 0 || id >= 8) return;
    if (size > MA_MAX_SIZE) size = MA_MAX_SIZE;
    
    maFilters[id].size = size;
    maFilters[id].index = 0;
    maFilters[id].sum = 0;
    maFilters[id].count = 0;
    memset(maFilters[id].buffer, 0, sizeof(maFilters[id].buffer));
}

extern "C" float maUpdate(int id, float input) {
    if (id < 0 || id >= 8) return input;
    
    MovingAverage& f = maFilters[id];
    
    // Subtract oldest value if buffer is full
    if (f.count >= f.size) {
        f.sum -= f.buffer[f.index];
    } else {
        f.count++;
    }
    
    // Add new value
    f.buffer[f.index] = input;
    f.sum += input;
    
    // Advance index
    f.index = (f.index + 1) % f.size;
    
    return f.sum / f.count;
}

// ═══════════════════════════════════════════════════════════════════════════
// Deadzone Filter
// ═══════════════════════════════════════════════════════════════════════════

extern "C" float applyDeadzone(float value, float deadzone, float lastValue) {
    if (fabsf(value - lastValue) < deadzone) {
        return lastValue;
    }
    return value;
}

// ═══════════════════════════════════════════════════════════════════════════
// Clamp Function
// ═══════════════════════════════════════════════════════════════════════════

extern "C" float clampf(float value, float min, float max) {
    if (value < min) return min;
    if (value > max) return max;
    return value;
}
