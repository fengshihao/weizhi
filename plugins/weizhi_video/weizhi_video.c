/*
 * weizhi_video: NDK media-stack plugin (docs/video-architecture.md P1).
 * Remux-only editing on top of Android system media APIs (§2.1 path A):
 *   probe     — AMediaExtractor track/duration metadata
 *   open/run/close_job — trim (startMs/endMs) + concat, sample-copy through
 *                        AMediaMuxer (no re-encode; same-codec clips required)
 * All script paths go through host->resolve_path (engine fs semantics).
 * Long loops poll host->cancel_poll and unwind promptly.
 */
#include "weizhi_video_api.h"
#include "weizhi_video_manifest.h"
#include "wjson.h"

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaFormat.h>
#include <media/NdkMediaMuxer.h>

#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define WV_MAX_JOBS 4
#define WV_SAMPLE_BUF (2 * 1024 * 1024)
#define WV_MAX_TRACKS 8
#define WV_MIME_LEN 64
#define WV_LAST_FRAME_GAP_US 33000
/* NdkMediaCodec.h has no key-frame constant; Java MediaMuxer expects 1<<0 here. */
#define WV_BUFFER_FLAG_KEY_FRAME 1u

typedef struct WvJob {
    int in_use;
    int64_t id;
    WvJobSpec spec;
} WvJob;

static WeizhiPluginHost g_host;
static WvJob g_jobs[WV_MAX_JOBS];

WEIZHI_PLUGIN_EXPORT const char *weizhi_plugin_manifest(void) {
    return WEIZHI_PLUGIN_MANIFEST_JSON;
}

WEIZHI_PLUGIN_EXPORT int weizhi_plugin_init(WeizhiPluginHost *host) {
    if (host == NULL) {
        return -1;
    }
    g_host = *host;
    memset(g_jobs, 0, sizeof(g_jobs));
    return 0;
}

static WvJob *find_job(int64_t id) {
    int i;
    if (id <= 0) {
        return NULL;
    }
    for (i = 0; i < WV_MAX_JOBS; i++) {
        if (g_jobs[i].in_use && g_jobs[i].id == id) {
            return &g_jobs[i];
        }
    }
    return NULL;
}

static char *json_err(const char *fmt, const char *detail) {
    char *out = (char *)malloc(256);
    if (out == NULL) {
        return NULL;
    }
    snprintf(out, 256, "{\"error\":\"%.200s\"}", fmt != NULL ? fmt : "media failed");
    if (detail != NULL) {
        /* Keep the leading reason; append detail when it fits. */
        size_t used = strlen(out);
        if (used + strlen(detail) + 3 < 256) {
            snprintf(out + used, 256 - used, " (%s)", detail);
            /* move the closing brace */
            {
                size_t len = strlen(out);
                out[len - 2] = ')';
                out[len - 1] = '}';
                out[len] = '\0';
            }
        }
    }
    return out;
}

static char *resolved_or_error(const char *raw) {
    char err[160];
    char *abs;
    err[0] = '\0';
    if (g_host.resolve_path == NULL || g_host.engine == NULL) {
        return json_err("unsupported: path resolution (host too old)", NULL);
    }
    abs = g_host.resolve_path(g_host.engine, raw != NULL ? raw : "", err, sizeof(err));
    if (abs == NULL) {
        return json_err(err[0] != '\0' ? err : "invalid path", NULL);
    }
    return abs; /* caller frees */
}

