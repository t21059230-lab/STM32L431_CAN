# CMakeLists.txt for CANphon Native Core
# تكوين CMake للوصول المباشر عالي الأداء

cmake_minimum_required(VERSION 3.22.1)

project("canphon_native")

# Add the native library with all source files
add_library(${CMAKE_PROJECT_NAME} SHARED
    native_sensors.cpp
    jni_bridge.cpp
    kalman_filter.cpp
    filters.cpp
    guidance_controller.cpp
    # Phase 1: Native Tracking Core
    object_tracker.cpp
    target_discriminator.cpp
    # Phase 2: Native Telemetry
    telemetry.cpp
    # Phase 3: Servo Protocol
    servo_protocol.cpp
)

# Find and link required libraries
find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(${CMAKE_PROJECT_NAME}
    ${log-lib}
    ${android-lib}
)

# Enable C++17 features
target_compile_features(${CMAKE_PROJECT_NAME} PRIVATE cxx_std_17)

# Optimization flags for release
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O3 -ffast-math")
