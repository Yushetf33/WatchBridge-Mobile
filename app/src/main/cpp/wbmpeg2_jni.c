/*
 * Envoltorio JNI de libmpeg2 (decodificador MPEG-2 de AOSP, Apache-2.0) para el multiview de
 * WatchBridge: un decodificador de SOFTWARE para la segunda celda cuando el equipo no aguanta dos
 * decodificadores MPEG-2 por hardware (ver Mpeg2SoftwareDecoder.kt).
 *
 * Flujo: create -> decode(muestra) por cada imagen del flujo -> si devuelve 1 hay un fotograma
 * listo (frameInfo + copyFrame lo sacan en YUV 4:2:0 planar con los anchos de linea reales).
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include "iv_datatypedef.h"
#include "iv.h"
#include "ivd.h"
#include "impeg2d.h"

#define TAG "WbMpeg2"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

typedef struct {
    iv_obj_t *obj;
    iv_mem_rec_t *recs;
    uint32_t num_recs;
    ivd_out_bufdesc_t out;
    uint8_t *outbuf;
    uint32_t outcap;
    int header_done;
    uint32_t width, height;
    /* ultimo fotograma sacado */
    uint8_t *py, *pu, *pv;
    uint32_t fw, fh, ystride, ustride, vstride, ts;
} Ctx;

static void *aligned_alloc_(uint32_t alignment, uint32_t size) {
    void *p = NULL;
    if (alignment < sizeof(void *)) alignment = sizeof(void *);
    if (posix_memalign(&p, alignment, size) != 0) return NULL;
    return p;
}

static int set_mode(Ctx *c, IVD_VIDEO_DECODE_MODE_T mode, uint32_t disp_wd) {
    ivd_ctl_set_config_ip_t ip;
    ivd_ctl_set_config_op_t op;
    memset(&ip, 0, sizeof(ip));
    memset(&op, 0, sizeof(op));
    ip.u4_disp_wd = disp_wd;
    ip.e_frm_skip_mode = IVD_SKIP_NONE;
    ip.e_frm_out_mode = IVD_DISPLAY_FRAME_OUT;
    ip.e_vid_dec_mode = mode;
    ip.e_cmd = IVD_CMD_VIDEO_CTL;
    ip.e_sub_cmd = IVD_CMD_CTL_SETPARAMS;
    ip.u4_size = sizeof(ip);
    op.u4_size = sizeof(op);
    return impeg2d_api_function(c->obj, &ip, &op) == IV_SUCCESS ? 0 : -1;
}

