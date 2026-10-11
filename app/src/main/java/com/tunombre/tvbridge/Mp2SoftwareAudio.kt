package com.tunombre.tvbridge

import android.os.Handler
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DecoderAudioRenderer
import java.nio.ByteBuffer

/** Puente JNI con el decodificador MP2 de PL_MPEG (ver app/src/main/cpp/wbmp2audio_jni.c). */
internal object Mp2Native {
    val available: Boolean = Mpeg2Native.available

    external fun nativeCreate(): Long
    external fun nativeDecode(handle: Long, input: ByteBuffer, offset: Int, size: Int, out: ByteBuffer, capacity: Int): Int
    external fun nativeSampleRate(handle: Long): Int
    external fun nativeFlush(handle: Long)
    external fun nativeRelease(handle: Long)
}

/**
 * Decodificador de audio MPEG-1 Layer II por software. La TDT (Cuatro, Telecinco...) lleva audio MP2
 * y hay equipos sin decodificador de MP2 del sistema (el Fire TV Stick 4K): el video se ve pero no
 * suena. Solo se usa cuando el sistema no tiene uno (ver [Mp2SoftwareAudioRenderer]).
 */
@OptIn(UnstableApi::class)
internal class Mp2SoftwareDecoder(private val inputFormat: Format) :
    SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException>(
        @Suppress("UNCHECKED_CAST") (arrayOfNulls<DecoderInputBuffer>(INPUT_BUFFERS) as Array<DecoderInputBuffer>),
        @Suppress("UNCHECKED_CAST") (arrayOfNulls<SimpleDecoderOutputBuffer>(OUTPUT_BUFFERS) as Array<SimpleDecoderOutputBuffer>)
    ) {

    private val handle: Long = Mp2Native.nativeCreate()

    init {
        if (handle == 0L) throw DecoderException("No se pudo crear el decodificador MP2 de software")
        setInitialInputBufferSize(INITIAL_INPUT_SIZE)
    }

    // Frecuencia REAL del flujo, leida de la cabecera de la primera trama. Antes se fiaba del formato
    // que declara el contenedor (o de 48 kHz por defecto): en canales que emiten a 32 kHz el audio se
    // reproducia a 48/32 = 1,5 veces la velocidad (las voces sonaban aceleradas).
    @Volatile
    private var streamSampleRate = 0

    /** Formato PCM que entrega: 16 bits, estereo (PL_MPEG siempre saca dos canales). */
    val outputFormat: Format
        get() = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setChannelCount(2)
            .setSampleRate(streamSampleRate.takeIf { it > 0 } ?: inputFormat.sampleRate.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .build()

    override fun getName(): String = "pl_mpeg-mp2-software"

    override fun createInputBuffer(): DecoderInputBuffer =
        DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)

    override fun createOutputBuffer(): SimpleDecoderOutputBuffer =
        SimpleDecoderOutputBuffer { buffer -> releaseOutputBuffer(buffer) }

    override fun createUnexpectedDecodeException(error: Throwable): DecoderException =
        DecoderException("Error inesperado en el decodificador MP2 de software", error)

    override fun decode(input: DecoderInputBuffer, output: SimpleDecoderOutputBuffer, reset: Boolean): DecoderException? {
        if (reset) Mp2Native.nativeFlush(handle)
        val data = input.data ?: return null
        val size = data.remaining()
        statsIn++
        statsInBytes += size
        val pcm = output.init(input.timeUs, MAX_PCM_BYTES)
        val written = if (size > 0) Mp2Native.nativeDecode(handle, data, data.position(), size, pcm, MAX_PCM_BYTES) else 0
        if (written <= 0) {
            output.shouldBeSkipped = true
            return null
        }
        statsOutFrames += written / PCM_FRAME_BYTES
        logStats()
        pcm.position(0)
        pcm.limit(written)
        if (streamSampleRate == 0) {
            streamSampleRate = Mp2Native.nativeSampleRate(handle)
            Log.i("WbMp2", "audio MP2 a ${streamSampleRate} Hz (el contenedor declaraba ${inputFormat.sampleRate} Hz)")
        }
        return null
    }

    // Diagnostico: tramas MP2 que entran y tramas de audio que salen (a 48 kHz deberian ser ~41,7/s).
    private var statsIn = 0
    private var statsInBytes = 0L
    private var statsOutFrames = 0
    private var statsSince = android.os.SystemClock.elapsedRealtime()

    private fun logStats() {
        if (!com.tunombre.tvbridge.mobile.BuildConfig.DEBUG) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - statsSince < 5000) return
        val s = (now - statsSince) / 1000.0
        Log.i("WbMp2", "entran %.1f muestras/s (%.0f bytes de media), salen %.1f tramas de audio/s".format(statsIn / s, statsInBytes.toDouble() / maxOf(statsIn, 1), statsOutFrames / s))
        statsIn = 0; statsInBytes = 0; statsOutFrames = 0; statsSince = now
    }

    override fun release() {
        super.release()
        Mp2Native.nativeRelease(handle)
    }

    private companion object {
        const val INPUT_BUFFERS = 8
        const val OUTPUT_BUFFERS = 8
        const val INITIAL_INPUT_SIZE = 4096
        const val DEFAULT_SAMPLE_RATE = 48000
        // Una trama MP2 = 1152 muestras x 2 canales x 2 bytes = 4608; margen para dos.
        const val PCM_FRAME_BYTES = 4608
        const val MAX_PCM_BYTES = 4608 * 2
    }
}

/** Renderer de audio con [Mp2SoftwareDecoder]; solo reclama el formato MP2 (audio/mpeg-L2). */
@OptIn(UnstableApi::class)
internal class Mp2SoftwareAudioRenderer(
    eventHandler: Handler?,
    eventListener: AudioRendererEventListener?,
    audioSink: AudioSink
) : DecoderAudioRenderer<Mp2SoftwareDecoder>(eventHandler, eventListener, audioSink) {

    override fun getName(): String = "Mp2SoftwareAudioRenderer"

    override fun supportsFormatInternal(format: Format): Int =
        if (Mp2Native.available && MimeTypes.AUDIO_MPEG_L2 == format.sampleMimeType) {
            RendererCapabilities.create(C.FORMAT_HANDLED)
        } else {
            RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        }

    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?): Mp2SoftwareDecoder = Mp2SoftwareDecoder(format)

    override fun getOutputFormat(decoder: Mp2SoftwareDecoder): Format = decoder.outputFormat
}
