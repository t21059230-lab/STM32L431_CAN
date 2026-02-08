/**
 * object_tracker.cpp
 * Military-Grade Object Tracking System (C++)
 * 
 * نظام تتبع الأهداف المتقدم - تحويل من ObjectTracker.kt
 * يستخدم Kalman Filter و Target Discriminator
 */

#include "object_tracker.h"
#include "target_discriminator.h"
#include <cmath>
#include <cstring>
#include <android/log.h>

#define LOG_TAG "NativeTracker"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// ═══════════════════════════════════════════════════════════════════════════
// External: Kalman Filter from kalman_filter.cpp
// ═══════════════════════════════════════════════════════════════════════════

extern "C" {
    void kalmanInit(double x, double y, double processNoise, double measurementNoise);
    void kalmanPredict(double* outX, double* outY);
    void kalmanUpdate(double measuredX, double measuredY);
    void kalmanGetState(double* x, double* y, double* vx, double* vy);
    void kalmanReset();
    double kalmanGetUncertainty();
}

// ═══════════════════════════════════════════════════════════════════════════
// Tracker State
// ═══════════════════════════════════════════════════════════════════════════

#define MAX_TRACKED_OBJECTS 100

struct TrackerState {
    TrackingMode mode;
    int enablePrediction;
    
    // Image dimensions
    int imageWidth;
    int imageHeight;
    
    // Current tracking state
    int lastX, lastY, lastW, lastH;
    int predictedX, predictedY;
    float confidence;
    
    // Tracked objects
    TrackedObject objects[MAX_TRACKED_OBJECTS];
    int objectCount;
};

static TrackerState tracker = {
    .mode = TRACK_MODE_OFF,
    .enablePrediction = 0,  // Disabled by default (matching Kotlin)
    .imageWidth = 1280,
    .imageHeight = 720,
    .lastX = -1, .lastY = -1, .lastW = 0, .lastH = 0,
    .predictedX = 0, .predictedY = 0,
    .confidence = 0.0f,
    .objectCount = 0
};

// ═══════════════════════════════════════════════════════════════════════════
// Helper Functions
// ═══════════════════════════════════════════════════════════════════════════

static inline float distance(int x1, int y1, int x2, int y2) {
    float dx = (float)(x2 - x1);
    float dy = (float)(y2 - y1);
    return sqrtf(dx * dx + dy * dy);
}

// Find closest object to a given point
static int findClosest(int* rects, int count, int targetX, int targetY, float maxDist) {
    int bestIdx = -1;
    float bestDist = maxDist;
    
    for (int i = 0; i < count; i++) {
        int x = rects[i * 4 + 0];
        int y = rects[i * 4 + 1];
        
        float d = distance(x, y, targetX, targetY);
        if (d < bestDist) {
            bestDist = d;
            bestIdx = i;
        }
    }
    
    return bestIdx;
}

// ═══════════════════════════════════════════════════════════════════════════
// Main API Implementation
// ═══════════════════════════════════════════════════════════════════════════

extern "C" void trackerInit() {
    memset(&tracker, 0, sizeof(TrackerState));
    tracker.mode = TRACK_MODE_OFF;
    tracker.enablePrediction = 0;
    tracker.imageWidth = 1280;
    tracker.imageHeight = 720;
    tracker.lastX = -1;
    tracker.lastY = -1;
    
    discriminatorInit();
    LOGI("✅ Object Tracker initialized (Native C++)");
}

extern "C" void trackerStartTracking(int x, int y, int w, int h) {
    tracker.lastX = x;
    tracker.lastY = y;
    tracker.lastW = w;
    tracker.lastH = h;
    tracker.mode = TRACK_MODE_TRACK;
    tracker.confidence = 1.0f;
    
    // Initialize Kalman filter with initial position
    kalmanInit((double)x, (double)y, 300.0, 1.0);
    
    LOGI("✅ Started tracking: (%d, %d) size %dx%d", x, y, w, h);
}

extern "C" void trackerSetImageSize(int width, int height) {
    tracker.imageWidth = width;
    tracker.imageHeight = height;
}

