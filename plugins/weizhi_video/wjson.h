#ifndef WEIZHI_VIDEO_WJSON_H
#define WEIZHI_VIDEO_WJSON_H

#include <stddef.h>
#include <stdint.h>

/*
 * Minimal JSON scanner for the weizhi_video plugin (no engine internals here).
 * Sufficient for the job options schema:
 *   {"clips":[{"path":"a.mp4","startMs":0,"endMs":0}],"output":"out.mp4"}
 * Not a general parser: no unicode escapes, numbers are i64/double via strtod.
 */

#define WJSON_MAX_CLIPS 16
#define WJSON_MAX_PATH 1024

typedef struct WvClip {
    char path[WJSON_MAX_PATH];
    int64_t start_ms;
    int64_t end_ms; /* 0 = to end of source */
} WvClip;

typedef struct WvJobSpec {
    WvClip clips[WJSON_MAX_CLIPS];
    int nclips;
    char output[WJSON_MAX_PATH];
} WvJobSpec;

/* Returns 0 on success; err holds an English reason otherwise. */
int wv_parse_job(const char *json, WvJobSpec *spec, char *err, size_t err_len);

#endif
