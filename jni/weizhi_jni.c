#include "weizhi.h"

#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static JavaVM *g_vm = NULL;
static jmethodID g_async_mid = NULL;
static jmethodID g_fetch_mid = NULL;
static jmethodID g_native_ensure_mid = NULL;
static jmethodID g_native_call_mid = NULL;
static jobject g_http_thiz = NULL;
static jobject g_native_thiz = NULL;

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
                                                               jlong js_stack, jint max_hosts, jlong fs_io,
                                                               jint max_async_io) {
    WeizhiLimits limits;
    (void)env;
    (void)clazz;
    memset(&limits, 0, sizeof(limits));
    limits.js_heap_bytes = (size_t)js_heap;
    limits.js_stack_bytes = (size_t)js_stack;
    limits.max_host_functions = (int)max_hosts;
    limits.fs_io_bytes = (size_t)fs_io;
    limits.max_async_io = (int)max_async_io;
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

JNIEXPORT jint JNICALL Java_com_weizhi_WeizhiEngine_nativeSetScriptFolder(JNIEnv *env, jclass clazz, jlong handle,
                                                                         jstring folder) {
    char *path = jstring_to_utf8(env, folder);
    int rc;
    (void)clazz;
    if (path == NULL) {
        return -1;
    }
    rc = weizhi_set_script_folder((WeizhiEngine *)(intptr_t)handle, path);
    free(path);
    return rc;
}

JNIEXPORT void JNICALL Java_com_weizhi_WeizhiEngine_nativeCancel(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    weizhi_cancel((WeizhiEngine *)(intptr_t)handle);
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

static int java_http_async(WeizhiEngine *engine, int64_t request_id, const char *method, const char *url,
                           const char *headers_json, const WeizhiBytes *body, void *userdata) {
    JNIEnv *env = NULL;
    jobject engine_obj = (jobject)userdata;
    jstring jmethod;
    jstring jurl;
    jstring jheaders;
    jbyteArray jbody = NULL;
    int attached = 0;
    (void)engine;
    if (g_vm == NULL || engine_obj == NULL || g_fetch_mid == NULL) {
        return -1;
    }
    if ((*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*g_vm)->AttachCurrentThread(g_vm, (void **)&env, NULL) != 0) {
            return -1;
        }
        attached = 1;
    }
    jmethod = (*env)->NewStringUTF(env, method != NULL ? method : "GET");
    jurl = (*env)->NewStringUTF(env, url != NULL ? url : "");
    jheaders = (*env)->NewStringUTF(env, headers_json != NULL ? headers_json : "{}");
    if (body != NULL && body->data != NULL && body->len > 0) {
        jbody = (*env)->NewByteArray(env, (jsize)body->len);
        if (jbody != NULL) {
            (*env)->SetByteArrayRegion(env, jbody, 0, (jsize)body->len, (const jbyte *)body->data);
        }
    }
    (*env)->CallVoidMethod(env, engine_obj, g_fetch_mid, (jlong)(intptr_t)engine, (jlong)request_id, jmethod, jurl,
                           jheaders, jbody);
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

JNIEXPORT void JNICALL Java_com_weizhi_WeizhiEngine_nativeInstallJavaHttp(JNIEnv *env, jobject thiz, jlong handle) {
    WeizhiEngine *engine = (WeizhiEngine *)(intptr_t)handle;
    jclass cls;
    if (engine == NULL) {
        return;
    }
    cls = (*env)->GetObjectClass(env, thiz);
    g_fetch_mid = (*env)->GetMethodID(env, cls, "onFetchAsync",
                                      "(JJLjava/lang/String;Ljava/lang/String;Ljava/lang/String;[B)V");
    if (g_http_thiz != NULL) {
        (*env)->DeleteGlobalRef(env, g_http_thiz);
    }
    g_http_thiz = (*env)->NewGlobalRef(env, thiz);
    weizhi_set_http(engine, java_http_async, g_http_thiz);
}

JNIEXPORT void JNICALL Java_com_weizhi_WeizhiEngine_nativeCompleteFetch(JNIEnv *env, jclass clazz, jlong handle,
                                                                       jlong request_id, jint status,
                                                                       jstring headers_json, jbyteArray body,
                                                                       jstring error) {
    WeizhiBytes bytes;
    char *headers = NULL;
    char *err = NULL;
    (void)clazz;
    memset(&bytes, 0, sizeof(bytes));
    if (body != NULL) {
        jsize len = (*env)->GetArrayLength(env, body);
        if (len > 0) {
            bytes.data = malloc((size_t)len);
            if (bytes.data != NULL) {
                (*env)->GetByteArrayRegion(env, body, 0, len, (jbyte *)bytes.data);
                bytes.len = (size_t)len;
            }
        }
    }
    if (headers_json != NULL) {
        headers = jstring_to_utf8(env, headers_json);
    }
    if (error != NULL) {
        err = jstring_to_utf8(env, error);
    }
    weizhi_complete_fetch((WeizhiEngine *)(intptr_t)handle, (int64_t)request_id, (int)status, headers, &bytes, err);
    weizhi_bytes_free(&bytes);
    free(headers);
    free(err);
}

static int java_native_ensure(WeizhiEngine *engine, int64_t request_id, const char *name, void *userdata) {
    JNIEnv *env = NULL;
    jobject engine_obj = (jobject)userdata;
    jstring jname;
    int attached = 0;
    (void)engine;
    if (g_vm == NULL || engine_obj == NULL || g_native_ensure_mid == NULL) {
        return -1;
    }
    if ((*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*g_vm)->AttachCurrentThread(g_vm, (void **)&env, NULL) != 0) {
            return -1;
        }
        attached = 1;
    }
    jname = (*env)->NewStringUTF(env, name != NULL ? name : "");
    (*env)->CallVoidMethod(env, engine_obj, g_native_ensure_mid, (jlong)(intptr_t)engine, (jlong)request_id, jname);
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

static char *java_native_call(const char *plugin_name, const char *export_name, const char *args_json,
                              void *userdata) {
    JNIEnv *env = NULL;
    jobject engine_obj = (jobject)userdata;
    jstring jplugin;
    jstring jexport;
    jstring jargs;
    jstring jresult;
    char *copy = NULL;
    int attached = 0;
    if (g_vm == NULL || engine_obj == NULL || g_native_call_mid == NULL) {
        return NULL;
    }
    if ((*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*g_vm)->AttachCurrentThread(g_vm, (void **)&env, NULL) != 0) {
            return NULL;
        }
        attached = 1;
    }
    jplugin = (*env)->NewStringUTF(env, plugin_name != NULL ? plugin_name : "");
    jexport = (*env)->NewStringUTF(env, export_name != NULL ? export_name : "");
    jargs = (*env)->NewStringUTF(env, args_json != NULL ? args_json : "[]");
    jresult = (jstring)(*env)->CallObjectMethod(env, engine_obj, g_native_call_mid, jplugin, jexport, jargs);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        if (attached) {
            (*g_vm)->DetachCurrentThread(g_vm);
        }
        return NULL;
    }
    if (jresult != NULL) {
        copy = jstring_to_utf8(env, jresult);
    }
    if (attached) {
        (*g_vm)->DetachCurrentThread(g_vm);
    }
    return copy;
}

