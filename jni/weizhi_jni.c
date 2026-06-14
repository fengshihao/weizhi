#include "weizhi.h"

#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static JavaVM *g_vm = NULL;
static jmethodID g_async_mid = NULL;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_vm = vm;
    return JNI_VERSION_1_6;
}

static char *jstring_to_utf8(JNIEnv *env, jstring str) {
    const char *chars;
    char *copy;
    if (str == NULL) {
        return NULL;
    }
    chars = (*env)->GetStringUTFChars(env, str, NULL);
    if (chars == NULL) {
        return NULL;
    }
    copy = strdup(chars);
    (*env)->ReleaseStringUTFChars(env, str, chars);
    return copy;
}

JNIEXPORT jlong JNICALL Java_com_weizhi_WeizhiEngine_nativeOpen(JNIEnv *env, jclass clazz, jlong js_heap,
                                                               jlong js_stack, jint max_packs, jint max_hosts,
                                                               jlong wasm_stack, jlong wasm_heap, jlong wasm_linear,
                                                               jlong fs_io) {
    WeizhiLimits limits;
    (void)env;
    (void)clazz;
    memset(&limits, 0, sizeof(limits));
    limits.js_heap_bytes = (size_t)js_heap;
    limits.js_stack_bytes = (size_t)js_stack;
    limits.max_packs = (int)max_packs;
    limits.max_host_functions = (int)max_hosts;
    limits.wasm_stack_bytes = (size_t)wasm_stack;
    limits.wasm_heap_bytes = (size_t)wasm_heap;
    limits.wasm_max_linear_bytes = (size_t)wasm_linear;
    limits.fs_io_bytes = (size_t)fs_io;
    return (jlong)(intptr_t)weizhi_open(&limits);
}

JNIEXPORT void JNICALL Java_com_weizhi_WeizhiEngine_nativeClose(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    weizhi_close((WeizhiEngine *)(intptr_t)handle);
}

JNIEXPORT jint JNICALL Java_com_weizhi_WeizhiEngine_nativeSetFsRoot(JNIEnv *env, jclass clazz, jlong handle,
                                                                  jstring folder) {
    char *path = jstring_to_utf8(env, folder);
    int rc;
    (void)clazz;
    if (path == NULL) {
        return -1;
    }
    rc = weizhi_set_fs_root((WeizhiEngine *)(intptr_t)handle, path);
    free(path);
    return rc;
}

JNIEXPORT jint JNICALL Java_com_weizhi_WeizhiEngine_nativeSetPackFolder(JNIEnv *env, jclass clazz, jlong handle,
                                                                      jstring folder) {
    char *path = jstring_to_utf8(env, folder);
    int rc;
    (void)clazz;
    if (path == NULL) {
        return -1;
    }
    rc = weizhi_set_pack_folder((WeizhiEngine *)(intptr_t)handle, path);
    free(path);
    return rc;
}

JNIEXPORT jstring JNICALL Java_com_weizhi_WeizhiEngine_nativeRunJs(JNIEnv *env, jclass clazz, jlong handle,
                                                                 jstring source, jint timeout_ms) {
    char *src = jstring_to_utf8(env, source);
    WeizhiResult result;
    jstring out;
    (void)clazz;
    if (src == NULL) {
        return NULL;
    }
    result = weizhi_run_js((WeizhiEngine *)(intptr_t)handle, src, (int)timeout_ms);
    free(src);
    if (result.ok && result.output_text != NULL) {
        out = (*env)->NewStringUTF(env, result.output_text);
    } else if (result.error != NULL) {
        size_t n = strlen(result.error);
        char *msg = malloc(n + 2);
        if (msg != NULL) {
            msg[0] = '!';
            memcpy(msg + 1, result.error, n + 1);
            out = (*env)->NewStringUTF(env, msg);
            free(msg);
        } else {
            out = (*env)->NewStringUTF(env, "!run failed");
        }
    } else {
        out = (*env)->NewStringUTF(env, "");
    }
    weizhi_result_free(&result);
    return out;
}

static int java_vfs_async(WeizhiEngine *engine, int64_t request_id, WeizhiVfsOp op, const char *relpath,
                          const char *relpath2, const WeizhiBytes *in, void *userdata) {
    JNIEnv *env = NULL;
    jobject engine_obj = (jobject)userdata;
    jstring jpath;
    jbyteArray jdata = NULL;
    int attached = 0;
    (void)relpath2;
    (void)engine;
    if (g_vm == NULL || engine_obj == NULL || g_async_mid == NULL) {
        return -1;
    }
    if ((*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*g_vm)->AttachCurrentThread(g_vm, (void **)&env, NULL) != 0) {
            return -1;
        }
        attached = 1;
    }
    jpath = (*env)->NewStringUTF(env, relpath != NULL ? relpath : "");
    if (in != NULL && in->data != NULL && in->len > 0) {
        jdata = (*env)->NewByteArray(env, (jsize)in->len);
        if (jdata != NULL) {
            (*env)->SetByteArrayRegion(env, jdata, 0, (jsize)in->len, (const jbyte *)in->data);
        }
    }
    (*env)->CallVoidMethod(env, engine_obj, g_async_mid, (jlong)(intptr_t)engine, (jlong)request_id, (jint)op, jpath,
                           jdata);
    if ((*env)->ExceptionCheck(env)) {
        if (attached) {
            (*g_vm)->DetachCurrentThread(g_vm);
        }
        return -1;
    }
    if (attached) {
        (*g_vm)->DetachCurrentThread(g_vm);
    }
    return 0;
}

JNIEXPORT void JNICALL Java_com_weizhi_WeizhiEngine_nativeInstallJavaAsyncVfs(JNIEnv *env, jobject thiz, jlong handle) {
    WeizhiEngine *engine = (WeizhiEngine *)(intptr_t)handle;
    jclass cls;
    jobject global_thiz;
    if (engine == NULL) {
        return;
    }
    cls = (*env)->GetObjectClass(env, thiz);
    g_async_mid = (*env)->GetMethodID(env, cls, "onVfsAsync", "(JJILjava/lang/String;[B)V");
    global_thiz = (*env)->NewGlobalRef(env, thiz);
    /* Keep built-in sync; only replace async with the Java Executor */
    weizhi_set_vfs(engine, NULL, java_vfs_async, global_thiz);
}

JNIEXPORT void JNICALL Java_com_weizhi_WeizhiEngine_nativeComplete(JNIEnv *env, jclass clazz, jlong handle,
                                                                  jlong request_id, jboolean ok, jbyteArray data,
                                                                  jstring error) {
    WeizhiBytes bytes;
    char *err = NULL;
    (void)clazz;
    memset(&bytes, 0, sizeof(bytes));
    if (data != NULL) {
        jsize len = (*env)->GetArrayLength(env, data);
        if (len > 0) {
            bytes.data = malloc((size_t)len);
            if (bytes.data != NULL) {
                (*env)->GetByteArrayRegion(env, data, 0, len, (jbyte *)bytes.data);
                bytes.len = (size_t)len;
            }
        }
    }
    if (error != NULL) {
        err = jstring_to_utf8(env, error);
    }
    weizhi_complete((WeizhiEngine *)(intptr_t)handle, (int64_t)request_id, ok ? 1 : 0, &bytes, err);
    weizhi_bytes_free(&bytes);
    free(err);
}
