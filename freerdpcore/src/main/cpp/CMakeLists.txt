# FreeRDP 官方 Android 客户端原生层（android_freerdp.c 等，MPL-2.0）。
#
# 上游这个模块的 CMake 会用 ExternalProject 从源码重编 FreeRDP；这里改成直接链接
# third_party/build-android.sh 产出的静态库，和 rdpcore 用同一套依赖。
cmake_minimum_required(VERSION 3.22)

project(freerdp-android C)

set(ABI_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/prebuilt/${ANDROID_ABI}")
if(NOT EXISTS "${ABI_DIR}/lib/libfreerdp-client3.a")
    message(FATAL_ERROR
        "缺少 ${ABI_DIR} 下的 FreeRDP 静态库。\n"
        "先在 Git Bash 里跑：third_party/build-android.sh ${ANDROID_ABI}")
endif()

add_library(freerdp-android SHARED
    android_cliprdr.c
    android_disp.c
    android_event.c
    android_freerdp.c
    android_jni_callback.c
    android_jni_utils.c
    android_rail.c)

target_include_directories(freerdp-android PRIVATE
    "${ABI_DIR}/include/freerdp3"
    "${ABI_DIR}/include/winpr3")

target_compile_options(freerdp-android PRIVATE -Wall -Wno-unused-parameter)
# FreeRDP/OpenSSL 静态库带调试信息，不剥的话 .so 会有几十 MB
# WinPR 与 android_freerdp.c 各有一个 JNI_OnLoad/JNI_OnUnload，静态链接必然重名；
# 由前者的对象优先（我们的文件在命令行前面），WinPR 的 VM 缓存在它的 OnLoad 里手动补上
target_link_options(freerdp-android PRIVATE -Wl,-s -Wl,--allow-multiple-definition)

set(RDP_STATIC_LIBS
    "${ABI_DIR}/lib/libfreerdp-client3.a"
    "${ABI_DIR}/lib/libfreerdp3.a"
    "${ABI_DIR}/lib/libwinpr3.a"
    "${ABI_DIR}/lib/libssl.a"
    "${ABI_DIR}/lib/libcrypto.a")

find_library(ANDROID_LOG_LIB log)
find_library(JNIGRAPHICS_LIB jnigraphics)
find_library(OPENSLES_LIB OpenSLES)

target_link_libraries(freerdp-android
    -Wl,--start-group ${RDP_STATIC_LIBS} -Wl,--end-group
    ${ANDROID_LOG_LIB}
    ${JNIGRAPHICS_LIB}
    ${OPENSLES_LIB}
    z m dl atomic)
