cmake_minimum_required(VERSION 3.22.1)
project(tunvix_ffmpeg C CXX)
set(CMAKE_CXX_STANDARD 11)
include(ExternalProject)
find_program(FFMPEG_MAKE make REQUIRED)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(ffmpeg_arch aarch64)
    set(ffmpeg_triple aarch64-linux-android)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(ffmpeg_arch arm)
    set(ffmpeg_triple armv7a-linux-androideabi)
elseif(ANDROID_ABI STREQUAL "x86")
    set(ffmpeg_arch x86)
    set(ffmpeg_triple i686-linux-android)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(ffmpeg_arch x86_64)
    set(ffmpeg_triple x86_64-linux-android)
else()
    message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
endif()
get_filename_component(llvm_bin "${CMAKE_C_COMPILER}" DIRECTORY)
set(ffmpeg_source "${CMAKE_CURRENT_BINARY_DIR}/ffmpeg-source")
set(ffmpeg_build "${CMAKE_CURRENT_BINARY_DIR}/ffmpeg-build")
ExternalProject_Add(ffmpeg_mp2
    URL "${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/ffmpeg-6.0.1.tar.xz"
    URL_HASH SHA256=9b16b8731d78e596b4be0d720428ca42df642bb2d78342881ff7f5bc29fc9623
    SOURCE_DIR "${ffmpeg_source}"
    BINARY_DIR "${ffmpeg_build}"
    CONFIGURE_COMMAND <SOURCE_DIR>/configure
        --target-os=android --arch=${ffmpeg_arch} --enable-cross-compile
        --cc=${llvm_bin}/${ffmpeg_triple}29-clang
        --ar=${CMAKE_AR} --ranlib=${CMAKE_RANLIB} --nm=${llvm_bin}/llvm-nm
        --enable-static --disable-shared --enable-pic --disable-asm --disable-debug --enable-small
        --disable-doc --disable-programs --disable-everything
        --disable-avdevice --disable-avformat --disable-swscale
        --disable-postproc --disable-avfilter --disable-network
        --disable-autodetect --disable-vulkan --disable-v4l2-m2m
        --enable-swresample --enable-decoder=mp2
        --extra-ldflags=-Wl,-z,max-page-size=16384
    BUILD_COMMAND ${FFMPEG_MAKE} -j4
    INSTALL_COMMAND ""
    BUILD_BYPRODUCTS "${ffmpeg_build}/libavcodec/libavcodec.a"
        "${ffmpeg_build}/libswresample/libswresample.a" "${ffmpeg_build}/libavutil/libavutil.a")

foreach(lib avcodec swresample avutil)
    add_library(${lib} STATIC IMPORTED)
    set_target_properties(${lib} PROPERTIES IMPORTED_LOCATION "${ffmpeg_build}/lib${lib}/lib${lib}.a")
endforeach()
add_library(ffmpegJNI SHARED ffmpeg_jni.cc)
add_dependencies(ffmpegJNI ffmpeg_mp2)
target_include_directories(ffmpegJNI PRIVATE "${ffmpeg_build}")
# FFmpeg's plain-text 'version' file must not shadow libc++'s <version> header.
target_compile_options(ffmpegJNI PRIVATE "-idirafter${ffmpeg_source}")
set_target_properties(ffmpegJNI PROPERTIES CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN ON)
target_link_libraries(ffmpegJNI PRIVATE swresample avcodec avutil android log m)
target_link_options(ffmpegJNI PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-Bsymbolic")
