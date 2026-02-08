/**
 * native_sensors.cpp
 * الوصول المباشر للحساسات عبر Android NDK Sensor API
 * 
 * هذا الكود يستخدم ASensorManager للوصول المباشر للحساسات
 * بأقصى تردد ممكن (SENSOR_DELAY_FASTEST)
 */

#include <android/sensor.h>
#include <android/looper.h>
#include <android/log.h>
#include <cstring>
#include <cmath>

#define LOG_TAG "NativeSensors"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Sensor data structure
struct SensorData {
    float accel[3];      // Accelerometer X, Y, Z
    float gyro[3];       // Gyroscope X, Y, Z
    float mag[3];        // Magnetometer X, Y, Z
    int64_t timestamp;   // Timestamp in nanoseconds
    int sampleRate;      // Actual sample rate achieved
};

// Global sensor manager and sensors
static ASensorManager* sensorManager = nullptr;
static const ASensor* accelerometer = nullptr;
static const ASensor* gyroscope = nullptr;
static const ASensor* magnetometer = nullptr;
static ASensorEventQueue* eventQueue = nullptr;
static ALooper* looper = nullptr;

// Latest sensor data
static SensorData latestData;
static bool sensorsInitialized = false;

// Sample rate tracking
static int64_t lastGyroTimestamp = 0;
static int gyroSampleCount = 0;
static float measuredGyroRate = 0.0f;

/**
 * Initialize native sensor access
 * Returns: 0 on success, -1 on failure
 */
extern "C" int initNativeSensors() {
    LOGI("Initializing native sensors...");
    
    // Get sensor manager instance
    sensorManager = ASensorManager_getInstanceForPackage("com.example.canphon");
    if (!sensorManager) {
        LOGE("Failed to get sensor manager!");
        return -1;
    }
    
    // Get sensors
    accelerometer = ASensorManager_getDefaultSensor(sensorManager, ASENSOR_TYPE_ACCELEROMETER);
    gyroscope = ASensorManager_getDefaultSensor(sensorManager, ASENSOR_TYPE_GYROSCOPE);
    magnetometer = ASensorManager_getDefaultSensor(sensorManager, ASENSOR_TYPE_MAGNETIC_FIELD);
    
    if (!accelerometer || !gyroscope) {
        LOGE("Failed to get required sensors!");
        return -1;
    }
    
    // Log sensor info
    if (accelerometer) {
        LOGI("Accelerometer: %s, Min Delay: %d μs", 
             ASensor_getName(accelerometer),
             ASensor_getMinDelay(accelerometer));
    }
    if (gyroscope) {
        LOGI("Gyroscope: %s, Min Delay: %d μs (Max Rate: %.1f Hz)", 
             ASensor_getName(gyroscope),
             ASensor_getMinDelay(gyroscope),
             1000000.0f / ASensor_getMinDelay(gyroscope));
    }
    
    // Create looper
    looper = ALooper_prepare(ALOOPER_PREPARE_ALLOW_NON_CALLBACKS);
    if (!looper) {
        LOGE("Failed to create looper!");
        return -1;
    }
    
    // Create event queue
    eventQueue = ASensorManager_createEventQueue(sensorManager, looper, 
                                                  ALOOPER_POLL_CALLBACK, nullptr, nullptr);
    if (!eventQueue) {
        LOGE("Failed to create event queue!");
        return -1;
    }
    
    sensorsInitialized = true;
    LOGI("Native sensors initialized successfully!");
    return 0;
}

/**
 * Start sensor polling at maximum rate
 * usDelay: Delay in microseconds (0 = FASTEST)
 */
extern "C" int startSensors(int usDelay) {
    if (!sensorsInitialized) {
        LOGE("Sensors not initialized!");
        return -1;
    }
    
    // Use minimum delay if 0 is passed (FASTEST)
    int accelDelay = usDelay > 0 ? usDelay : ASensor_getMinDelay(accelerometer);
    int gyroDelay = usDelay > 0 ? usDelay : ASensor_getMinDelay(gyroscope);
    
    LOGI("Starting sensors with delays - Accel: %d μs, Gyro: %d μs", accelDelay, gyroDelay);
    
    // Enable sensors
    ASensorEventQueue_enableSensor(eventQueue, accelerometer);
    ASensorEventQueue_setEventRate(eventQueue, accelerometer, accelDelay);
    
    ASensorEventQueue_enableSensor(eventQueue, gyroscope);
    ASensorEventQueue_setEventRate(eventQueue, gyroscope, gyroDelay);
    
    if (magnetometer) {
        ASensorEventQueue_enableSensor(eventQueue, magnetometer);
        ASensorEventQueue_setEventRate(eventQueue, magnetometer, 10000); // 100 Hz max for mag
    }
    
    LOGI("Sensors started at maximum rate!");
    return 0;
}

