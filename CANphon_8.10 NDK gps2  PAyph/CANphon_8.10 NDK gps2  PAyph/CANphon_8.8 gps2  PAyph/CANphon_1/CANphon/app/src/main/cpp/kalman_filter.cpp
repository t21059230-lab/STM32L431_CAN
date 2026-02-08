/**
 * kalman_filter.cpp
 * Kalman Filter عالي الأداء للتتبع
 * 
 * يستخدم للتنبؤ بموقع الهدف وتصفية الضوضاء
 * محسّن لتتبع الأهداف فائقة السرعة (صواريخ، طائرات)
 */

#include <cmath>
#include <cstring>
#include <android/log.h>

#define LOG_TAG "NativeKalman"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// ═══════════════════════════════════════════════════════════════════════════
// Kalman Filter State
// ═══════════════════════════════════════════════════════════════════════════

struct KalmanState {
    // State vector: [x, y, vx, vy]
    double state[4];
    
    // Covariance matrix (4x4)
    double P[4][4];
    
    // Process noise
    double processNoise;
    
    // Measurement noise
    double measurementNoise;
    
    // Initialized flag
    bool initialized;
};

static KalmanState kalman = {0};

// State transition matrix F (constant velocity model)
static const double F[4][4] = {
    {1.0, 0.0, 1.0, 0.0},  // x = x + vx
    {0.0, 1.0, 0.0, 1.0},  // y = y + vy
    {0.0, 0.0, 1.0, 0.0},  // vx = vx
    {0.0, 0.0, 0.0, 1.0}   // vy = vy
};

// Measurement matrix H (we measure position only)
static const double H[2][4] = {
    {1.0, 0.0, 0.0, 0.0},  // measure x
    {0.0, 1.0, 0.0, 0.0}   // measure y
};

// ═══════════════════════════════════════════════════════════════════════════
// Matrix Operations (optimized inline)
// ═══════════════════════════════════════════════════════════════════════════

// 4x4 * 4x1 = 4x1
inline void matVec4(const double m[4][4], const double v[4], double out[4]) {
    for (int i = 0; i < 4; i++) {
        out[i] = m[i][0] * v[0] + m[i][1] * v[1] + m[i][2] * v[2] + m[i][3] * v[3];
    }
}