JNIEXPORT void JNICALL Java_com_weizhi_WeizhiEngine_nativeInstallJavaNative(JNIEnv *env, jobject thiz, jlong handle) {
    WeizhiEngine *engine = (WeizhiEngine *)(intptr_t)handle;
    jclass cls;
    if (engine == NULL) {
        return;
    }
    cls = (*env)->GetObjectClass(env, thiz);
    g_native_ensure_mid = (*env)->GetMethodID(env, cls, "onNativeEnsure", "(JJLjava/lang/String;)V");
    g_native_call_mid =
        (*env)->GetMethodID(env, cls, "onNativeCall",
                            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    if (g_native_thiz != NULL) {
        (*env)->DeleteGlobalRef(env, g_native_thiz);
    }
    g_native_thiz = (*env)->NewGlobalRef(env, thiz);
    weizhi_set_native(engine, java_native_ensure, java_native_call, g_native_thiz);
}

JNIEXPORT jint JNICALL Java_com_weizhi_WeizhiEngine_nativeEnablePluginLoader(JNIEnv *env, jclass clazz, jlong handle,
                                                                            jstring folder) {
    char *path = jstring_to_utf8(env, folder);
    int rc;
    (void)clazz;
    if (path == NULL) {
        return -1;
    }
    rc = weizhi_enable_plugin_loader((WeizhiEngine *)(intptr_t)handle, path);
    free(path);
    return rc;
}

static jmethodID g_host_mid = NULL;

static char *java_host_call(const char *args_json, void *userdata) {
    JNIEnv *env = NULL;
    jobject engine_obj = (jobject)userdata;
    int attached = 0;
    jstring jargs;
    jstring jret;
    char *out;
    if (g_vm == NULL || engine_obj == NULL || g_host_mid == NULL) {
        return strdup("{\"error\":\"unsupported: host call\"}");
    }
    if ((*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*g_vm)->AttachCurrentThread(g_vm, (void **)&env, NULL) != 0) {
            return strdup("{\"error\":\"host call attach failed\"}");
        }
        attached = 1;
    }
    jargs = (*env)->NewStringUTF(env, args_json != NULL ? args_json : "null");
    jret = (jstring)(*env)->CallObjectMethod(env, engine_obj, g_host_mid, jargs);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        out = strdup("{\"error\":\"host call threw\"}");
    } else if (jret == NULL) {
        out = strdup("null");
    } else {
        out = jstring_to_utf8(env, jret);
        if (out == NULL) {
            out = strdup("null");
        }
    }
    if (attached) {
        (*g_vm)->DetachCurrentThread(g_vm);
    }
    return out;
}

