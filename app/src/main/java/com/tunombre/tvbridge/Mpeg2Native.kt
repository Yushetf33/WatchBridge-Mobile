package com.tunombre.tvbridge

import java.nio.ByteBuffer

/** Puente JNI con libmpeg2 (ver app/src/main/cpp/wbmpeg2_jni.c). */
internal object Mpeg2Native {
    val available: Boolean = try {
        System.loadLibrary("wbmpeg2")
        true
    } catch (e: UnsatisfiedLinkError) {
        false
    }

    external fun nativeCreate(maxWidth: Int, maxHeight: Int): Long
    external fun nativeDecode(handle: Long, buffer: ByteBuffer, offset: Int, size: Int, ts: Int): Int
    external fun nativeFrameInfo(handle: Long, info: IntArray)
    external fun nativeCopyFrame(handle: Long, dst: ByteBuffer, capacity: Int): Boolean
    external fun nativeFlush(handle: Long)
    external fun nativeRelease(handle: Long)
}
