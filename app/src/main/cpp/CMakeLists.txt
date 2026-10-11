cmake_minimum_required(VERSION 3.18.1)
project(wbmpeg2 C ASM)

# Decodificador de software MPEG-2 (libmpeg2 de AOSP, Apache-2.0) + un pequeno envoltorio JNI.
# Solo ARM (los equipos de TV son ARM): ver abiFilters en app/build.gradle.kts.
set(M ${CMAKE_CURRENT_SOURCE_DIR}/libmpeg2)

set(COMMON_SRCS
    ${M}/common/icv_sad.c
    ${M}/common/icv_variance.c
    ${M}/common/ideint.c
    ${M}/common/ideint_cac.c
    ${M}/common/ideint_debug.c
    ${M}/common/ideint_function_selector_generic.c
    ${M}/common/ideint_utils.c
    ${M}/common/impeg2_buf_mgr.c
    ${M}/common/impeg2_disp_mgr.c
    ${M}/common/impeg2_format_conv.c
    ${M}/common/impeg2_globals.c
    ${M}/common/impeg2_idct.c
    ${M}/common/impeg2_inter_pred.c
    ${M}/common/impeg2_job_queue.c
    ${M}/common/impeg2_mem_func.c
    ${M}/common/ithread.c
    ${M}/decoder/impeg2d_api_main.c
    ${M}/decoder/impeg2d_bitstream.c
    ${M}/decoder/impeg2d_d_pic.c
    ${M}/decoder/impeg2d_debug.c
    ${M}/decoder/impeg2d_dec_hdr.c
    ${M}/decoder/impeg2d_decoder.c
    ${M}/decoder/impeg2d_deinterlace.c
    ${M}/decoder/impeg2d_function_selector_generic.c
    ${M}/decoder/impeg2d_globals.c
    ${M}/decoder/impeg2d_i_pic.c
    ${M}/decoder/impeg2d_mc.c
    ${M}/decoder/impeg2d_mv_dec.c
    ${M}/decoder/impeg2d_pic_proc.c
    ${M}/decoder/impeg2d_pnb_pic.c
    ${M}/decoder/impeg2d_vld.c
    ${M}/decoder/impeg2d_vld_tables.c)

set(INCLUDES ${M}/common ${M}/decoder ${CMAKE_CURRENT_SOURCE_DIR}/compat)
set(DEFS _LIB MULTICORE ANDROID)
set(FLAGS -O3 -fPIC -w)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    list(APPEND INCLUDES ${M}/common/armv8 ${M}/decoder/arm)
    list(APPEND DEFS ARMV8 ARM DEFAULT_ARCH=D_ARCH_ARMV8_GENERIC)
    set(ARCH_SRCS
        ${M}/common/arm/ideint_function_selector.c
        ${M}/common/arm/ideint_function_selector_av8.c
        ${M}/common/armv8/icv_sad_av8.s
        ${M}/common/armv8/icv_variance_av8.s
        ${M}/common/armv8/ideint_cac_av8.s
        ${M}/common/armv8/ideint_spatial_filter_av8.s
        ${M}/common/armv8/impeg2_format_conv.s
        ${M}/common/armv8/impeg2_idct.s
        ${M}/common/armv8/impeg2_inter_pred.s
        ${M}/common/armv8/impeg2_mem_func.s
        ${M}/decoder/arm/impeg2d_function_selector.c
        ${M}/decoder/arm/impeg2d_function_selector_av8.c)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    list(APPEND INCLUDES ${M}/common/arm ${M}/decoder/arm)
    list(APPEND DEFS ARM DEFAULT_ARCH=D_ARCH_ARM_A9Q)
    list(APPEND FLAGS -marm -mfpu=neon)
    set(ARCH_SRCS
        ${M}/common/arm/ideint_function_selector.c
        ${M}/common/arm/ideint_function_selector_a9.c
        ${M}/common/arm/icv_sad_a9.s
        ${M}/common/arm/icv_variance_a9.s
        ${M}/common/arm/ideint_cac_a9.s
        ${M}/common/arm/ideint_spatial_filter_a9.s
        ${M}/common/arm/impeg2_format_conv.s
        ${M}/common/arm/impeg2_idct.s
        ${M}/common/arm/impeg2_inter_pred.s
        ${M}/common/arm/impeg2_mem_func.s
        ${M}/decoder/arm/impeg2d_function_selector.c
        ${M}/decoder/arm/impeg2d_function_selector_a9q.c)
else()
    message(FATAL_ERROR "ABI no soportada para el decodificador MPEG-2: ${ANDROID_ABI}")
endif()

add_library(wbmpeg2 SHARED wbmpeg2_jni.c wbmp2audio_jni.c ${COMMON_SRCS} ${ARCH_SRCS})
target_include_directories(wbmpeg2 PRIVATE ${INCLUDES})
target_compile_definitions(wbmpeg2 PRIVATE ${DEFS})
target_compile_options(wbmpeg2 PRIVATE ${FLAGS})
set_source_files_properties(${ARCH_SRCS} PROPERTIES COMPILE_FLAGS "-I${M}/common/armv8 -I${M}/common/arm")
# Google Play exige bibliotecas nativas alineadas a 16 KB (Android 15+).
target_link_libraries(wbmpeg2 log m "-Wl,-z,max-page-size=16384")
