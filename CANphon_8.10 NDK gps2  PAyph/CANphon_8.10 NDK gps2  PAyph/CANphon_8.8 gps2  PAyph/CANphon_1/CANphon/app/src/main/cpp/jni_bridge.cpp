/**
 * jni_bridge.cpp
 * JNI Bridge - الجسر الكامل بين Kotlin و C++
 * 
 * يربط جميع الدوال الأصلية (Native) مع Kotlin
 */

#include <jni.h>
#include <android/log.h>
#include <cstring>

#define LOG_TAG "JNI_Bridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// ═══════════════════════════════════════════════════════════════════════════
// External declarations from all cpp files
// ═══════════════════════════════════════════════════════════════════════════

// native_sensors.cpp
extern "C" {
    int initNativeSensors();
    int startSensors(int usDelay);
    int pollSensors(float* accel, float* gyro, float* mag, float* rate);
    void stopSensors();
    void cleanupNativeSensors();
    float getMaxSensorRate(int sensorType);
    float getMeasuredRate();
}

// kalman_filter.cpp
extern "C" {
    void kalmanInit(double x, double y, double processNoise, double measurementNoise);
    void kalmanPredict(double* outX, double* outY);
    void kalmanUpdate(double measuredX, double measuredY);
    void kalmanGetState(double* x, double* y, double* vx, double* vy);
    void kalmanPredictFuture(int steps, double* outX, double* outY);
    void kalmanReset();
    double kalmanGetUncertainty();
}

// filters.cpp
extern "C" {
    void iirInit(int id, float alpha);
    float iirUpdate(int id, float input);
    float iirGet(int id);
    void iirReset(int id);
    void vecFilterInit(int id, float alpha);
    void vecFilterUpdate(int id, float inX, float inY, float inZ, float* outX, float* outY, float* outZ);
    void maInit(int id, int size);
    float maUpdate(int id, float input);
    float applyDeadzone(float value, float deadzone, float lastValue);
    float clampf(float value, float min, float max);
}

// guidance_controller.cpp
extern "C" {
    void guidanceInit(float alpha, float cmdMax);
    void guidanceStart();
    void guidanceStop();
    void guidanceUpdate(float errorX, float errorY, float dt);
    void guidanceGetCommands(float* pitch, float* yaw);
    void guidanceGetServoAngles(float* angles);
    void pidInit(int axis, float kp, float ki, float kd, float outputMin, float outputMax, float alpha);
    float pidUpdate(int axis, float error, float dt);
    void pidReset(int axis);
    void fusionInit(float alpha);
    void fusionUpdateGps(double lat, double lon, double alt, int64_t timestamp);
    void fusionIntegrateImu(float accelN, float accelE, float accelD, float dt);
    void fusionGetPosition(double* lat, double* lon, double* alt);
    void fusionGetVelocity(double* velN, double* velE, double* velD);
    bool fusionHasFix();
}

// ═══════════════════════════════════════════════════════════════════════════
// NativeSensorManager JNI (existing)
// ═══════════════════════════════════════════════════════════════════════════