extern "C" void trackerProcessDetections(int* rects, int count) {
    // Process and update internal tracked objects list
    // This matches the Kotlin processDetectedObjects logic
    
    for (int i = 0; i < count; i++) {
        int x = rects[i * 4 + 0];
        int y = rects[i * 4 + 1];
        int w = rects[i * 4 + 2];
        int h = rects[i * 4 + 3];
        
        // Validate coordinates
        if (w > 2 && h > 2 && 
            x > 5 && x < tracker.imageWidth && 
            y > 5 && y < tracker.imageHeight) {
            
            // Check if matches existing tracked object
            int matched = 0;
            int searchRangeX = tracker.imageWidth / 16;
            int searchRangeY = tracker.imageHeight / 16;
            
            for (int j = 0; j < tracker.objectCount; j++) {
                TrackedObject* obj = &tracker.objects[j];
                ObjectState* last = (obj->historyCount > 0) ? 
                                    &obj->history[obj->historyCount - 1] : NULL;
                
                if (last != NULL) {
                    if (x >= last->x - searchRangeX && x <= last->x + searchRangeX &&
                        y >= last->y - searchRangeY && y <= last->y + searchRangeY) {
                        
                        // Match found - update history
                        matched = 1;
                        
                        if (obj->historyCount < 100) {
                            ObjectState* newState = &obj->history[obj->historyCount++];
                            newState->x = x;
                            newState->y = y;
                            newState->w = w;
                            newState->h = h;
                            newState->status = 1;  // open
                            newState->lostCount = 0;
                        } else {
                            // Shift history
                            memmove(&obj->history[0], &obj->history[1], 
                                    99 * sizeof(ObjectState));
                            ObjectState* newState = &obj->history[99];
                            newState->x = x;
                            newState->y = y;
                            newState->w = w;
                            newState->h = h;
                            newState->status = 1;
                            newState->lostCount = 0;
                        }
                        
                        obj->current.x = x;
                        obj->current.y = y;
                        obj->current.w = w;
                        obj->current.h = h;
                        break;
                    }
                }
            }
            
            // Add new object if not matched
            if (!matched && tracker.objectCount < MAX_TRACKED_OBJECTS) {
                TrackedObject* obj = &tracker.objects[tracker.objectCount++];
                memset(obj, 0, sizeof(TrackedObject));
                obj->id = tracker.objectCount;
                obj->current.x = x;
                obj->current.y = y;
                obj->current.w = w;
                obj->current.h = h;
                obj->current.status = 1;
                obj->history[0] = obj->current;
                obj->historyCount = 1;
            }
        }
    }
    
    // Mark lost objects
    for (int i = 0; i < tracker.objectCount; i++) {
        ObjectState* last = (tracker.objects[i].historyCount > 0) ?
                            &tracker.objects[i].history[tracker.objects[i].historyCount - 1] : NULL;
        if (last != NULL) {
            if (last->status == 1) {
                last->status = 0;  // close
            } else {
                last->lostCount++;
                if (last->lostCount > 6) {
                    // Remove object
                    tracker.objects[i].historyCount = 0;
                }
            }
        }
    }
}