JNIEXPORT jlong JNICALL
Java_com_tunombre_tvbridge_Mpeg2Native_nativeCreate(JNIEnv *env, jclass clazz, jint max_w, jint max_h) {
    Ctx *c = (Ctx *)calloc(1, sizeof(Ctx));
    if (!c) return 0;

    iv_num_mem_rec_ip_t q_ip;
    iv_num_mem_rec_op_t q_op;
    q_ip.u4_size = sizeof(q_ip);
    q_op.u4_size = sizeof(q_op);
    q_ip.e_cmd = IV_CMD_GET_NUM_MEM_REC;
    if (impeg2d_api_function(NULL, &q_ip, &q_op) != IV_SUCCESS) { LOGE("num mem rec"); free(c); return 0; }
    c->num_recs = q_op.u4_num_mem_rec;
    c->recs = (iv_mem_rec_t *)calloc(c->num_recs, sizeof(iv_mem_rec_t));
    for (uint32_t i = 0; i < c->num_recs; i++) c->recs[i].u4_size = sizeof(iv_mem_rec_t);

    impeg2d_fill_mem_rec_ip_t f_ip;
    impeg2d_fill_mem_rec_op_t f_op;
    memset(&f_ip, 0, sizeof(f_ip));
    memset(&f_op, 0, sizeof(f_op));
    f_ip.s_ivd_fill_mem_rec_ip_t.e_cmd = IV_CMD_FILL_NUM_MEM_REC;
    f_ip.s_ivd_fill_mem_rec_ip_t.pv_mem_rec_location = c->recs;
    f_ip.s_ivd_fill_mem_rec_ip_t.u4_max_frm_wd = max_w;
    f_ip.s_ivd_fill_mem_rec_ip_t.u4_max_frm_ht = max_h;
    f_ip.u4_share_disp_buf = 0;
    f_ip.u4_deinterlace = 0;
    f_ip.u4_keep_threads_active = 0;
    f_ip.e_output_format = IV_YUV_420P;
    f_ip.s_ivd_fill_mem_rec_ip_t.u4_size = sizeof(f_ip);
    f_op.s_ivd_fill_mem_rec_op_t.u4_size = sizeof(f_op);
    if (impeg2d_api_function(NULL, &f_ip, &f_op) != IV_SUCCESS) { LOGE("fill mem rec"); free(c->recs); free(c); return 0; }
    c->num_recs = f_op.s_ivd_fill_mem_rec_op_t.u4_num_mem_rec_filled;

    for (uint32_t i = 0; i < c->num_recs; i++) {
        c->recs[i].pv_base = aligned_alloc_(c->recs[i].u4_mem_alignment, c->recs[i].u4_mem_size);
        if (!c->recs[i].pv_base) { LOGE("alloc rec %u", i); return 0; }
    }

    impeg2d_init_ip_t i_ip;
    impeg2d_init_op_t i_op;
    memset(&i_ip, 0, sizeof(i_ip));
    memset(&i_op, 0, sizeof(i_op));
    i_ip.s_ivd_init_ip_t.e_cmd = (IVD_API_COMMAND_TYPE_T)IV_CMD_INIT;
    i_ip.s_ivd_init_ip_t.pv_mem_rec_location = c->recs;
    i_ip.s_ivd_init_ip_t.u4_frm_max_wd = max_w;
    i_ip.s_ivd_init_ip_t.u4_frm_max_ht = max_h;
    i_ip.u4_share_disp_buf = 0;
    i_ip.u4_deinterlace = 0;
    i_ip.u4_keep_threads_active = 0;
    i_ip.s_ivd_init_ip_t.u4_num_mem_rec = c->num_recs;
    i_ip.s_ivd_init_ip_t.e_output_format = IV_YUV_420P;
    i_ip.s_ivd_init_ip_t.u4_size = sizeof(i_ip);
    i_op.s_ivd_init_op_t.u4_size = sizeof(i_op);

    c->obj = (iv_obj_t *)c->recs[0].pv_base;
    c->obj->pv_fxns = (void *)&impeg2d_api_function;
    c->obj->u4_size = sizeof(iv_obj_t);
    if (impeg2d_api_function(c->obj, &i_ip, &i_op) != IV_SUCCESS) {
        LOGE("init %x", i_op.s_ivd_init_op_t.u4_error_code);
        return 0;
    }

    /* un solo hilo: es SD y solo una celda; sin hilos propios que limpiar */
    impeg2d_ctl_set_num_cores_ip_t n_ip;
    impeg2d_ctl_set_num_cores_op_t n_op;
    memset(&n_ip, 0, sizeof(n_ip));
    n_ip.e_cmd = IVD_CMD_VIDEO_CTL;
    n_ip.e_sub_cmd = (IVD_CONTROL_API_COMMAND_TYPE_T)IMPEG2D_CMD_CTL_SET_NUM_CORES;
    n_ip.u4_num_cores = 1;
    n_ip.u4_size = sizeof(n_ip);
    n_op.u4_size = sizeof(n_op);
    impeg2d_api_function(c->obj, &n_ip, &n_op);

    return (jlong)(intptr_t)c;
}

