/**
 * target_discriminator.cpp
 * Military-Grade Target Discrimination System (C++)
 * 
 * يستخدم في أنظمة السيكر العسكرية لتمييز الأهداف الحقيقية من الضوضاء
 * Converted from Kotlin TargetDiscriminator.kt
 */

#include "target_discriminator.h"
#include <cmath>
#include <cstring>
#include <android/log.h>

#define LOG_TAG "NativeDiscriminator"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// ═══════════════════════════════════════════════════════════════════════════
// Configuration (matching Kotlin values)
// ═══════════════════════════════════════════════════════════════════════════

static const int MIN_SIZE = 20;
static const int MAX_SIZE = 500;
static const float MIN_ASPECT_RATIO = 0.3f;
static const float MAX_ASPECT_RATIO = 3.0f;
static const int STABILITY_FRAMES = 3;

// ═══════════════════════════════════════════════════════════════════════════
// Target History (for stability calculation)
// ═══════════════════════════════════════════════════════════════════════════

#define MAX_HISTORY_TARGETS 32
#define MAX_HISTORY_FRAMES 5

struct TargetHistory {
    int centerX[MAX_HISTORY_FRAMES];
    int centerY[MAX_HISTORY_FRAMES];
    int count;
    int hash;  // Simple identifier
};

static TargetHistory historyBuffer[MAX_HISTORY_TARGETS];
static int historyCount = 0;

// Last evaluated scores (for detailed retrieval)
static TargetScore lastScores[32];
static int lastScoreCount = 0;

// ═══════════════════════════════════════════════════════════════════════════
// Helper Functions
// ═══════════════════════════════════════════════════════════════════════════

static inline float clamp(float val, float minVal, float maxVal) {
    if (val < minVal) return minVal;
    if (val > maxVal) return maxVal;
    return val;
}

static inline float absf(float x) {
    return x < 0 ? -x : x;
}

// Simple hash for rect (for history tracking)
static int rectHash(int x, int y, int w, int h) {
    // Quantize to grid cells for approximate matching
    return ((x / 20) * 1000000) + ((y / 20) * 1000) + ((w + h) / 10);
}

// Find or create history entry for a target
static TargetHistory* getHistory(int hash) {
    // Search existing
    for (int i = 0; i < historyCount; i++) {
        if (historyBuffer[i].hash == hash) {
            return &historyBuffer[i];
        }
    }
    
    // Create new if space available
    if (historyCount < MAX_HISTORY_TARGETS) {
        TargetHistory* h = &historyBuffer[historyCount++];
        memset(h, 0, sizeof(TargetHistory));
        h->hash = hash;
        return h;
    }
    
    // Overwrite oldest if full
    return &historyBuffer[0];
}

