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

find_library(JNIGRAPHICS jnigraphics)
find_library(LOG log)

add_library(thumbnail-extractor SHARED ThumbnailExtractor.cpp)
target_include_directories(thumbnail-extractor PRIVATE
    ${CMAKE_SOURCE_DIR}/../jniLibs/${CMAKE_ANDROID_ARCH_ABI}
)
target_link_libraries(thumbnail-extractor
    avformat avcodec avutil swscale
    ${JNIGRAPHICS} ${LOG}
)
