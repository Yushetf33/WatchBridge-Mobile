package com.tunombre.tvbridge

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.Decoder
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.VideoDecoderOutputBuffer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.DecoderVideoRenderer
import androidx.media3.exoplayer.video.VideoDecoderOutputBufferRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import java.nio.ByteBuffer

/**
 * Decodificador de video MPEG-2 por SOFTWARE (libmpeg2 de AOSP, Apache-2.0; ver app/src/main/cpp)
 * para la segunda celda del multiview: hay equipos (p.ej. el Fire TV Stick 4K) donde dos
 * decodificadores MPEG-2 por hardware a la vez congelan el video, y otros (la TV de pruebas) donde
 * reinician el equipo. El decodificador de software de Android, cuando existe, saca los fotogramas
 * con un ancho de linea que la pantalla no entiende (rayas); este entrega YUV 4:2:0 con los anchos
 * de linea reales y se dibuja con [androidx.media3.exoplayer.video.VideoDecoderGLSurfaceView].
 */
@OptIn(UnstableApi::class)
internal class Mpeg2SoftwareDecoder(format: Format) :
    SimpleDecoder<DecoderInputBuffer, VideoDecoderOutputBuffer, DecoderException>(
        @Suppress("UNCHECKED_CAST") (arrayOfNulls<DecoderInputBuffer>(INPUT_BUFFERS) as Array<DecoderInputBuffer>),
        @Suppress("UNCHECKED_CAST") (arrayOfNulls<VideoDecoderOutputBuffer>(OUTPUT_BUFFERS) as Array<VideoDecoderOutputBuffer>)
    ) {

    private val handle: Long = Mpeg2Native.nativeCreate(MAX_WIDTH, MAX_HEIGHT)
    private val info = IntArray(5)
    // La API nativa solo lleva 32 bits de marca de tiempo: se guarda la real aparte y se recupera al salir el fotograma.
    private val ptsRing = LongArray(RING)
    private var counter = 0

    init {
        if (handle == 0L) throw DecoderException("No se pudo crear el decodificador MPEG-2 de software")
        setInitialInputBufferSize(INITIAL_INPUT_SIZE)
        // La cabecera de secuencia (dimensiones) llega en los datos de inicializacion del formato.
        format.initializationData.firstOrNull()?.let { csd ->
            val direct = ByteBuffer.allocateDirect(csd.size).put(csd)
            direct.flip()
            Mpeg2Native.nativeDecode(handle, direct, 0, csd.size, 0)
        }
    }

    override fun getName(): String = "libmpeg2-software"

    override fun createInputBuffer(): DecoderInputBuffer =
        DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)

    override fun createOutputBuffer(): VideoDecoderOutputBuffer =
        VideoDecoderOutputBuffer { buffer -> releaseOutputBuffer(buffer) }

    override fun createUnexpectedDecodeException(error: Throwable): DecoderException =
        DecoderException("Error inesperado en el decodificador MPEG-2 de software", error)

    override fun decode(input: DecoderInputBuffer, output: VideoDecoderOutputBuffer, reset: Boolean): DecoderException? {
        if (reset) Mpeg2Native.nativeFlush(handle)
        val data = input.data ?: return null
        val size = data.remaining()
        if (size <= 0) {
            output.shouldBeSkipped = true
            return null
        }
        val ts = counter++ and Int.MAX_VALUE
        ptsRing[ts % RING] = input.timeUs
        val started = SystemClock.elapsedRealtimeNanos()
        val result = Mpeg2Native.nativeDecode(handle, data, data.position(), size, ts)
        decodeNanos += SystemClock.elapsedRealtimeNanos() - started
        decodedPictures++
        if (result < 0) return DecoderException("Error al decodificar MPEG-2 (codigo $result)")
        if (result == 0) {
            output.shouldBeSkipped = true
            return null
        }
        Mpeg2Native.nativeFrameInfo(handle, info)
        logStats()
        output.init(ptsRing[info[4] % RING], C.VIDEO_OUTPUT_MODE_YUV, null)
        if (!output.initForYuvFrame(info[0], info[1], info[2], info[3], VideoDecoderOutputBuffer.COLORSPACE_BT601)) {
            return DecoderException("No hay memoria para el fotograma")
        }
        val frame = output.data ?: return DecoderException("Fotograma sin datos")
        if (!Mpeg2Native.nativeCopyFrame(handle, frame, frame.capacity())) {
            return DecoderException("No se pudo copiar el fotograma")
        }
        return null
    }

    // Estadisticas para medir si la CPU aguanta: cada ~5 s, imagenes por segundo y ms medios por imagen.
    private var decodedPictures = 0
    private var decodeNanos = 0L
    private var statsSince = SystemClock.elapsedRealtime()

    private fun logStats() {
        if (!com.tunombre.tvbridge.mobile.BuildConfig.DEBUG) return
        val now = SystemClock.elapsedRealtime()
        if (now - statsSince < STATS_MS || decodedPictures == 0) return
        val seconds = (now - statsSince) / 1000.0
        Log.i(TAG, "%.1f imagenes/s, %.1f ms de CPU por imagen".format(decodedPictures / seconds, decodeNanos / 1e6 / decodedPictures))
        decodedPictures = 0
        decodeNanos = 0
        statsSince = now
    }

    override fun release() {
        super.release()
        Mpeg2Native.nativeRelease(handle)
    }

    private companion object {
        const val TAG = "WbMpeg2"
        const val STATS_MS = 5000L
        const val INPUT_BUFFERS = 6
        const val OUTPUT_BUFFERS = 4
        const val INITIAL_INPUT_SIZE = 256 * 1024
        const val RING = 256
        // Margen para MPEG-2 de alta definicion (la inmensa mayoria es SD 720x576).
        const val MAX_WIDTH = 1920
        const val MAX_HEIGHT = 1088
    }
}

