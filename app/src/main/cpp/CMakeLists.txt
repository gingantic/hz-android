cmake_minimum_required(VERSION 3.22.1)
project("thumbnail-extractor")

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

find_library(JNIGRAPHICS jnigraphics)
find_library(LOG log)

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
