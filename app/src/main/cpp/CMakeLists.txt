cmake_minimum_required(VERSION 3.22.1)
project("thumbnail-extractor")

# ccache for direct CLI/cmake builds; AGP's -DCMAKE_*_COMPILER_LAUNCHER args
# take precedence over this set(), so no double-wrapping in CI.
find_program(CCACHE_PROGRAM ccache)
if(CCACHE_PROGRAM)
    set(CMAKE_C_COMPILER_LAUNCHER   ${CCACHE_PROGRAM})
    set(CMAKE_CXX_COMPILER_LAUNCHER ${CCACHE_PROGRAM})
endif()

# Prebuilt FFmpeg shared libs from jniLibs/<abi>/
macro(add_ffmpeg_lib NAME)
    add_library(${NAME} SHARED IMPORTED)
    set_target_properties(${NAME} PROPERTIES
        IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${CMAKE_ANDROID_ARCH_ABI}/lib${NAME}.so
    )
endmacro()
add_ffmpeg_lib(avformat)
add_ffmpeg_lib(avcodec)
add_ffmpeg_lib(avutil)
add_ffmpeg_lib(swscale)
add_ffmpeg_lib(swresample)

# Dependency libs for the assrender native lib (all prebuilt, in jniLibs/<abi>/).
# libass is the fontconfig-enabled build; fontconfig pulls in expat; libass pulls
# in freetype/fribidi/harfbuzz. Imported so the linker records NEEDED entries and
# the Android packager bundles them.
macro(add_ass_dep NAME)
    add_library(${NAME} SHARED IMPORTED)
    set_target_properties(${NAME} PROPERTIES
        IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${CMAKE_ANDROID_ARCH_ABI}/lib${NAME}.so
    )
endmacro()
add_ass_dep(ass)
add_ass_dep(fontconfig)
add_ass_dep(expat)
add_ass_dep(freetype)
add_ass_dep(fribidi)
add_ass_dep(harfbuzz)

# libarchive (prebuilt: bz2+lzma fused statically; zlib from NDK sysroot).
# Public headers are copied into include/libarchive so the build does not
# depend on the build_archive scratch dir.
add_library(archive SHARED IMPORTED)
set_target_properties(archive PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${CMAKE_ANDROID_ARCH_ABI}/libarchive.so
)

find_library(JNIGRAPHICS jnigraphics)
find_library(LOG log)
find_library(ANDROID_LIB android)

add_library(thumbnail-extractor SHARED ThumbnailExtractor.cpp)
target_include_directories(thumbnail-extractor PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)
target_link_libraries(thumbnail-extractor
    avformat avcodec avutil swscale
    ${JNIGRAPHICS} ${LOG}
)

# Direct libass pipeline (no FFmpeg) — intercepts ASS from ExoPlayer's extractor.
# Libass headers come from the ass/ass.h, etc. checked into target include directory.
add_library(assrender SHARED ass_direct.c ass_direct_jni.c)
target_include_directories(assrender PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)
target_link_libraries(assrender
    ass fontconfig expat freetype fribidi harfbuzz
    ${JNIGRAPHICS} ${LOG}
)

# Archive entry reader: lists archive entries and serves a single entry's
# bytes through libarchive for the "archive://" Media3 DataSource scheme.
add_library(archive-extractor SHARED ArchiveExtractor.cpp)
target_include_directories(archive-extractor PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include/libarchive
)
target_link_libraries(archive-extractor
    archive
    ${LOG}
)

# FFmpeg software decode fallback for ExoPlayer (audio + video): decodes
# codecs MediaCodec can't handle (ProRes, DNxHD, MJPEG, CineForm, DTS, ALAC,
# …) and blits video frames into the Surface via ANativeWindow.
add_library(ffcodec SHARED FfmpegDecoder.cpp)
target_include_directories(ffcodec PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)
target_link_libraries(ffcodec
    avcodec avutil swscale swresample
    ${ANDROID_LIB} ${LOG}
)

add_library(dav1d SHARED IMPORTED)
set_target_properties(dav1d PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${CMAKE_ANDROID_ARCH_ABI}/libdav1d.so
)

# Standalone Native FFmpeg player engine: handles full demux, seek, video and audio decode,
# direct ANativeWindow blit, and instant seeking without ExoPlayer buffering pipelines.
add_library(ffplayer SHARED FfmpegPlayer.cpp)
target_include_directories(ffplayer PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)
target_link_libraries(ffplayer
    avformat avcodec avutil swscale swresample dav1d
    ${ANDROID_LIB} ${LOG}
)




