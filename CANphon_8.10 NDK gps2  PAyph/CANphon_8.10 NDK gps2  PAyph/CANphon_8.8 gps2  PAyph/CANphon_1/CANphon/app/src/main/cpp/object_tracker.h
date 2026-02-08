/**
 * object_tracker.h
 * Military-Grade Object Tracking System (C++)
 * 
 * نظام تتبع الأهداف المتقدم
 */

#ifndef OBJECT_TRACKER_H
#define OBJECT_TRACKER_H

#include <cstdint>

#ifdef __cplusplus
extern "C" {
#endif

// Tracking modes
typedef enum {
    TRACK_MODE_OFF = 0,
    TRACK_MODE_SEARCH = 1,
    TRACK_MODE_TRACK = 2,
    TRACK_MODE_LOST = 3
} TrackingMode;

// Object state structure
typedef struct {
    int x, y;      // Center position
    int w, h;      // Width, height
    int lostCount; // Frames since last seen
    int status;    // 0=close, 1=open
} ObjectState;

// Tracked object with history
typedef struct {
    int id;
    ObjectState current;
    ObjectState history[100];
    int historyCount;
    float confidence;
} TrackedObject;

// Initialize tracker
void trackerInit();

// Start tracking a specific target
void trackerStartTracking(int x, int y, int w, int h);

// Set image dimensions
void trackerSetImageSize(int width, int height);

// Process detected objects (from YOLO/detector)
// rects: [x1,y1,w1,h1, x2,y2,w2,h2, ...] - center positions
void trackerProcessDetections(int* rects, int count);

// Update tracking with detections
// Returns 1 if target found, 0 if lost
int trackerUpdate(int* detectedRects, int detectedCount, 
                  int* outX, int* outY, int* outW, int* outH,
                  float* outConfidence);

// Get current tracked position
void trackerGetPosition(int* x, int* y, int* w, int* h);

// Get predicted position (from Kalman filter)
void trackerGetPrediction(int* x, int* y);

// Get tracking state
TrackingMode trackerGetMode();

// Get confidence (0.0 - 1.0)
float trackerGetConfidence();

// Is currently tracking?
int trackerIsTracking();

// Reset tracker
void trackerReset();

// Stop tracking
void trackerStop();

// Enable/disable Kalman prediction
void trackerEnablePrediction(int enable);

// Get all tracked objects (for multi-target display)
int trackerGetAllObjects(ObjectState* objects, int maxCount);

#ifdef __cplusplus
}
#endif

#endif // OBJECT_TRACKER_H
