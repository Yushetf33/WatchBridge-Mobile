/*
 * Decodificador de audio MPEG-1 Layer II (MP2) por software para WatchBridge, con PL_MPEG
 * (https://github.com/phoboslab/pl_mpeg, licencia MIT; ver plmpeg/pl_mpeg.h).
 *
 * Por que: la TDT (Cuatro, Telecinco, La 1...) lleva el audio en MP2 y hay equipos (p.ej. el Fire TV
 * Stick 4K) sin decodificador de MP2 del sistema: el video se ve y no suena. Este decodificador se
 * usa solo como respaldo cuando el sistema no tiene uno (ver Mp2SoftwareAudio.kt).
 *
 * Cada muestra que entrega ExoPlayer es una trama MP2 completa: se escribe en el buffer de PL_MPEG
 * y se decodifica una trama (1152 muestras estereo en coma flotante -> PCM de 16 bits).
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define PL_MPEG_IMPLEMENTATION
#include "plmpeg/pl_mpeg.h"

typedef struct {
    plm_buffer_t *buffer;
    plm_audio_t *audio;
} Mp2;

static void mp2_open(Mp2 *m) {
    m->buffer = plm_buffer_create_with_capacity(64 * 1024);
    m->audio = plm_audio_create_with_buffer(m->buffer, 1 /* destruye el buffer al terminar */);
}

JNIEXPORT jlong JNICALL
Java_com_tunombre_tvbridge_Mp2Native_nativeCreate(JNIEnv *env, jclass clazz) {
    Mp2 *m = (Mp2 *)calloc(1, sizeof(Mp2));
    if (!m) return 0;
    mp2_open(m);
    return (jlong)(intptr_t)m;
}

/* Devuelve los bytes de PCM (16 bits, estereo intercalado) escritos en out, o 0 si no salio trama. */
JNIEXPORT jint JNICALL
Java_com_tunombre_tvbridge_Mp2Native_nativeDecode(JNIEnv *env, jclass clazz, jlong handle,
                                                  jobject in, jint offset, jint size,
                                                  jobject out, jint capacity) {
    Mp2 *m = (Mp2 *)(intptr_t)handle;
    uint8_t *src = (uint8_t *)(*env)->GetDirectBufferAddress(env, in);
    uint8_t *dst = (uint8_t *)(*env)->GetDirectBufferAddress(env, out);
    if (!m || !src || !dst || size <= 0) return 0;

    /* Cada muestra es UNA trama completa y alineada: se empieza siempre desde cero en el buffer
     * (sin restos de la anterior ni resincronizacion). El estado de sintesis del decodificador se
     * conserva, que es lo que da continuidad al audio entre tramas. */
    m->buffer->bit_index = 0;
    m->buffer->length = 0;
    m->audio->next_frame_data_size = 0;
    plm_buffer_write(m->buffer, src + offset, (size_t)size);
    int written = 0;
    plm_samples_t *s;
    /* normalmente sale una trama por muestra; si el buffer acumulo mas, se sacan todas las que quepan */
    while ((s = plm_audio_decode(m->audio)) != NULL) {
        int n = PLM_AUDIO_SAMPLES_PER_FRAME * 2;
        if (written + n * 2 > capacity) break;
        int16_t *o = (int16_t *)(dst + written);
        for (int i = 0; i < n; i++) {
            float v = s->interleaved[i] * 32767.0f;
            if (v > 32767.0f) v = 32767.0f;
            if (v < -32768.0f) v = -32768.0f;
            o[i] = (int16_t)v;
        }
        written += n * 2;
    }
    return written;
}

JNIEXPORT jint JNICALL
Java_com_tunombre_tvbridge_Mp2Native_nativeSampleRate(JNIEnv *env, jclass clazz, jlong handle) {
    Mp2 *m = (Mp2 *)(intptr_t)handle;
    return m && m->audio ? plm_audio_get_samplerate(m->audio) : 0;
}

/* Tras un salto (cambio de canal, busqueda): descarta lo acumulado y empieza de cero. */
JNIEXPORT void JNICALL
Java_com_tunombre_tvbridge_Mp2Native_nativeFlush(JNIEnv *env, jclass clazz, jlong handle) {
    Mp2 *m = (Mp2 *)(intptr_t)handle;
    if (!m) return;
    plm_audio_destroy(m->audio);
    mp2_open(m);
}

JNIEXPORT void JNICALL
Java_com_tunombre_tvbridge_Mp2Native_nativeRelease(JNIEnv *env, jclass clazz, jlong handle) {
    Mp2 *m = (Mp2 *)(intptr_t)handle;
    if (!m) return;
    plm_audio_destroy(m->audio);
    free(m);
}