// 4x4 * 4x4 = 4x4
inline void matMul4x4(const double a[4][4], const double b[4][4], double out[4][4]) {
    for (int i = 0; i < 4; i++) {
        for (int j = 0; j < 4; j++) {
            out[i][j] = 0;
            for (int k = 0; k < 4; k++) {
                out[i][j] += a[i][k] * b[k][j];
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Kalman Filter Functions
// ═══════════════════════════════════════════════════════════════════════════

extern "C" void kalmanInit(double x, double y, double processNoise, double measurementNoise) {
    kalman.state[0] = x;
    kalman.state[1] = y;
    kalman.state[2] = 0.0;  // vx = 0
    kalman.state[3] = 0.0;  // vy = 0
    
    kalman.processNoise = processNoise;
    kalman.measurementNoise = measurementNoise;
    
    // Initialize covariance matrix
    memset(kalman.P, 0, sizeof(kalman.P));
    kalman.P[0][0] = 100.0;  // x uncertainty
    kalman.P[1][1] = 100.0;  // y uncertainty
    kalman.P[2][2] = 10.0;   // vx uncertainty
    kalman.P[3][3] = 10.0;   // vy uncertainty
    
    kalman.initialized = true;
    LOGI("Kalman initialized: x=%.2f, y=%.2f, Q=%.1f, R=%.1f", x, y, processNoise, measurementNoise);
}

extern "C" void kalmanPredict(double* outX, double* outY) {
    if (!kalman.initialized) {
        *outX = 0;
        *outY = 0;
        return;
    }
    
    // State prediction: x' = F * x
    double predictedState[4];
    matVec4(F, kalman.state, predictedState);
    
    // Covariance prediction: P' = F * P * F^T + Q
    double FP[4][4], FPFt[4][4];
    matMul4x4(F, kalman.P, FP);
    
    // F^T multiplication (simplified since F is sparse)
    for (int i = 0; i < 4; i++) {
        for (int j = 0; j < 4; j++) {
            FPFt[i][j] = 0;
            for (int k = 0; k < 4; k++) {
                FPFt[i][j] += FP[i][k] * F[j][k];  // F^T
            }
        }
    }
    
    // Add process noise Q
    for (int i = 0; i < 4; i++) {
        FPFt[i][i] += kalman.processNoise;
    }
    
    // Update state and covariance
    memcpy(kalman.state, predictedState, sizeof(predictedState));
    memcpy(kalman.P, FPFt, sizeof(FPFt));
    
    *outX = kalman.state[0];
    *outY = kalman.state[1];
}

extern "C" void kalmanUpdate(double measuredX, double measuredY) {
    if (!kalman.initialized) {
        kalmanInit(measuredX, measuredY, 300.0, 1.0);
        return;
    }
    
    // Innovation: y = z - H * x
    double innovation[2];
    innovation[0] = measuredX - kalman.state[0];
    innovation[1] = measuredY - kalman.state[1];
    
    // Innovation covariance: S = H * P * H^T + R
    double S[2][2];
    S[0][0] = kalman.P[0][0] + kalman.measurementNoise;
    S[0][1] = kalman.P[0][1];
    S[1][0] = kalman.P[1][0];
    S[1][1] = kalman.P[1][1] + kalman.measurementNoise;
    
    // Invert S (2x2)
    double det = S[0][0] * S[1][1] - S[0][1] * S[1][0];
    if (fabs(det) < 1e-10) return;
    
    double invS[2][2];
    invS[0][0] = S[1][1] / det;
    invS[0][1] = -S[0][1] / det;
    invS[1][0] = -S[1][0] / det;
    invS[1][1] = S[0][0] / det;
    
    // Kalman gain: K = P * H^T * S^-1
    double K[4][2];
    for (int i = 0; i < 4; i++) {
        // P * H^T (H^T is just first two columns transposed)
        double PHt[2];
        PHt[0] = kalman.P[i][0];
        PHt[1] = kalman.P[i][1];
        
        // Multiply by invS
        K[i][0] = PHt[0] * invS[0][0] + PHt[1] * invS[1][0];
        K[i][1] = PHt[0] * invS[0][1] + PHt[1] * invS[1][1];
    }
    
    // State update: x = x + K * innovation
    double oldX = kalman.state[0];
    double oldY = kalman.state[1];
    
    for (int i = 0; i < 4; i++) {
        kalman.state[i] += K[i][0] * innovation[0] + K[i][1] * innovation[1];
    }
    
    // Update velocity estimate (EMA)
    double dx = kalman.state[0] - oldX;
    double dy = kalman.state[1] - oldY;
    kalman.state[2] = kalman.state[2] * 0.5 + dx * 0.5;
    kalman.state[3] = kalman.state[3] * 0.5 + dy * 0.5;
    
    // Covariance update: P = (I - K * H) * P
    double IKH[4][4];
    for (int i = 0; i < 4; i++) {
        for (int j = 0; j < 4; j++) {
            IKH[i][j] = (i == j ? 1.0 : 0.0);
            if (j < 2) {
                IKH[i][j] -= K[i][j];
            }
        }
    }
    
    double newP[4][4];
    matMul4x4(IKH, kalman.P, newP);
    memcpy(kalman.P, newP, sizeof(newP));
}

extern "C" void kalmanGetState(double* x, double* y, double* vx, double* vy) {
    *x = kalman.state[0];
    *y = kalman.state[1];
    *vx = kalman.state[2];
    *vy = kalman.state[3];
}

extern "C" void kalmanPredictFuture(int steps, double* outX, double* outY) {
    if (!kalman.initialized) {
        *outX = 0;
        *outY = 0;
        return;
    }
    *outX = kalman.state[0] + kalman.state[2] * steps;
    *outY = kalman.state[1] + kalman.state[3] * steps;
}

extern "C" void kalmanReset() {
    kalman.initialized = false;
    memset(kalman.state, 0, sizeof(kalman.state));
    memset(kalman.P, 0, sizeof(kalman.P));
}

extern "C" double kalmanGetUncertainty() {
    return sqrt(kalman.P[0][0] * kalman.P[0][0] + kalman.P[1][1] * kalman.P[1][1]);
}
