#ifndef WEIZHI_TRANSCODE_WTJSON_H
#define WEIZHI_TRANSCODE_WTJSON_H

/*
 * Minimal JSON scanner for the weizhi_transcode plugin (same style as
 * plugins/weizhi_video/wjson.h; plugins stay self-contained per the IDL rules).
 * Schema:
 *  {"clips":[{"path":"a.mp4","startMs":0,"endMs":0}],
 *   "output":"out.mp4",
 *   "video":{"width":0,"height":0,"bitrate":0,"fps":0}}   // 0 = keep source
 */

#include <stddef.h>
#include <stdint.h>

#define WT_MAX_CLIPS 16
#define WT_MAX_PATH 1024

typedef struct WtClip {
    char path[WT_MAX_PATH];
    int64_t start_ms;
    int64_t end_ms; /* 0 = to end of source */
} WtClip;

typedef struct WtVideoOpts {
    int64_t width;
    int64_t height;
    int64_t bitrate;
    int64_t fps;
} WtVideoOpts;

typedef struct WtJobSpec {
    WtClip clips[WT_MAX_CLIPS];
    int nclips;
    char output[WT_MAX_PATH];
    WtVideoOpts video;
    int nfilters; /* filters[] entries; execution lands with the GPU path (P3b) */
} WtJobSpec;

/* Returns 0 on success; err holds an English reason otherwise. */
int wt_parse_job(const char *json, WtJobSpec *spec, char *err, size_t err_len);

#endif