JNIEXPORT jint JNICALL Java_com_weizhi_WeizhiEngine_nativeInstallHostCall(JNIEnv *env, jobject thiz, jlong handle) {
    WeizhiEngine *engine = (WeizhiEngine *)(intptr_t)handle;
    jclass cls;
    jobject global_thiz;
    if (engine == NULL) {
        return -1;
    }
    cls = (*env)->GetObjectClass(env, thiz);
    g_host_mid = (*env)->GetMethodID(env, cls, "onHostCall", "(Ljava/lang/String;)Ljava/lang/String;");
    if (g_host_mid == NULL) {
        return -1;
    }
    global_thiz = (*env)->NewGlobalRef(env, thiz);
    if (global_thiz == NULL || weizhi_add_function(engine, "__caps", java_host_call, global_thiz) != 0) {
        if (global_thiz != NULL) {
            (*env)->DeleteGlobalRef(env, global_thiz);
        }
        return -1;
    }
    return 0;
}

JNIEXPORT jbyteArray JNICALL Java_com_weizhi_WeizhiEngine_nativeResizeRgba(JNIEnv *env, jclass clazz, jlong handle,
                                                                          jbyteArray rgba, jint width, jint height,
                                                                          jint max_edge) {
    jbyte *in = NULL;
    jsize in_len = 0;
    uint8_t *out = NULL;
    size_t out_len = 0;
    jbyteArray result = NULL;
    (void)clazz;
    if (rgba != NULL) {
        in_len = (*env)->GetArrayLength(env, rgba);
        in = (*env)->GetByteArrayElements(env, rgba, NULL);
    }
    if (weizhi_plugin_resize_rgba((WeizhiEngine *)(intptr_t)handle, (const uint8_t *)in, (size_t)in_len, (int32_t)width,
                                  (int32_t)height, (int32_t)max_edge, &out, &out_len) == 0 &&
        out != NULL) {
        if (out_len <= 0x7fffffff) {
            result = (*env)->NewByteArray(env, (jsize)out_len);
            if (result != NULL) {
                (*env)->SetByteArrayRegion(env, result, 0, (jsize)out_len, (const jbyte *)out);
            }
        }
        free(out);
    }
    if (in != NULL) {
        (*env)->ReleaseByteArrayElements(env, rgba, in, JNI_ABORT);
    }
    return result;
}

JNIEXPORT void JNICALL Java_com_weizhi_WeizhiEngine_nativeCompleteNative(JNIEnv *env, jclass clazz, jlong handle,
                                                                        jlong request_id, jboolean ok,
                                                                        jstring plugin_json, jstring error) {
    char *json = NULL;
    char *err = NULL;
    (void)clazz;
    if (plugin_json != NULL) {
        json = jstring_to_utf8(env, plugin_json);
    }
    if (error != NULL) {
        err = jstring_to_utf8(env, error);
    }
    weizhi_complete_native((WeizhiEngine *)(intptr_t)handle, (int64_t)request_id, ok ? 1 : 0, json, err);
    free(json);
    free(err);
}
