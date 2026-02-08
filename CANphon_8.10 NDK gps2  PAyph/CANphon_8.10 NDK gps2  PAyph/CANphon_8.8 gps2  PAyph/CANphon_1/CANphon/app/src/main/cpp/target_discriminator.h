/**
 * target_discriminator.h
 * Military-Grade Target Discrimination System (C++)
 * 
 * تمييز الأهداف الحقيقية من الضوضاء
 */

#ifndef TARGET_DISCRIMINATOR_H
#define TARGET_DISCRIMINATOR_H

#include <cstdint>

#ifdef __cplusplus
extern "C" {
#endif

// Target score structure
typedef struct {
    int x, y, w, h;         // Rect: center_x, center_y, width, height
    float confidence;
    float sizeScore;
    float positionScore;
    float stabilityScore;
    float motionScore;
    float totalScore;
} TargetScore;

// Initialize discriminator
void discriminatorInit();

// Evaluate a single target and return score
float discriminatorEvaluate(
    int x, int y, int w, int h,           // Target rect
    int lastX, int lastY, int lastW, int lastH,  // Last tracked (or -1 if none)
    int imageWidth, int imageHeight
);

// Evaluate multiple targets - returns scores array
void discriminatorEvaluateMultiple(
    int* rects,             // [x1,y1,w1,h1, x2,y2,w2,h2, ...]
    int count,              // Number of targets
    int lastX, int lastY, int lastW, int lastH,
    int imageWidth, int imageHeight,
    float* outScores        // Output: score for each target
);

// Select best target index (returns -1 if none good enough)
int discriminatorSelectBest(float* scores, int count, float minScore);

// Get full score details for a target
void discriminatorGetScore(int index, TargetScore* outScore);

// Reset state
void discriminatorReset();

// Filter weak targets (returns count of valid targets)
int discriminatorFilterWeak(float* scores, int count, float minScore, int* validIndices);

#ifdef __cplusplus
}
#endif

#endif // TARGET_DISCRIMINATOR_H