// Add point to history
static void addToHistory(TargetHistory* h, int centerX, int centerY) {
    if (h->count < MAX_HISTORY_FRAMES) {
        h->centerX[h->count] = centerX;
        h->centerY[h->count] = centerY;
        h->count++;
    } else {
        // Shift left and add new
        for (int i = 0; i < MAX_HISTORY_FRAMES - 1; i++) {
            h->centerX[i] = h->centerX[i + 1];
            h->centerY[i] = h->centerY[i + 1];
        }
        h->centerX[MAX_HISTORY_FRAMES - 1] = centerX;
        h->centerY[MAX_HISTORY_FRAMES - 1] = centerY;
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Main API
// ═══════════════════════════════════════════════════════════════════════════

extern "C" void discriminatorInit() {
    historyCount = 0;
    lastScoreCount = 0;
    memset(historyBuffer, 0, sizeof(historyBuffer));
    LOGI("✅ Target Discriminator initialized");
}

extern "C" float discriminatorEvaluate(
    int x, int y, int w, int h,
    int lastX, int lastY, int lastW, int lastH,
    int imageWidth, int imageHeight
) {
    int area = w * h;
    int centerX = x;
    int centerY = y;
    
    // 1. SIZE SCORE - Medium-sized targets are preferred
    float sizeScore;
    int minArea = MIN_SIZE * MIN_SIZE;
    int maxArea = MAX_SIZE * MAX_SIZE;
    
    if (area < minArea) {
        sizeScore = 0.0f;  // Too small
    } else if (area > maxArea) {
        sizeScore = 0.3f;  // Too large
    } else {
        float normalizedSize = (float)(area - minArea) / (float)(maxArea - minArea);
        sizeScore = 1.0f - absf(normalizedSize - 0.5f) * 2.0f;  // Best in middle
    }
    
    // 2. ASPECT RATIO SCORE
    float aspectRatio = (h > 0) ? (float)w / (float)h : 1.0f;
    float aspectScore;
    
    if (aspectRatio < MIN_ASPECT_RATIO || aspectRatio > MAX_ASPECT_RATIO) {
        aspectScore = 0.2f;
    } else if (aspectRatio >= 0.8f && aspectRatio <= 1.2f) {
        aspectScore = 1.0f;  // Square-ish (preferred for tanks/vehicles)
    } else {
        aspectScore = 0.7f;
    }
    
    // 3. POSITION SCORE - Centered targets are better
    float dx = (float)(centerX - imageWidth / 2);
    float dy = (float)(centerY - imageHeight / 2);
    float centerDistance = sqrtf(dx * dx + dy * dy);
    float maxDistance = sqrtf((float)(imageWidth / 2) * (imageWidth / 2) + 
                               (float)(imageHeight / 2) * (imageHeight / 2));
    float positionScore = clamp(1.0f - (centerDistance / maxDistance), 0.0f, 1.0f);
    
    // 4. STABILITY SCORE - Stable targets are better
    int hash = rectHash(x, y, w, h);
    TargetHistory* hist = getHistory(hash);
    addToHistory(hist, centerX, centerY);
    
    float stabilityScore;
    if (hist->count >= STABILITY_FRAMES) {
        // Calculate variance
        float avgX = 0, avgY = 0;
        for (int i = 0; i < hist->count; i++) {
            avgX += hist->centerX[i];
            avgY += hist->centerY[i];
        }
        avgX /= hist->count;
        avgY /= hist->count;
        
        float variance = 0;
        for (int i = 0; i < hist->count; i++) {
            float dxi = hist->centerX[i] - avgX;
            float dyi = hist->centerY[i] - avgY;
            variance += sqrtf(dxi * dxi + dyi * dyi);
        }
        variance /= hist->count;
        
        // Lower variance = higher score
        stabilityScore = 1.0f - clamp(variance / 50.0f, 0.0f, 1.0f);
    } else {
        stabilityScore = 0.3f;  // Not stable yet
    }
    
    // 5. MOTION SCORE - Targets close to last tracked are better
    float motionScore;
    if (lastX >= 0 && lastY >= 0) {
        float dxm = (float)(centerX - lastX);
        float dym = (float)(centerY - lastY);
        float distance = sqrtf(dxm * dxm + dym * dym);
        float maxMotion = sqrtf((float)(imageWidth / 4) * (imageWidth / 4) + 
                                 (float)(imageHeight / 4) * (imageHeight / 4));
        motionScore = clamp(1.0f - (distance / maxMotion), 0.0f, 1.0f);
    } else {
        motionScore = 0.5f;  // No previous target
    }
    
    // 6. CONFIDENCE (default - could be enhanced from YOLO)
    float confidence = 0.8f;
    
    // Calculate weighted total score
    float totalScore = (
        sizeScore * 0.20f +
        aspectScore * 0.15f +
        positionScore * 0.15f +
        stabilityScore * 0.25f +
        motionScore * 0.15f +
        confidence * 0.10f
    );
    
    // Store for later retrieval
    if (lastScoreCount < 32) {
        TargetScore* s = &lastScores[lastScoreCount++];
        s->x = x; s->y = y; s->w = w; s->h = h;
        s->confidence = confidence;
        s->sizeScore = sizeScore;
        s->positionScore = positionScore;
        s->stabilityScore = stabilityScore;
        s->motionScore = motionScore;
        s->totalScore = totalScore;
    }
    
    LOGD("🎯 Eval: size=%.2f, aspect=%.2f, pos=%.2f, stab=%.2f, motion=%.2f → total=%.2f",
         sizeScore, aspectScore, positionScore, stabilityScore, motionScore, totalScore);
    
    return totalScore;
}

extern "C" void discriminatorEvaluateMultiple(
    int* rects,
    int count,
    int lastX, int lastY, int lastW, int lastH,
    int imageWidth, int imageHeight,
    float* outScores
) {
    lastScoreCount = 0;  // Reset for new batch
    
    for (int i = 0; i < count; i++) {
        int x = rects[i * 4 + 0];
        int y = rects[i * 4 + 1];
        int w = rects[i * 4 + 2];
        int h = rects[i * 4 + 3];
        
        outScores[i] = discriminatorEvaluate(
            x, y, w, h,
            lastX, lastY, lastW, lastH,
            imageWidth, imageHeight
        );
    }
}

extern "C" int discriminatorSelectBest(float* scores, int count, float minScore) {
    int bestIdx = -1;
    float bestScore = minScore;
    
    for (int i = 0; i < count; i++) {
        if (scores[i] > bestScore) {
            bestScore = scores[i];
            bestIdx = i;
        }
    }
    
    return bestIdx;
}

extern "C" void discriminatorGetScore(int index, TargetScore* outScore) {
    if (index >= 0 && index < lastScoreCount) {
        *outScore = lastScores[index];
    }
}

extern "C" void discriminatorReset() {
    historyCount = 0;
    lastScoreCount = 0;
    memset(historyBuffer, 0, sizeof(historyBuffer));
    LOGI("🔄 Target Discriminator reset");
}

extern "C" int discriminatorFilterWeak(float* scores, int count, float minScore, int* validIndices) {
    int validCount = 0;
    for (int i = 0; i < count; i++) {
        if (scores[i] >= minScore) {
            validIndices[validCount++] = i;
        }
    }
    return validCount;
}