extern "C" JNIEXPORT jint JNICALL
Java_com_example_canphon_native_1sensors_NativeSensorManager_initNative(JNIEnv* env, jobject) {
    return initNativeSensors();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_canphon_native_1sensors_NativeSensorManager_startNative(JNIEnv* env, jobject, jint usDelay) {
    return startSensors(usDelay);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_canphon_native_1sensors_NativeSensorManager_pollNative(JNIEnv* env, jobject) {
    float accel[3], gyro[3], mag[3], rate;
    int events = pollSensors(accel, gyro, mag, &rate);
    jfloatArray result = env->NewFloatArray(8);
    float output[8] = {accel[0], accel[1], accel[2], gyro[0], gyro[1], gyro[2], rate, (float)events};
    env->SetFloatArrayRegion(result, 0, 8, output);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_canphon_native_1sensors_NativeSensorManager_stopNative(JNIEnv* env, jobject) {
    stopSensors();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_canphon_native_1sensors_NativeSensorManager_cleanupNative(JNIEnv* env, jobject) {
    cleanupNativeSensors();
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_example_canphon_native_1sensors_NativeSensorManager_getMaxRateNative(JNIEnv* env, jobject, jint type) {
    return getMaxSensorRate(type);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_example_canphon_native_1sensors_NativeSensorManager_getMeasuredRateNative(JNIEnv* env, jobject) {
    return getMeasuredRate();
}

// ═══════════════════════════════════════════════════════════════════════════
// NativeCore JNI - Kalman Filter
// ═══════════════════════════════════════════════════════════════════════════

extern "C" JNIEXPORT void JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_kalmanInit(JNIEnv* env, jobject, jdouble x, jdouble y, jdouble pn, jdouble mn) {
    kalmanInit(x, y, pn, mn);
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_kalmanPredict(JNIEnv* env, jobject) {
    double x, y;
    kalmanPredict(&x, &y);
    jdoubleArray result = env->NewDoubleArray(2);
    double out[2] = {x, y};
    env->SetDoubleArrayRegion(result, 0, 2, out);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_kalmanUpdate(JNIEnv* env, jobject, jdouble mx, jdouble my) {
    kalmanUpdate(mx, my);
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_kalmanGetState(JNIEnv* env, jobject) {
    double x, y, vx, vy;
    kalmanGetState(&x, &y, &vx, &vy);
    jdoubleArray result = env->NewDoubleArray(4);
    double out[4] = {x, y, vx, vy};
    env->SetDoubleArrayRegion(result, 0, 4, out);
    return result;
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_kalmanPredictFuture(JNIEnv* env, jobject, jint steps) {
    double x, y;
    kalmanPredictFuture(steps, &x, &y);
    jdoubleArray result = env->NewDoubleArray(2);
    double out[2] = {x, y};
    env->SetDoubleArrayRegion(result, 0, 2, out);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_kalmanReset(JNIEnv* env, jobject) {
    kalmanReset();
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_kalmanGetUncertainty(JNIEnv* env, jobject) {
    return kalmanGetUncertainty();
}

// ═══════════════════════════════════════════════════════════════════════════
// NativeCore JNI - Filters
// ═══════════════════════════════════════════════════════════════════════════

extern "C" JNIEXPORT void JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_iirInit(JNIEnv* env, jobject, jint id, jfloat alpha) {
    iirInit(id, alpha);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_iirUpdate(JNIEnv* env, jobject, jint id, jfloat input) {
    return iirUpdate(id, input);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_iirGet(JNIEnv* env, jobject, jint id) {
    return iirGet(id);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_iirReset(JNIEnv* env, jobject, jint id) {
    iirReset(id);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_vecFilterInit(JNIEnv* env, jobject, jint id, jfloat alpha) {
    vecFilterInit(id, alpha);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_vecFilterUpdate(JNIEnv* env, jobject, jint id, jfloat x, jfloat y, jfloat z) {
    float ox, oy, oz;
    vecFilterUpdate(id, x, y, z, &ox, &oy, &oz);
    jfloatArray result = env->NewFloatArray(3);
    float out[3] = {ox, oy, oz};
    env->SetFloatArrayRegion(result, 0, 3, out);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_maInit(JNIEnv* env, jobject, jint id, jint size) {
    maInit(id, size);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_maUpdate(JNIEnv* env, jobject, jint id, jfloat input) {
    return maUpdate(id, input);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_applyDeadzone(JNIEnv* env, jobject, jfloat v, jfloat dz, jfloat last) {
    return applyDeadzone(v, dz, last);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_clampf(JNIEnv* env, jobject, jfloat v, jfloat min, jfloat max) {
    return clampf(v, min, max);
}

// ═══════════════════════════════════════════════════════════════════════════
// NativeCore JNI - Guidance Controller & PID
// ═══════════════════════════════════════════════════════════════════════════

extern "C" JNIEXPORT void JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_guidanceInit(JNIEnv* env, jobject, jfloat alpha, jfloat cmdMax) {
    guidanceInit(alpha, cmdMax);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_guidanceStart(JNIEnv* env, jobject) {
    guidanceStart();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_guidanceStop(JNIEnv* env, jobject) {
    guidanceStop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_guidanceUpdate(JNIEnv* env, jobject, jfloat ex, jfloat ey, jfloat dt) {
    guidanceUpdate(ex, ey, dt);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_guidanceGetCommands(JNIEnv* env, jobject) {
    float pitch, yaw;
    guidanceGetCommands(&pitch, &yaw);
    jfloatArray result = env->NewFloatArray(2);
    float out[2] = {pitch, yaw};
    env->SetFloatArrayRegion(result, 0, 2, out);
    return result;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_guidanceGetServoAngles(JNIEnv* env, jobject) {
    float angles[4];
    guidanceGetServoAngles(angles);
    jfloatArray result = env->NewFloatArray(4);
    env->SetFloatArrayRegion(result, 0, 4, angles);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_pidInit(JNIEnv* env, jobject, jint axis, jfloat kp, jfloat ki, jfloat kd, jfloat omin, jfloat omax, jfloat alpha) {
    pidInit(axis, kp, ki, kd, omin, omax, alpha);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_pidUpdate(JNIEnv* env, jobject, jint axis, jfloat error, jfloat dt) {
    return pidUpdate(axis, error, dt);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_pidReset(JNIEnv* env, jobject, jint axis) {
    pidReset(axis);
}

// ═══════════════════════════════════════════════════════════════════════════
// NativeCore JNI - Sensor Fusion
// ═══════════════════════════════════════════════════════════════════════════

extern "C" JNIEXPORT void JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_fusionInit(JNIEnv* env, jobject, jfloat alpha) {
    fusionInit(alpha);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_fusionUpdateGps(JNIEnv* env, jobject, jdouble lat, jdouble lon, jdouble alt, jlong ts) {
    fusionUpdateGps(lat, lon, alt, ts);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_fusionIntegrateImu(JNIEnv* env, jobject, jfloat an, jfloat ae, jfloat ad, jfloat dt) {
    fusionIntegrateImu(an, ae, ad, dt);
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_fusionGetPosition(JNIEnv* env, jobject) {
    double lat, lon, alt;
    fusionGetPosition(&lat, &lon, &alt);
    jdoubleArray result = env->NewDoubleArray(3);
    double out[3] = {lat, lon, alt};
    env->SetDoubleArrayRegion(result, 0, 3, out);
    return result;
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_fusionGetVelocity(JNIEnv* env, jobject) {
    double vn, ve, vd;
    fusionGetVelocity(&vn, &ve, &vd);
    jdoubleArray result = env->NewDoubleArray(3);
    double out[3] = {vn, ve, vd};
    env->SetDoubleArrayRegion(result, 0, 3, out);
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_fusionHasFix(JNIEnv* env, jobject) {
    return fusionHasFix() ? JNI_TRUE : JNI_FALSE;
}

// ═══════════════════════════════════════════════════════════════════════════
// NativeCore JNI - Object Tracker (Phase 1: Native Tracking)
// ═══════════════════════════════════════════════════════════════════════════

// object_tracker.cpp
extern "C" {
    void trackerInit();
    void trackerStartTracking(int x, int y, int w, int h);
    void trackerSetImageSize(int width, int height);
    int trackerUpdate(int* detectedRects, int detectedCount,
                      int* outX, int* outY, int* outW, int* outH, float* outConfidence);
    void trackerGetPosition(int* x, int* y, int* w, int* h);
    void trackerGetPrediction(int* x, int* y);
    int trackerGetMode();
    float trackerGetConfidence();
    int trackerIsTracking();
    void trackerReset();
    void trackerStop();
    void trackerEnablePrediction(int enable);
}

// target_discriminator.cpp
extern "C" {
    void discriminatorInit();
    float discriminatorEvaluate(int x, int y, int w, int h, int lastX, int lastY, int lastW, int lastH, int imageWidth, int imageHeight);
    void discriminatorEvaluateMultiple(int* rects, int count, int lastX, int lastY, int lastW, int lastH, int imageWidth, int imageHeight, float* outScores);
    int discriminatorSelectBest(float* scores, int count, float minScore);
    void discriminatorReset();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_trackerInit(JNIEnv* env, jobject) {
    trackerInit();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_trackerStart(JNIEnv* env, jobject, jint x, jint y, jint w, jint h) {
    trackerStartTracking(x, y, w, h);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_trackerSetImageSize(JNIEnv* env, jobject, jint w, jint h) {
    trackerSetImageSize(w, h);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_trackerUpdate(JNIEnv* env, jobject, jintArray detections) {
    // Get detection count
    jsize len = env->GetArrayLength(detections);
    int count = len / 4;
    
    // Get detection data
    jint* rects = env->GetIntArrayElements(detections, NULL);
    
    // Update tracker
    int outX, outY, outW, outH;
    float confidence;
    int found = trackerUpdate((int*)rects, count, &outX, &outY, &outW, &outH, &confidence);
    
    env->ReleaseIntArrayElements(detections, rects, 0);
    
    // Return [found, x, y, w, h, confidence*100]
    jintArray result = env->NewIntArray(6);
    jint out[6] = {found, outX, outY, outW, outH, (int)(confidence * 100)};
    env->SetIntArrayRegion(result, 0, 6, out);
    return result;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_trackerGetPosition(JNIEnv* env, jobject) {
    int x, y, w, h;
    trackerGetPosition(&x, &y, &w, &h);
    jintArray result = env->NewIntArray(4);
    jint out[4] = {x, y, w, h};
    env->SetIntArrayRegion(result, 0, 4, out);
    return result;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_trackerGetPrediction(JNIEnv* env, jobject) {
    int x, y;
    trackerGetPrediction(&x, &y);
    jintArray result = env->NewIntArray(2);
    jint out[2] = {x, y};
    env->SetIntArrayRegion(result, 0, 2, out);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_trackerGetMode(JNIEnv* env, jobject) {
    return trackerGetMode();
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_trackerGetConfidence(JNIEnv* env, jobject) {
    return trackerGetConfidence();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_trackerIsTracking(JNIEnv* env, jobject) {
    return trackerIsTracking() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_trackerReset(JNIEnv* env, jobject) {
    trackerReset();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_trackerStop(JNIEnv* env, jobject) {
    trackerStop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_trackerEnablePrediction(JNIEnv* env, jobject, jboolean enable) {
    trackerEnablePrediction(enable ? 1 : 0);
}

// Target Discriminator JNI

extern "C" JNIEXPORT void JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_discriminatorInit(JNIEnv* env, jobject) {
    discriminatorInit();
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_discriminatorEvaluate(JNIEnv* env, jobject,
    jint x, jint y, jint w, jint h, jint lastX, jint lastY, jint lastW, jint lastH, jint imgW, jint imgH) {
    return discriminatorEvaluate(x, y, w, h, lastX, lastY, lastW, lastH, imgW, imgH);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_discriminatorEvaluateMultiple(JNIEnv* env, jobject,
    jintArray rects, jint lastX, jint lastY, jint lastW, jint lastH, jint imgW, jint imgH) {
    
    jsize len = env->GetArrayLength(rects);
    int count = len / 4;
    
    jint* rectData = env->GetIntArrayElements(rects, NULL);
    
    float* scores = new float[count];
    discriminatorEvaluateMultiple((int*)rectData, count, lastX, lastY, lastW, lastH, imgW, imgH, scores);
    
    env->ReleaseIntArrayElements(rects, rectData, 0);
    
    jfloatArray result = env->NewFloatArray(count);
    env->SetFloatArrayRegion(result, 0, count, scores);
    
    delete[] scores;
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_discriminatorSelectBest(JNIEnv* env, jobject,
    jfloatArray scores, jfloat minScore) {
    
    jsize count = env->GetArrayLength(scores);
    jfloat* scoreData = env->GetFloatArrayElements(scores, NULL);
    
    int best = discriminatorSelectBest(scoreData, count, minScore);
    
    env->ReleaseFloatArrayElements(scores, scoreData, 0);
    return best;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_discriminatorReset(JNIEnv* env, jobject) {
    discriminatorReset();
}

// ═══════════════════════════════════════════════════════════════════════════
// NativeCore JNI - Telemetry (Phase 2)
// ═══════════════════════════════════════════════════════════════════════════

// telemetry.cpp
extern "C" {
    void telemetryInit();
    void telemetrySetTimestamp(uint32_t ts);
    void telemetrySetOrientation(float roll, float pitch, float yaw);
    void telemetrySetAccelerometer(float x, float y, float z);
    void telemetrySetPressure(float pressureHpa, float altitudeM);
    void telemetrySetGPS(double lat, double lon, float alt, float speed, float heading,
                         int satellites, int fix, float hdop);
    void telemetrySetServoCmd(float s1, float s2, float s3, float s4);
    void telemetrySetServoFb(float s1, float s2, float s3, float s4);
    void telemetrySetServoStatus(int online);
    void telemetrySetTracking(int x, int y, int w, int h);
    void telemetrySetBattery(int percent, int charging, int voltageMv);
    void telemetrySetTemperature(float tempC);
    int telemetryBuildFrame(uint8_t* outBuffer, int maxLen);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_telemetryInit(JNIEnv* env, jobject) {
    telemetryInit();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_telemetrySetOrientation(JNIEnv* env, jobject, jfloat r, jfloat p, jfloat y) {
    telemetrySetOrientation(r, p, y);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_telemetrySetAccelerometer(JNIEnv* env, jobject, jfloat x, jfloat y, jfloat z) {
    telemetrySetAccelerometer(x, y, z);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_telemetrySetGPS(JNIEnv* env, jobject,
    jdouble lat, jdouble lon, jfloat alt, jfloat spd, jfloat hdg, jint sats, jint fix, jfloat hdop) {
    telemetrySetGPS(lat, lon, alt, spd, hdg, sats, fix, hdop);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_telemetrySetServoCmd(JNIEnv* env, jobject, jfloat s1, jfloat s2, jfloat s3, jfloat s4) {
    telemetrySetServoCmd(s1, s2, s3, s4);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_telemetrySetServoFb(JNIEnv* env, jobject, jfloat s1, jfloat s2, jfloat s3, jfloat s4) {
    telemetrySetServoFb(s1, s2, s3, s4);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_telemetrySetServoStatus(JNIEnv* env, jobject, jint online) {
    telemetrySetServoStatus(online);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_telemetrySetTracking(JNIEnv* env, jobject, jint x, jint y, jint w, jint h) {
    telemetrySetTracking(x, y, w, h);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_telemetrySetBattery(JNIEnv* env, jobject, jint pct, jint chg, jint mv) {
    telemetrySetBattery(pct, chg, mv);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_telemetryBuildFrame(JNIEnv* env, jobject) {
    uint8_t buffer[128];
    int len = telemetryBuildFrame(buffer, 128);
    
    if (len <= 0) {
        return env->NewByteArray(0);
    }
    
    jbyteArray result = env->NewByteArray(len);
    env->SetByteArrayRegion(result, 0, len, (jbyte*)buffer);
    return result;
}

// ═══════════════════════════════════════════════════════════════════════════
// NativeCore JNI - Servo Protocol (Phase 3)
// ═══════════════════════════════════════════════════════════════════════════

// servo_protocol.cpp
extern "C" {
    int servoFormatCommand(int servoId, float angleDegrees, uint8_t* outBuffer);
    int servoFormatFeedbackRequest(int servoId, uint8_t* outBuffer);
    int servoParseFeedback(const uint8_t* data, int length, void* outFeedback);
    int servoAngleToPosition(float angleDegrees);
    float servoPositionToAngle(int position);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_servoFormatCommand(JNIEnv* env, jobject, jint id, jfloat angle) {
    uint8_t buffer[8];
    int len = servoFormatCommand(id, angle, buffer);
    
    jbyteArray result = env->NewByteArray(len);
    env->SetByteArrayRegion(result, 0, len, (jbyte*)buffer);
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_servoFormatFeedbackRequest(JNIEnv* env, jobject, jint id) {
    uint8_t buffer[8];
    int len = servoFormatFeedbackRequest(id, buffer);
    
    jbyteArray result = env->NewByteArray(len);
    env->SetByteArrayRegion(result, 0, len, (jbyte*)buffer);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_servoAngleToPosition(JNIEnv* env, jobject, jfloat angle) {
    return servoAngleToPosition(angle);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_example_canphon_native_1sensors_NativeCore_servoPositionToAngle(JNIEnv* env, jobject, jint position) {
    return servoPositionToAngle(position);
}
