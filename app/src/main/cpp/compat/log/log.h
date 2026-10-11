/* Sustituto minimo de <log/log.h> de la plataforma Android (libmpeg2 lo incluye en un par de sitios). */
#ifndef WB_COMPAT_LOG_H
#define WB_COMPAT_LOG_H
#include <android/log.h>
#define ALOGE(...) ((void)0)
#define ALOGW(...) ((void)0)
#define ALOGI(...) ((void)0)
#define ALOGD(...) ((void)0)
#define android_errorWriteLog(tag, subTag) ((void)0)
#endif