WEIZHI_PLUGIN_EXPORT char *weizhi_video_probe(const char *path) {
    char *abs = resolved_or_error(path);
    AMediaExtractor *ex = NULL;
    char *out = NULL;
    size_t ntracks;
    size_t i;
    int fd;
    off64_t len;
    media_status_t st;
    int64_t duration_us = 0;
    char *json;
    size_t cap;
    size_t used = 0;
    if (abs == NULL || abs[0] != '/') { /* error json from resolved_or_error */
        return abs;
    }
    fd = open(abs, O_RDONLY);
    if (fd < 0) {
        out = json_err("file not found", abs);
        free(abs);
        return out;
    }
    len = lseek(fd, 0, SEEK_END);
    lseek(fd, 0, SEEK_SET);
    ex = AMediaExtractor_new();
    st = AMediaExtractor_setDataSourceFd(ex, fd, 0, len);
    close(fd);
    if (st != AMEDIA_OK) {
        AMediaExtractor_delete(ex);
        out = json_err("media open failed", abs);
        free(abs);
        return out;
    }
    cap = 512 + strlen(abs);
    json = (char *)malloc(cap);
    if (json == NULL) {
        AMediaExtractor_delete(ex);
        free(abs);
        return NULL;
    }
    used += (size_t)snprintf(json + used, cap - used, "{\"ok\":true,\"path\":\"%s\",\"tracks\":[", abs);
    ntracks = AMediaExtractor_getTrackCount(ex);
    for (i = 0; i < ntracks && i < WV_MAX_TRACKS; i++) {
        AMediaFormat *fmt = AMediaExtractor_getTrackFormat(ex, i);
        const char *mime = "";
        const char *type = "data";
        int32_t w = 0;
        int32_t h = 0;
        int32_t fps = 0;
        int32_t rate = 0;
        int32_t ch = 0;
        int64_t dur = 0;
        char piece[256];
        if (fmt == NULL) {
            continue;
        }
        AMediaFormat_getString(fmt, AMEDIAFORMAT_KEY_MIME, &mime);
        AMediaFormat_getInt64(fmt, AMEDIAFORMAT_KEY_DURATION, &dur);
        if (dur > duration_us) {
            duration_us = dur;
        }
        if (strncmp(mime, "video/", 6) == 0) {
            type = "video";
            AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_WIDTH, &w);
            AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, &h);
            AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_FRAME_RATE, &fps);
        } else if (strncmp(mime, "audio/", 6) == 0) {
            type = "audio";
            AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_SAMPLE_RATE, &rate);
            AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &ch);
        }
        AMediaFormat_delete(fmt); /* caller-owned per NdkMediaExtractor.h */
        snprintf(piece, sizeof(piece),
                 "%s{\"index\":%d,\"type\":\"%s\",\"mime\":\"%s\",\"durationMs\":%lld,"
                 "\"width\":%d,\"height\":%d,\"fps\":%d,\"sampleRate\":%d,\"channels\":%d}",
                 i ? "," : "", (int)i, type, mime, (long long)(dur / 1000), w, h, fps, rate, ch);
        used += (size_t)snprintf(json + used, cap - used, "%s", piece);
    }
    used += (size_t)snprintf(json + used, cap - used, "],\"durationMs\":%lld}",
                             (long long)(duration_us / 1000));
    AMediaExtractor_delete(ex);
    free(abs);
    return json;
}

typedef struct WvMimeMap {
    char mime[WV_MIME_LEN];
    ssize_t mux_track;
} WvMimeMap;

WEIZHI_PLUGIN_EXPORT int64_t weizhi_video_open_job(const char *options_json) {
    static int64_t next_id = 1;
    char err[160];
    int i;
    WvJobSpec spec;
    err[0] = '\0';
    if (options_json == NULL || wv_parse_job(options_json, &spec, err, sizeof(err)) != 0) {
        return 0;
    }
    for (i = 0; i < WV_MAX_JOBS; i++) {
        if (!g_jobs[i].in_use) {
            g_jobs[i].in_use = 1;
            g_jobs[i].id = next_id++;
            g_jobs[i].spec = spec;
            return g_jobs[i].id;
        }
    }
    return 0;
}

