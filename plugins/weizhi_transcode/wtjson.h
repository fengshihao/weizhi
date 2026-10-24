#ifndef WEIZHI_TRANSCODE_WTJSON_H
#define WEIZHI_TRANSCODE_WTJSON_H

/*
 * Minimal JSON scanner for the weizhi_transcode plugin (same style as
 * plugins/weizhi_video/wjson.h; plugins stay self-contained per the IDL rules).
 * Schema:
 *  {"clips":[{"path":"a.mp4","startMs":0,"endMs":0,
 *             "fadeInMs":0,"fadeOutMs":0}],   // fades from transitions (P3c)
 *   "slides":[{"path":"p.jpg","durationMs":1500,"fadeInMs":0,"fadeOutMs":0}],
 *            // photo slideshow source (P3d); clips[] and slides[] are
 *            // mutually exclusive per job
 *   "audio":"music.m4a",   // slide jobs only: passthrough soundtrack
 *   "output":"out.mp4",
 *   "video":{"width":0,"height":0,"bitrate":0,"fps":0},   // 0 = keep source
 *   "filters":[{"shader":"...","uniforms":{"k":0.0},"lut":"x.cube"}]}
 */

#include <stddef.h>
#include <stdint.h>

#define WT_MAX_CLIPS 16
#define WT_MAX_SLIDES 64
#define WT_MAX_PATH 1024
#define WT_MAX_FILTERS 4
#define WT_MAX_SHADER 4096
#define WT_MAX_UNIFORMS 8
#define WT_MAX_UNIFORM_NAME 32

typedef struct WtClip {
    char path[WT_MAX_PATH];
    int64_t start_ms;
    int64_t end_ms; /* 0 = to end of source */
    int64_t fade_in_ms;  /* transition from the previous clip (0 = none) */
    int64_t fade_out_ms; /* transition into the next clip (0 = none) */
} WtClip;

/* One photo slide (P3d): decoded via AImageDecoder, drawn letterboxed for
   duration_ms at the job fps; fades reuse the clip transition math. */
typedef struct WtSlide {
    char path[WT_MAX_PATH];
    int64_t duration_ms;
    int64_t fade_in_ms;
    int64_t fade_out_ms;
} WtSlide;

typedef struct WtVideoOpts {
    int64_t width;
    int64_t height;
    int64_t bitrate;
    int64_t fps;
} WtVideoOpts;

/* One GPU filter: a complete GLES2 fragment shader (engine prepends the
   OES extension + precision lines) plus float uniforms. The weizhi:video
   package compiles presets and custom bodies into this shape. lut_path is
   optional: an Adobe/IRIDAS .cube 3D LUT uploaded to texture unit 1 with
   uLut/uLutSize uniforms bound by the plugin. */
typedef struct WtUniform {
    char name[WT_MAX_UNIFORM_NAME];
    double value;
} WtUniform;

typedef struct WtFilter {
    char shader[WT_MAX_SHADER];
    char lut_path[WT_MAX_PATH];
    WtUniform uniforms[WT_MAX_UNIFORMS];
    int nuniforms;
} WtFilter;

/* ---- timeline v2 (P5, docs/video-timeline-v2.md) ---- */
#define WT_MAX_TRACKS 8
#define WT_MAX_TRACK_CLIPS 64

typedef struct WtTransform {
    double x;        /* normalized, 0 = center */
    double y;
    double scale;    /* 1 = fit */
    double rotation; /* degrees, clockwise */
    double opacity;  /* [0, 1] */
} WtTransform;

/* Keyframe entries (P5c): compiler guarantees per-property ascending t. */
#define WT_MAX_KFS 32
#define WT_KF_X 0
#define WT_KF_Y 1
#define WT_KF_SCALE 2
#define WT_KF_ROTATION 3
#define WT_KF_OPACITY 4
#define WT_KF_GAIN 5

typedef struct WtKfEntry {
    int prop;
    int64_t t_ms;
    double v;
    int ease; /* 0 = linear, 1 = smoothstep */
} WtKfEntry;

typedef struct WtV2Clip {
    char path[WT_MAX_PATH];
    char text[256]; /* text track clips: compiled, execution lands in P5d */
    int64_t start_ms;
    int64_t in_ms;
    int64_t out_ms;      /* video/audio: 0 = to end of source */
    int64_t duration_ms; /* image/text */
    int has_out;
    int has_duration;
    WtTransform transform;
    WtKfEntry kfs[WT_MAX_KFS];
    int nkfs;
    int has_gain_kf; /* audio gain keyframes: guarded to P5d */
    int has_filters; /* per-clip effects present (later phase) */
} WtV2Clip;

typedef struct WtTrack {
    int type; /* WT_TRACK_* */
    WtV2Clip clips[WT_MAX_TRACK_CLIPS];
    int nclips;
} WtTrack;

enum {
    WT_TRACK_VIDEO = 0,
    WT_TRACK_IMAGE = 1,
    WT_TRACK_TEXT = 2,
    WT_TRACK_AUDIO = 3,
};

typedef struct WtJobSpec {
    WtClip clips[WT_MAX_CLIPS];
    int nclips;
    WtSlide slides[WT_MAX_SLIDES];
    int nslides;
    char audio[WT_MAX_PATH]; /* slide jobs: soundtrack muxed in passthrough */
    char output[WT_MAX_PATH];
    WtVideoOpts video;
    WtFilter filters[WT_MAX_FILTERS];
    int nfilters;
    int v2; /* 1 = tracks[] form (P5b+) */
    WtTrack tracks[WT_MAX_TRACKS];
    int ntracks;
} WtJobSpec;

/* Returns 0 on success; err holds an English reason otherwise. */
int wt_parse_job(const char *json, WtJobSpec *spec, char *err, size_t err_len);

#endif