/**
 * Renderer de ExoPlayer que usa [Mpeg2SoftwareDecoder] y dibuja en un
 * [VideoDecoderOutputBufferRenderer] (la vista GL de la celda). Solo acepta esa salida: el
 * [android.view.Surface] que ExoPlayer manda a todos los renderers de video se ignora, porque lo
 * usa el decodificador por hardware del resto de formatos.
 */
@OptIn(UnstableApi::class)
internal class Mpeg2SoftwareVideoRenderer(
    eventHandler: Handler?,
    eventListener: VideoRendererEventListener?,
    private val onActiveChanged: (Boolean) -> Unit
) : DecoderVideoRenderer(ALLOWED_JOINING_MS, eventHandler, eventListener, MAX_DROPPED_NOTIFY) {

    override fun getName(): String = "Mpeg2SoftwareVideoRenderer"

    override fun supportsFormat(format: Format): Int =
        if (Mpeg2Native.available && MimeTypes.VIDEO_MPEG2 == format.sampleMimeType) {
            RendererCapabilities.create(C.FORMAT_HANDLED)
        } else {
            RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        }

    override fun createDecoder(
        format: Format,
        cryptoConfig: CryptoConfig?
    ): Decoder<DecoderInputBuffer, out VideoDecoderOutputBuffer, out DecoderException> = Mpeg2SoftwareDecoder(format)

    override fun renderOutputBufferToSurface(outputBuffer: VideoDecoderOutputBuffer, surface: Surface) {
        throw DecoderException("Este decodificador solo dibuja en la vista GL")
    }

    override fun setDecoderOutputMode(outputMode: Int) = Unit

    override fun handleMessage(messageType: Int, message: Any?) {
        if (messageType == Renderer.MSG_SET_VIDEO_OUTPUT && message !is VideoDecoderOutputBufferRenderer) return
        super.handleMessage(messageType, message)
    }

    override fun onEnabled(joining: Boolean, mayRenderStartOfStream: Boolean) {
        super.onEnabled(joining, mayRenderStartOfStream)
        onActiveChanged(true)
    }

    override fun onDisabled() {
        super.onDisabled()
        onActiveChanged(false)
    }

    private companion object {
        const val ALLOWED_JOINING_MS = 0L
        const val MAX_DROPPED_NOTIFY = 50
    }
}

/**
 * Renderers por defecto de ExoPlayer + los de software propios:
 *  - audio MP2 (Mp2SoftwareAudioRenderer) DETRAS de los del sistema: solo se usa si el equipo no
 *    tiene decodificador de MP2 (el Fire TV Stick 4K no lo tiene y los canales de la TDT no sonaban);
 *  - si [softwareMpeg2Video], video MPEG-2 por software (Mpeg2SoftwareVideoRenderer) DELANTE: gana
 *    el empate con el decodificador del sistema (celda 2 del multiview).
 */
@OptIn(UnstableApi::class)
internal class WatchBridgeRenderersFactory(
    context: Context,
    selector: MediaCodecSelector? = null,
    private val softwareMpeg2Video: Boolean = false,
    private val softwareMp2First: Boolean = false
) : DefaultRenderersFactory(context) {

    /** El renderer de video de software de la ultima construccion, para mandarle la vista donde dibujar. */
    var softwareRenderer: Mpeg2SoftwareVideoRenderer? = null
        private set

    init {
        if (selector != null) setMediaCodecSelector(selector)
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        if (softwareMpeg2Video && Mpeg2Native.available) {
            val software = Mpeg2SoftwareVideoRenderer(eventHandler, eventListener) { }
            softwareRenderer = software
            out.add(software)
        }
        super.buildVideoRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
            eventHandler, eventListener, allowedVideoJoiningTimeMs, out
        )
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        // En el movil (softwareMp2First) el de software va DELANTE: muchos moviles dicen tener decodificador de MP2 y luego
        // no suena nada; asi el audio MP2 siempre lo decodificamos nosotros.
        if (softwareMp2First && Mp2Native.available) out.add(Mp2SoftwareAudioRenderer(eventHandler, eventListener, audioSink))
        super.buildAudioRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
            audioSink, eventHandler, eventListener, out
        )
        if (!softwareMp2First && Mp2Native.available) out.add(Mp2SoftwareAudioRenderer(eventHandler, eventListener, audioSink))
    }
}
