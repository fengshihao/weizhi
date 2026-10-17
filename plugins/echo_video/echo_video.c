/*
 * echo_video: channel PoC for the video pipeline (docs/video-architecture.md P0).
 * Demonstrates the IDL v2 surface the real weizhi_video plugin will use:
 *   - i64 session handles (0 = invalid),
 *   - str args / JSON str returns,
 *   - cb(s: str) progress events (same-call scoped, same-turn flush),
 *   - cancel_poll so a cancelled runJs exits a long loop promptly.
 * Frames are synthetic and deterministic (FNV-1a over frame indices); no media stack.
 */
#include "weizhi_echo_video_api.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define ECHO_VIDEO_MAX_SESSIONS 8
#define ECHO_VIDEO_MAX_DELAY_MS 200

typedef struct EchoVideoSession {
    int64_t id;
    int32_t frames;
    int64_t delay_ms;
    int in_use;
} EchoVideoSession;

static WeizhiPluginHost g_host;
static EchoVideoSession g_sessions[ECHO_VIDEO_MAX_SESSIONS];

WEIZHI_PLUGIN_EXPORT int weizhi_plugin_init(WeizhiPluginHost *host) {
    if (host == NULL) {
        return -1;
    }
    g_host = *host;
    memset(g_sessions, 0, sizeof(g_sessions));
    return 0;
}

static EchoVideoSession *find_session(int64_t id) {
    int i;
    if (id <= 0) {
        return NULL;
    }
    for (i = 0; i < ECHO_VIDEO_MAX_SESSIONS; i++) {
        if (g_sessions[i].in_use && g_sessions[i].id == id) {
            return &g_sessions[i];
        }
    }
    return NULL;
}

/* Extract a capped integer from a tiny options JSON like {"delayMs":5}; 0 when absent. */
static int64_t options_int(const char *json, const char *key, int64_t max) {
    const char *p;
    int64_t v;
    if (json == NULL) {
        return 0;
    }
    p = strstr(json, key);
    if (p == NULL) {
        return 0;
    }
    p = strchr(p, ':');
    if (p == NULL) {
        return 0;
    }
    v = strtoll(p + 1, NULL, 10);
    if (v < 0) {
        return 0;
    }
    return v > max ? max : v;
}

static void emit_progress(uint32_t cb, int32_t frame, int32_t frames) {
    char json[96];
    int percent;
    if (g_host.cb_invoke_str == NULL || g_host.engine == NULL || frames <= 0) {
        return;
    }
    percent = (int)((frame + 1) * 100 / frames);
    snprintf(json, sizeof(json), "{\"frame\":%d,\"percent\":%d}", (int)frame, percent);
    g_host.cb_invoke_str(g_host.engine, cb, json);
}

WEIZHI_PLUGIN_EXPORT char *weizhi_echo_video_probe_media(const char *path) {
    char err[128];
    char *resolved;
    char *out;
    const char *safe = path != NULL ? path : "";
    FILE *f;
    long size;
    err[0] = '\0';
    /* Path form is the script's choice (relative or absolute); semantics are the
       engine's: normalize under fs_root, escape -> error. See NATIVE_PLUGIN_IDL.md v2. */
    if (g_host.resolve_path == NULL || g_host.engine == NULL) {
        out = (char *)malloc(160);
        if (out != NULL) {
            strcpy(out, "{\"error\":\"unsupported: path resolution (host too old)\"}");
        }
        return out;
    }
    resolved = g_host.resolve_path(g_host.engine, safe, err, sizeof(err));
    if (resolved == NULL) {
        out = (char *)malloc(192);
        if (out != NULL) {
            snprintf(out, 192, "{\"error\":\"%.120s\"}", err[0] != '\0' ? err : "invalid path");
        }
        return out;
    }
    f = fopen(resolved, "rb");
    if (f == NULL) {
        out = (char *)malloc(256);
        if (out != NULL) {
            snprintf(out, 256, "{\"error\":\"file not found\",\"path\":\"%.160s\"}", resolved);
        }
        free(resolved);
        return out;
    }
    fseek(f, 0, SEEK_END);
    size = ftell(f);
    fclose(f);
    out = (char *)malloc(strlen(resolved) + 256);
    if (out != NULL) {
        snprintf(out, strlen(resolved) + 256,
                 "{\"ok\":true,\"path\":\"%s\",\"size\":%ld,"
                 "\"tracks\":[{\"type\":\"video\",\"codec\":\"h264\",\"width\":64,\"height\":64,\"fps\":30}],"
                 "\"synthetic\":true}",
                 resolved, size);
    }
    free(resolved);
    return out;
}

WEIZHI_PLUGIN_EXPORT int64_t weizhi_echo_video_open_session(int32_t frame_count, const char *options_json) {
    int i;
    static int64_t next_id = 1;
    if (frame_count <= 0) {
        return 0;
    }
    for (i = 0; i < ECHO_VIDEO_MAX_SESSIONS; i++) {
        if (!g_sessions[i].in_use) {
            g_sessions[i].in_use = 1;
            g_sessions[i].id = next_id++;
            g_sessions[i].frames = frame_count;
            g_sessions[i].delay_ms = options_int(options_json, "delayMs", ECHO_VIDEO_MAX_DELAY_MS);
            return g_sessions[i].id;
        }
    }
    return 0; /* session table full */
}

WEIZHI_PLUGIN_EXPORT char *weizhi_echo_video_run_session(int64_t session, uint32_t on_progress) {
    EchoVideoSession *s = find_session(session);
    uint64_t hash = 1469598103934665603ULL; /* FNV-1a 64 offset basis */
    int32_t i;
    char *out;
    if (s == NULL) {
        out = (char *)malloc(48);
        if (out != NULL) {
            strcpy(out, "{\"error\":\"bad session handle\"}");
        }
        return out;
    }
    for (i = 0; i < s->frames; i++) {
        uint64_t v = (uint64_t)i;
        int b;
        for (b = 0; b < 8; b++) {
            hash ^= (v >> (b * 8)) & 0xff;
            hash *= 1099511628211ULL;
        }
        emit_progress(on_progress, i, s->frames);
        if (s->delay_ms > 0) {
            struct timespec ts;
            ts.tv_sec = s->delay_ms / 1000;
            ts.tv_nsec = (s->delay_ms % 1000) * 1000000L;
            nanosleep(&ts, NULL);
        }
        if (g_host.cancel_poll != NULL && g_host.engine != NULL && g_host.cancel_poll(g_host.engine)) {
            /* Cancelled: release the session here; JS-side cleanup cannot run after cancel. */
            s->in_use = 0;
            out = (char *)malloc(64);
            if (out != NULL) {
                snprintf(out, 64, "{\"cancelled\":true,\"frame\":%d}", (int)i);
            }
            return out;
        }
    }
    out = (char *)malloc(96);
    if (out != NULL) {
        snprintf(out, 96, "{\"cancelled\":false,\"frameCount\":%d,\"hash\":\"%016llx\"}",
                 (int)s->frames, (unsigned long long)hash);
    }
    return out;
}

WEIZHI_PLUGIN_EXPORT void weizhi_echo_video_close_session(int64_t session) {
    EchoVideoSession *s = find_session(session);
    if (s != NULL) {
        s->in_use = 0;
    }
}