extern "C" int trackerUpdate(int* detectedRects, int detectedCount,
                              int* outX, int* outY, int* outW, int* outH,
                              float* outConfidence) {
    
    if (tracker.mode != TRACK_MODE_TRACK) {
        LOGW("⚠️ Not in TRACK mode");
        return 0;
    }
    
    if (tracker.lastX < 0 || tracker.lastY < 0) {
        LOGW("⚠️ No previous target position");
        return 0;
    }
    
    // 1. Get Kalman prediction
    double predX, predY;
    kalmanPredict(&predX, &predY);
    tracker.predictedX = (int)predX;
    tracker.predictedY = (int)predY;
    
    LOGD("🔮 Prediction: (%.0f, %.0f)", predX, predY);
    
    // 2. If no detections, use prediction if enabled
    if (detectedCount == 0) {
        if (!tracker.enablePrediction) {
            LOGW("⚠️ No targets - lost (prediction disabled)");
            return 0;
        }
        
        double uncertainty = kalmanGetUncertainty();
        if (uncertainty < 200.0) {
            *outX = tracker.predictedX;
            *outY = tracker.predictedY;
            *outW = tracker.lastW;
            *outH = tracker.lastH;
            tracker.lastX = tracker.predictedX;
            tracker.lastY = tracker.predictedY;
            *outConfidence = 0.5f;
            return 1;
        }
        return 0;
    }
    
    // 3. Find closest detection to prediction
    float searchRadius = (float)fmax(tracker.imageWidth / 2, tracker.imageHeight / 2);
    searchRadius = fmax(searchRadius, 500.0f);
    
    int bestIdx = -1;
    float bestDist = searchRadius;
    
    for (int i = 0; i < detectedCount; i++) {
        int x = detectedRects[i * 4 + 0];
        int y = detectedRects[i * 4 + 1];
        
        float d = distance(x, y, tracker.predictedX, tracker.predictedY);
        LOGD("🔍 Detection %d: (%d,%d) dist=%.1f", i, x, y, d);
        
        if (d < bestDist) {
            bestDist = d;
            bestIdx = i;
        }
    }
    
    // If no match in range, pick closest anyway
    if (bestIdx < 0 && detectedCount > 0) {
        LOGD("⚠️ No target in range, using closest");
        bestDist = 1e10f;
        for (int i = 0; i < detectedCount; i++) {
            int x = detectedRects[i * 4 + 0];
            int y = detectedRects[i * 4 + 1];
            float d = distance(x, y, tracker.predictedX, tracker.predictedY);
            if (d < bestDist) {
                bestDist = d;
                bestIdx = i;
            }
        }
    }
    
    // 4. Update with best match
    if (bestIdx >= 0) {
        int x = detectedRects[bestIdx * 4 + 0];
        int y = detectedRects[bestIdx * 4 + 1];
        int w = detectedRects[bestIdx * 4 + 2];
        int h = detectedRects[bestIdx * 4 + 3];
        
        // Update Kalman
        kalmanUpdate((double)x, (double)y);
        
        // Update state
        tracker.lastX = x;
        tracker.lastY = y;
        tracker.lastW = w;
        tracker.lastH = h;
        
        if (!tracker.enablePrediction) {
            // Return raw position
            *outX = x;
            *outY = y;
            *outW = w;
            *outH = h;
            LOGD("✅ Tracking (RAW): (%d,%d) dist=%.1f", x, y, bestDist);
        } else {
            // Return filtered position
            double fx, fy, vx, vy;
            kalmanGetState(&fx, &fy, &vx, &vy);
            *outX = (int)fx;
            *outY = (int)fy;
            *outW = w;
            *outH = h;
            LOGD("✅ Tracking (filtered): raw=(%d,%d) filtered=(%.0f,%.0f)", x, y, fx, fy);
        }
        
        // Calculate confidence based on distance
        tracker.confidence = 1.0f - (bestDist / searchRadius);
        tracker.confidence = fmax(0.3f, fmin(1.0f, tracker.confidence));
        *outConfidence = tracker.confidence;
        
        return 1;
    }
    
    // 5. Lost target
    if (!tracker.enablePrediction) {
        LOGW("⚠️ Target lost (prediction disabled)");
        tracker.mode = TRACK_MODE_LOST;
        return 0;
    }
    
    // Use prediction
    double uncertainty = kalmanGetUncertainty();
    LOGD("📊 Uncertainty: %.1f", uncertainty);
    
    if (tracker.lastX >= 0) {
        *outX = tracker.predictedX;
        *outY = tracker.predictedY;
        *outW = tracker.lastW;
        *outH = tracker.lastH;
        tracker.lastX = tracker.predictedX;
        tracker.lastY = tracker.predictedY;
        *outConfidence = 0.3f;
        return 1;
    }
    
    tracker.mode = TRACK_MODE_LOST;
    kalmanReset();
    return 0;
}

extern "C" void trackerGetPosition(int* x, int* y, int* w, int* h) {
    *x = tracker.lastX;
    *y = tracker.lastY;
    *w = tracker.lastW;
    *h = tracker.lastH;
}

extern "C" void trackerGetPrediction(int* x, int* y) {
    *x = tracker.predictedX;
    *y = tracker.predictedY;
}

extern "C" TrackingMode trackerGetMode() {
    return tracker.mode;
}

extern "C" float trackerGetConfidence() {
    if (tracker.mode == TRACK_MODE_TRACK && tracker.lastX >= 0) {
        double uncertainty = kalmanGetUncertainty();
        return (float)((500.0 - uncertainty) / 500.0);
    }
    return 0.0f;
}

extern "C" int trackerIsTracking() {
    return (tracker.mode == TRACK_MODE_TRACK) ? 1 : 0;
}

extern "C" void trackerReset() {
    tracker.mode = TRACK_MODE_OFF;
    tracker.lastX = -1;
    tracker.lastY = -1;
    tracker.lastW = 0;
    tracker.lastH = 0;
    tracker.objectCount = 0;
    tracker.confidence = 0.0f;
    
    kalmanReset();
    discriminatorReset();
    
    LOGI("🔄 Tracker reset");
}

extern "C" void trackerStop() {
    tracker.mode = TRACK_MODE_OFF;
    LOGI("⏹️ Tracking stopped");
}

extern "C" void trackerEnablePrediction(int enable) {
    tracker.enablePrediction = enable;
    LOGI("🔮 Prediction %s", enable ? "ENABLED" : "DISABLED");
}

extern "C" int trackerGetAllObjects(ObjectState* objects, int maxCount) {
    int count = 0;
    for (int i = 0; i < tracker.objectCount && count < maxCount; i++) {
        if (tracker.objects[i].historyCount > 0) {
            ObjectState* last = &tracker.objects[i].history[tracker.objects[i].historyCount - 1];
            if (last->status == 1 || last->lostCount < 3) {
                objects[count++] = *last;
            }
        }
    }
    return count;
}