/* Prepara el buffer de salida cuando ya se conocen las dimensiones. */
static int prepare_output(Ctx *c) {
    ivd_ctl_getbufinfo_ip_t ip;
    ivd_ctl_getbufinfo_op_t op;
    memset(&ip, 0, sizeof(ip));
    memset(&op, 0, sizeof(op));
    ip.e_cmd = IVD_CMD_VIDEO_CTL;
    ip.e_sub_cmd = IVD_CMD_CTL_GETBUFINFO;
    ip.u4_size = sizeof(ip);
    op.u4_size = sizeof(op);
    if (impeg2d_api_function(c->obj, &ip, &op) != IV_SUCCESS) return -1;
    uint32_t total = op.u4_min_out_buf_size[0];
    if (op.u4_min_num_out_bufs > 1) total += op.u4_min_out_buf_size[1];
    if (op.u4_min_num_out_bufs > 2) total += op.u4_min_out_buf_size[2];
    if (total > c->outcap) {
        free(c->outbuf);
        c->outbuf = (uint8_t *)malloc(total);
        if (!c->outbuf) { c->outcap = 0; return -1; }
        c->outcap = total;
    }
    c->out.u4_min_out_buf_size[0] = op.u4_min_out_buf_size[0];
    c->out.u4_min_out_buf_size[1] = op.u4_min_out_buf_size[1];
    c->out.u4_min_out_buf_size[2] = op.u4_min_out_buf_size[2];
    c->out.pu1_bufs[0] = c->outbuf;
    if (op.u4_min_num_out_bufs > 1) c->out.pu1_bufs[1] = c->out.pu1_bufs[0] + op.u4_min_out_buf_size[0];
    if (op.u4_min_num_out_bufs > 2) c->out.pu1_bufs[2] = c->out.pu1_bufs[1] + op.u4_min_out_buf_size[1];
    c->out.u4_num_bufs = op.u4_min_num_out_bufs;
    return 0;
}

/* Devuelve 1 si hay fotograma listo, 0 si no, <0 si hay error. */
JNIEXPORT jint JNICALL
Java_com_tunombre_tvbridge_Mpeg2Native_nativeDecode(JNIEnv *env, jclass clazz, jlong handle,
                                                    jobject buffer, jint offset, jint size, jint ts) {
    Ctx *c = (Ctx *)(intptr_t)handle;
    uint8_t *base = (uint8_t *)(*env)->GetDirectBufferAddress(env, buffer);
    if (!c || !base) return -1;
    uint8_t *data = base + offset;

    if (!c->header_done) {
        /* El primer paso lee la cabecera de secuencia (dimensiones); sin ella no hay nada que decodificar. */
        if (set_mode(c, IVD_DECODE_HEADER, 0) != 0) return -2;
        ivd_video_decode_ip_t ip;
        ivd_video_decode_op_t op;
        memset(&ip, 0, sizeof(ip));
        memset(&op, 0, sizeof(op));
        ip.e_cmd = IVD_CMD_VIDEO_DECODE;
        ip.u4_ts = ts;
        ip.pv_stream_buffer = data;
        ip.u4_num_Bytes = size;
        ip.u4_size = sizeof(ip);
        op.u4_size = sizeof(op);
        impeg2d_api_function(c->obj, &ip, &op);
        if (op.u4_pic_wd == 0 || op.u4_pic_ht == 0) return 0;
        c->width = op.u4_pic_wd;
        c->height = op.u4_pic_ht;
        if (set_mode(c, IVD_DECODE_FRAME, c->width) != 0) return -3;
        if (prepare_output(c) != 0) return -4;
        c->header_done = 1;
        LOGI("secuencia %ux%u", c->width, c->height);
    }

    ivd_video_decode_ip_t ip;
    ivd_video_decode_op_t op;
    memset(&ip, 0, sizeof(ip));
    memset(&op, 0, sizeof(op));
    ip.e_cmd = IVD_CMD_VIDEO_DECODE;
    ip.u4_ts = ts;
    ip.pv_stream_buffer = data;
    ip.u4_num_Bytes = size;
    ip.s_out_buffer = c->out;
    ip.u4_size = sizeof(ip);
    op.u4_size = sizeof(op);
    IV_API_CALL_STATUS_T st = impeg2d_api_function(c->obj, &ip, &op);
    if (st != IV_SUCCESS && !op.u4_output_present) return 0; /* flujo danado: se tolera y se sigue */

    /* Cambio de resolucion a mitad de flujo: se vuelve a preparar. */
    if (op.u4_new_seq && op.u4_pic_wd && (op.u4_pic_wd != c->width || op.u4_pic_ht != c->height)) {
        c->width = op.u4_pic_wd;
        c->height = op.u4_pic_ht;
        set_mode(c, IVD_DECODE_FRAME, c->width);
        prepare_output(c);
    }
    if (!op.u4_output_present) return 0;

    c->py = (uint8_t *)op.s_disp_frm_buf.pv_y_buf;
    c->pu = (uint8_t *)op.s_disp_frm_buf.pv_u_buf;
    c->pv = (uint8_t *)op.s_disp_frm_buf.pv_v_buf;
    c->fw = op.s_disp_frm_buf.u4_y_wd;
    c->fh = op.s_disp_frm_buf.u4_y_ht;
    c->ystride = op.s_disp_frm_buf.u4_y_strd;
    c->ustride = op.s_disp_frm_buf.u4_u_strd;
    c->vstride = op.s_disp_frm_buf.u4_v_strd;
    c->ts = op.u4_ts;
    return 1;
}