/**
 * Poll sensors and get latest data
 * Returns: Number of events processed
 */
extern "C" int pollSensors(float* accel, float* gyro, float* mag, float* rate) {
    if (!sensorsInitialized || !eventQueue) {
        return -1;
    }
    
    ASensorEvent events[100];
    int eventCount = 0;
    int totalEvents = 0;
    
    // Poll all available events
    while ((eventCount = ASensorEventQueue_getEvents(eventQueue, events, 100)) > 0) {
        for (int i = 0; i < eventCount; i++) {
            switch (events[i].type) {
                case ASENSOR_TYPE_ACCELEROMETER:
                    latestData.accel[0] = events[i].acceleration.x;
                    latestData.accel[1] = events[i].acceleration.y;
                    latestData.accel[2] = events[i].acceleration.z;
                    break;
                    
                case ASENSOR_TYPE_GYROSCOPE:
                    latestData.gyro[0] = events[i].uncalibrated_gyro.x_uncalib;
                    latestData.gyro[1] = events[i].uncalibrated_gyro.y_uncalib;
                    latestData.gyro[2] = events[i].uncalibrated_gyro.z_uncalib;
                    latestData.timestamp = events[i].timestamp;
                    
                    // Calculate actual sample rate
                    if (lastGyroTimestamp > 0) {
                        int64_t deltaT = events[i].timestamp - lastGyroTimestamp;
                        if (deltaT > 0) {
                            float instantRate = 1000000000.0f / deltaT;
                            measuredGyroRate = measuredGyroRate * 0.9f + instantRate * 0.1f;
                        }
                    }
                    lastGyroTimestamp = events[i].timestamp;
                    gyroSampleCount++;
                    break;
                    
                case ASENSOR_TYPE_MAGNETIC_FIELD:
                    latestData.mag[0] = events[i].magnetic.x;
                    latestData.mag[1] = events[i].magnetic.y;
                    latestData.mag[2] = events[i].magnetic.z;
                    break;
            }
        }
        totalEvents += eventCount;
    }
    
    // Copy data to output
    if (accel) {
        memcpy(accel, latestData.accel, sizeof(float) * 3);
    }
    if (gyro) {
        memcpy(gyro, latestData.gyro, sizeof(float) * 3);
    }
    if (mag) {
        memcpy(mag, latestData.mag, sizeof(float) * 3);
    }
    if (rate) {
        *rate = measuredGyroRate;
    }
    
    return totalEvents;
}

/**
 * Stop sensors
 */
extern "C" void stopSensors() {
    if (eventQueue) {
        if (accelerometer) ASensorEventQueue_disableSensor(eventQueue, accelerometer);
        if (gyroscope) ASensorEventQueue_disableSensor(eventQueue, gyroscope);
        if (magnetometer) ASensorEventQueue_disableSensor(eventQueue, magnetometer);
    }
    LOGI("Sensors stopped. Total gyro samples: %d, Avg rate: %.1f Hz", 
         gyroSampleCount, measuredGyroRate);
}

/**
 * Cleanup
 */
extern "C" void cleanupNativeSensors() {
    stopSensors();
    if (eventQueue && sensorManager) {
        ASensorManager_destroyEventQueue(sensorManager, eventQueue);
        eventQueue = nullptr;
    }
    sensorsInitialized = false;
    LOGI("Native sensors cleaned up");
}

/**
 * Get maximum possible sensor rate
 */
extern "C" float getMaxSensorRate(int sensorType) {
    if (!sensorManager) return 0.0f;
    
    const ASensor* sensor = nullptr;
    switch (sensorType) {
        case ASENSOR_TYPE_ACCELEROMETER:
            sensor = accelerometer;
            break;
        case ASENSOR_TYPE_GYROSCOPE:
            sensor = gyroscope;
            break;
        case ASENSOR_TYPE_MAGNETIC_FIELD:
            sensor = magnetometer;
            break;
    }
    
    if (!sensor) return 0.0f;
    
    int minDelay = ASensor_getMinDelay(sensor);
    if (minDelay <= 0) return 0.0f;
    
    return 1000000.0f / minDelay;
}

/**
 * Get current measured rate
 */
extern "C" float getMeasuredRate() {
    return measuredGyroRate;
}
