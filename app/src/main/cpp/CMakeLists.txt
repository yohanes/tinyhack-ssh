cmake_minimum_required(VERSION 3.22.1)

project("ghostty-android")

# NDK r26 defaults to 4 KiB ELF LOAD alignment. Android 15+ devices may use
# 16 KiB memory pages, so make the produced JNI/executable ELF files portable.
add_link_options("LINKER:-z,max-page-size=16384")

set(GHOSTTY_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/ghostty")

include_directories(
    ${GHOSTTY_ROOT}/include
)

add_library(ghostty-vt STATIC IMPORTED)
set_target_properties(ghostty-vt PROPERTIES
    IMPORTED_LOCATION "${GHOSTTY_ROOT}/lib/${ANDROID_ABI}/libghostty-vt.a"
)

add_library(
    ghostty-android
    SHARED
    ghostty_jni.cpp
)

find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(
    ghostty-android
    PRIVATE
    ghostty-vt
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
)

# Headless SSH_ASKPASS helper. Executables must be named lib<name>.so for
# AGP to package them into jniLibs (same convention as libbash.so & co).
add_executable(askpass askpass.c)
set_target_properties(askpass PROPERTIES PREFIX "lib" OUTPUT_NAME "askpass.so")