WEIZHI_PLUGIN_EXPORT char *weizhi_video_run_job(int64_t job_id, uint32_t on_progress) {
    WvJob *job = find_job(job_id);
    WvMimeMap mimes[WV_MAX_TRACKS];
    int nmimes = 0;
    char *out_abs = NULL;
    char *result = NULL;
    AMediaMuxer *muxer = NULL;
    uint8_t *buf = NULL;
    int out_fd = -1;
    int started = 0;
    int cancelled = 0;
    int64_t timeline_us = 0;
    int c;
    char progress[96];

    if (job == NULL) {
        return json_err("bad job handle", NULL);
    }
    out_abs = resolved_or_error(job->spec.output);
    if (out_abs == NULL || out_abs[0] != '/') {
        return out_abs; /* resolve error json */
    }
    buf = (uint8_t *)malloc(WV_SAMPLE_BUF);
    if (buf == NULL) {
        free(out_abs);
        return json_err("out of memory", NULL);
    }
    out_fd = open(out_abs, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (out_fd < 0) {
        free(buf);
        result = json_err("output open failed", out_abs);
        free(out_abs);
        return result;
    }
    muxer = AMediaMuxer_new(out_fd, AMEDIAMUXER_OUTPUT_FORMAT_MPEG_4);
    memset(mimes, 0, sizeof(mimes));

    for (c = 0; c < job->spec.nclips && !cancelled; c++) {
        WvClip *clip = &job->spec.clips[c];
        char *abs = resolved_or_error(clip->path);
        AMediaExtractor *ex = NULL;
        ssize_t clip_map[WV_MAX_TRACKS];
        size_t ntracks;
        size_t i;
        int fd;
        off64_t len;
        int64_t start_us = clip->start_ms * 1000;
        int64_t end_us = clip->end_ms > 0 ? clip->end_ms * 1000 : (int64_t)1 << 62;
        int64_t last_pts = 0;
        int wrote_any = 0;
        int samples = 0;

        if (abs == NULL || abs[0] != '/') {
            result = abs != NULL ? abs : json_err("invalid path", clip->path);
            goto done;
        }
        fd = open(abs, O_RDONLY);
        if (fd < 0) {
            result = json_err("file not found", abs);
            free(abs);
            goto done;
        }
        len = lseek(fd, 0, SEEK_END);
        lseek(fd, 0, SEEK_SET);
        ex = AMediaExtractor_new();
        if (AMediaExtractor_setDataSourceFd(ex, fd, 0, len) != AMEDIA_OK) {
            close(fd);
            AMediaExtractor_delete(ex);
            result = json_err("media open failed", abs);
            free(abs);
            goto done;
        }
        close(fd);
        free(abs);

        ntracks = AMediaExtractor_getTrackCount(ex);
        if (ntracks > WV_MAX_TRACKS) {
            ntracks = WV_MAX_TRACKS;
        }
        for (i = 0; i < ntracks; i++) {
            AMediaExtractor_selectTrack(ex, i);
            clip_map[i] = -1;
        }
        for (i = 0; i < ntracks; i++) {
            AMediaFormat *fmt = AMediaExtractor_getTrackFormat(ex, i);
            const char *mime = "";
            int k;
            if (fmt == NULL) {
                continue;
            }
            AMediaFormat_getString(fmt, AMEDIAFORMAT_KEY_MIME, &mime);
            if (mime == NULL || mime[0] == '\0') {
                AMediaFormat_delete(fmt);
                continue;
            }
            for (k = 0; k < nmimes; k++) {
                if (strcmp(mimes[k].mime, mime) == 0) {
                    break;
                }
            }
            if (k == nmimes) {
                if (nmimes >= WV_MAX_TRACKS) {
                    AMediaFormat_delete(fmt);
                    continue;
                }
                mimes[k].mux_track = AMediaMuxer_addTrack(muxer, fmt);
                if (mimes[k].mux_track < 0) {
                    AMediaFormat_delete(fmt);
                    result = json_err("muxer track add failed", mime);
                    AMediaExtractor_delete(ex);
                    goto done;
                }
                snprintf(mimes[k].mime, WV_MIME_LEN, "%s", mime);
                nmimes++;
            }
            clip_map[i] = mimes[k].mux_track;
            AMediaFormat_delete(fmt); /* caller-owned per NdkMediaExtractor.h */
        }
        if (!started) {
            if (AMediaMuxer_start(muxer) != AMEDIA_OK) {
                result = json_err("muxer start failed", NULL);
                AMediaExtractor_delete(ex);
                goto done;
            }
            started = 1;
        }
        if (start_us > 0) {
            AMediaExtractor_seekTo(ex, (uint64_t)start_us, AMEDIAEXTRACTOR_SEEK_PREVIOUS_SYNC);
        }
        for (;;) {
            ssize_t size = AMediaExtractor_readSampleData(ex, buf, WV_SAMPLE_BUF);
            int64_t t;
            ssize_t ti;
            ssize_t mux_track;
            AMediaCodecBufferInfo info;
            uint32_t flags;
            if (size < 0) {
                break;
            }
            t = AMediaExtractor_getSampleTime(ex);
            if (t < 0 || t > end_us) {
                break;
            }
            ti = AMediaExtractor_getSampleTrackIndex(ex);
            if (ti < 0 || ti >= (ssize_t)ntracks || clip_map[ti] < 0) {
                AMediaExtractor_advance(ex);
                continue;
            }
            mux_track = clip_map[ti];
            flags = AMediaExtractor_getSampleFlags(ex);
            memset(&info, 0, sizeof(info));
            info.offset = 0;
            info.size = (size_t)size;
            info.presentationTimeUs = timeline_us + (t - start_us > 0 ? t - start_us : 0);
            info.flags = (flags & AMEDIAEXTRACTOR_SAMPLE_FLAG_SYNC) ? WV_BUFFER_FLAG_KEY_FRAME : 0;
            if (AMediaMuxer_writeSampleData(muxer, (size_t)mux_track, buf, &info) != AMEDIA_OK) {
                result = json_err("muxer write failed", NULL);
                AMediaExtractor_delete(ex);
                goto done;
            }
            last_pts = info.presentationTimeUs - timeline_us;
            wrote_any = 1;
            samples++;
            if ((samples % 30) == 0) {
                int64_t span = end_us - start_us;
                int clip_pct = span > 0 ? (int)((last_pts * 100) / span) : 100;
                int overall = (int)(((double)c + (clip_pct > 100 ? 100 : clip_pct) / 100.0) /
                                    job->spec.nclips * 100.0);
                if (g_host.cb_invoke_str != NULL && g_host.engine != NULL) {
                    snprintf(progress, sizeof(progress), "{\"clip\":%d,\"clipPercent\":%d,\"percent\":%d}",
                             c, clip_pct > 100 ? 100 : clip_pct, overall);
                    g_host.cb_invoke_str(g_host.engine, on_progress, progress);
                }
            }
            if (g_host.cancel_poll != NULL && g_host.engine != NULL &&
                g_host.cancel_poll(g_host.engine)) {
                cancelled = 1;
                break;
            }
            AMediaExtractor_advance(ex);
        }
        AMediaExtractor_delete(ex);
        if (wrote_any) {
            timeline_us += last_pts + WV_LAST_FRAME_GAP_US;
        }
    }

done:
    if (started) {
        AMediaMuxer_stop(muxer);
    }
    AMediaMuxer_delete(muxer);
    if (out_fd >= 0) {
        close(out_fd);
    }
    free(buf);
    if (cancelled) {
        unlink(out_abs);
        result = (char *)malloc(64);
        if (result != NULL) {
            strcpy(result, "{\"cancelled\":true}");
        }
    } else if (result == NULL) {
        result = (char *)malloc(strlen(out_abs) + 128);
        if (result != NULL) {
            snprintf(result, strlen(out_abs) + 128,
                     "{\"ok\":true,\"output\":\"%s\",\"clips\":%d,\"durationMs\":%lld}",
                     out_abs, job->spec.nclips, (long long)(timeline_us / 1000));
        }
    }
    free(out_abs);
    return result;
}

WEIZHI_PLUGIN_EXPORT void weizhi_video_close_job(int64_t job_id) {
    WvJob *job = find_job(job_id);
    if (job != NULL) {
        job->in_use = 0;
    }
}