/* info = {ancho, alto, ystride, uvstride, ts} del ultimo fotograma. */
JNIEXPORT void JNICALL
Java_com_tunombre_tvbridge_Mpeg2Native_nativeFrameInfo(JNIEnv *env, jclass clazz, jlong handle, jintArray info) {
    Ctx *c = (Ctx *)(intptr_t)handle;
    jint v[5] = {(jint)c->fw, (jint)c->fh, (jint)c->ystride, (jint)c->ustride, (jint)c->ts};
    (*env)->SetIntArrayRegion(env, info, 0, 5, v);
}

/* Copia Y, U y V (con sus anchos de linea) a dst: yStride*alto + 2 * uvStride*(alto+1)/2. */
JNIEXPORT jboolean JNICALL
Java_com_tunombre_tvbridge_Mpeg2Native_nativeCopyFrame(JNIEnv *env, jclass clazz, jlong handle, jobject dst, jint capacity) {
    Ctx *c = (Ctx *)(intptr_t)handle;
    uint8_t *out = (uint8_t *)(*env)->GetDirectBufferAddress(env, dst);
    if (!c || !out || !c->py) return JNI_FALSE;
    uint32_t uvh = (c->fh + 1) / 2;
    size_t ysz = (size_t)c->ystride * c->fh, usz = (size_t)c->ustride * uvh;
    if ((size_t)capacity < ysz + 2 * usz) return JNI_FALSE;
    memcpy(out, c->py, ysz);
    memcpy(out + ysz, c->pu, usz);
    memcpy(out + ysz + usz, c->pv, usz);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_tunombre_tvbridge_Mpeg2Native_nativeFlush(JNIEnv *env, jclass clazz, jlong handle) {
    Ctx *c = (Ctx *)(intptr_t)handle;
    if (!c || !c->obj) return;
    ivd_ctl_reset_ip_t ip;
    ivd_ctl_reset_op_t op;
    memset(&ip, 0, sizeof(ip));
    memset(&op, 0, sizeof(op));
    ip.e_cmd = IVD_CMD_VIDEO_CTL;
    ip.e_sub_cmd = IVD_CMD_CTL_RESET;
    ip.u4_size = sizeof(ip);
    op.u4_size = sizeof(op);
    impeg2d_api_function(c->obj, &ip, &op);
    c->header_done = 0;
    c->py = c->pu = c->pv = NULL;
}

JNIEXPORT void JNICALL
Java_com_tunombre_tvbridge_Mpeg2Native_nativeRelease(JNIEnv *env, jclass clazz, jlong handle) {
    Ctx *c = (Ctx *)(intptr_t)handle;
    if (!c) return;
    if (c->recs) {
        /* recs[0] contiene el propio objeto del decodificador: se libera al final */
        for (uint32_t i = c->num_recs; i > 0; i--) free(c->recs[i - 1].pv_base);
        free(c->recs);
    }
    free(c->outbuf);
    free(c);
}
