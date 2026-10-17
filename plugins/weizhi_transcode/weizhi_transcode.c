/*
 * weizhi_transcode: re-encoding export plugin (docs/video-architecture.md P2).
 * Pipeline (Android system media APIs only, no third-party code):
 *   AMediaExtractor(video) -> AMediaCodec decoder --surface-tunnel-->
 *   AMediaCodec encoder (input surface) -> AMediaMuxer; audio tracks are copied
 *   through a second extractor without re-encoding.
 *
 * Trim rewrites each frame's pts via releaseOutputBufferAtTime() (ns there, us
 * elsewhere), so frames never leave native memory. Scaling / GPU filters are
 * P3 (EGL): all clips must share one resolution here. Long loops poll
 * host->cancel_poll. AMediaExtractor_getTrackFormat / AMediaCodec_getOutputFormat
 * results are caller-owned and deleted after use.
 */
#include "weizhi_transcode_api.h"
#include "weizhi_transcode_manifest.h"
#include "wtjson.h"

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaFormat.h>
#include <media/NdkMediaMuxer.h>

#include <android/native_window.h>

#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define WT_MAX_JOBS 4
#define WT_SAMPLE_BUF (2 * 1024 * 1024)
#define WT_MAX_TRACKS 8
#define WT_MAX_PENDING 512
#define WT_LAST_FRAME_GAP_US 33333
/* NdkMediaCodec.h has no key-frame constant; Java MediaMuxer expects 1<<0. */
#define WT_BUFFER_FLAG_KEY_FRAME 1u

typedef struct WtJob {
    int in_use;
    int64_t id;
    WtJobSpec spec;
} WtJob;

typedef struct WtSource {
    AMediaExtractor *ex;
    ssize_t track;
    char *mime;
    int32_t width;
    int32_t height;
    int32_t fps;
} WtSource;

/* Audio sample buffered until the muxer has the video track and can start. */
typedef struct WtPendingSample {
    size_t track;
    uint8_t *data;
    size_t size;
    int64_t pts_us;
    uint32_t flags;
} WtPendingSample;

typedef struct WtPipeline {
    AMediaMuxer *muxer;
    AMediaCodec *encoder;
    ANativeWindow *enc_surface;
    int out_fd;
    char *out_abs;
    uint8_t *buf;
    WtPendingSample pending[WT_MAX_PENDING];
    int npending;
    int muxer_started;
    ssize_t video_mux_track;
    ssize_t audio_mux_track;
    int64_t timeline_us;
    int cancelled;
    char *fail;
} WtPipeline;

static WeizhiPluginHost g_host;
static WtJob g_jobs[WT_MAX_JOBS];

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

static WtJob *find_job(int64_t id) {
    int i;
    if (id <= 0) {
        return NULL;
    }
    for (i = 0; i < WT_MAX_JOBS; i++) {
        if (g_jobs[i].in_use && g_jobs[i].id == id) {
            return &g_jobs[i];
        }
    }
    return NULL;
}

static char *json_err(const char *reason, const char *detail) {
    char *out = (char *)malloc(256);
    if (out == NULL) {
        return NULL;
    }
    if (detail != NULL) {
        snprintf(out, 256, "{\"error\":\"%.170s (%.50s)\"}", reason, detail);
    } else {
        snprintf(out, 256, "{\"error\":\"%.220s\"}", reason);
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
    return abs;
}

static void wt_fail(WtPipeline *p, const char *reason, const char *detail) {
    if (p->fail == NULL) {
        p->fail = json_err(reason, detail);
    }
}

/* Caller-owned format of a selected track (delete after use). */
static AMediaFormat *source_track_format(AMediaExtractor *ex, ssize_t track) {
    if (track < 0) {
        return NULL;
    }
    return AMediaExtractor_getTrackFormat(ex, (size_t)track);
}

/* Open an extractor on abs_path selecting the first track whose mime starts
   with kind ("video/" / "audio/"). 0 ok, -1 with err set. */
static int open_source(const char *abs_path, const char *kind, WtSource *src, char *err,
                       size_t err_len) {
    size_t ntracks;
    size_t i;
    int fd;
    off64_t len;
    memset(src, 0, sizeof(*src));
    src->track = -1;
    fd = open(abs_path, O_RDONLY);
    if (fd < 0) {
        snprintf(err, err_len, "file not found");
        return -1;
    }
    len = lseek(fd, 0, SEEK_END);
    lseek(fd, 0, SEEK_SET);
    src->ex = AMediaExtractor_new();
    if (AMediaExtractor_setDataSourceFd(src->ex, fd, 0, len) != AMEDIA_OK) {
        close(fd);
        AMediaExtractor_delete(src->ex);
        src->ex = NULL;
        snprintf(err, err_len, "media open failed");
        return -1;
    }
    close(fd);
    ntracks = AMediaExtractor_getTrackCount(src->ex);
    if (ntracks > WT_MAX_TRACKS) {
        ntracks = WT_MAX_TRACKS;
    }
    for (i = 0; i < ntracks; i++) {
        AMediaFormat *fmt = AMediaExtractor_getTrackFormat(src->ex, i);
        const char *mime = "";
        AMediaFormat_getString(fmt, AMEDIAFORMAT_KEY_MIME, &mime);
        if (mime != NULL && strncmp(mime, kind, strlen(kind)) == 0) {
            src->track = (ssize_t)i;
            src->mime = strdup(mime);
            AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_WIDTH, &src->width);
            AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, &src->height);
            AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_FRAME_RATE, &src->fps);
            AMediaFormat_delete(fmt);
            AMediaExtractor_selectTrack(src->ex, i);
            return 0;
        }
        AMediaFormat_delete(fmt);
    }
    AMediaExtractor_delete(src->ex);
    src->ex = NULL;
    snprintf(err, err_len, "no %strack", kind);
    return -1;
}

static void close_source(WtSource *src) {
    if (src->ex != NULL) {
        AMediaExtractor_delete(src->ex);
        src->ex = NULL;
    }
    free(src->mime);
    src->mime = NULL;
}

WEIZHI_PLUGIN_EXPORT int64_t weizhi_transcode_open_job(const char *options_json) {
    static int64_t next_id = 1;
    char err[160];
    int i;
    WtJobSpec spec;
    err[0] = '\0';
    if (options_json == NULL || wt_parse_job(options_json, &spec, err, sizeof(err)) != 0) {
        return 0;
    }
    for (i = 0; i < WT_MAX_JOBS; i++) {
        if (!g_jobs[i].in_use) {
            g_jobs[i].in_use = 1;
            g_jobs[i].id = next_id++;
            g_jobs[i].spec = spec;
            return g_jobs[i].id;
        }
    }
    return 0;
}

static void wt_pending_push(WtPipeline *p, size_t track, const uint8_t *data, size_t size,
                            int64_t pts_us, uint32_t flags) {
    uint8_t *copy;
    if (p->npending >= WT_MAX_PENDING) {
        wt_fail(p, "too much audio buffered before first encoded frame", NULL);
        return;
    }
    copy = (uint8_t *)malloc(size);
    if (copy == NULL) {
        wt_fail(p, "out of memory", NULL);
        return;
    }
    memcpy(copy, data, size);
    p->pending[p->npending].track = track;
    p->pending[p->npending].data = copy;
    p->pending[p->npending].size = size;
    p->pending[p->npending].pts_us = pts_us;
    p->pending[p->npending].flags = flags;
    p->npending++;
}

static void wt_pending_flush(WtPipeline *p) {
    int i;
    for (i = 0; i < p->npending; i++) {
        AMediaCodecBufferInfo info;
        memset(&info, 0, sizeof(info));
        info.size = p->pending[i].size;
        info.presentationTimeUs = p->pending[i].pts_us;
        info.flags = p->pending[i].flags;
        AMediaMuxer_writeSampleData(p->muxer, p->pending[i].track, p->pending[i].data, &info);
        free(p->pending[i].data);
        p->pending[i].data = NULL;
    }
    p->npending = 0;
}

static void wt_pending_free(WtPipeline *p) {
    int i;
    for (i = 0; i < p->npending; i++) {
        free(p->pending[i].data);
        p->pending[i].data = NULL;
    }
    p->npending = 0;
}

/* Drain the encoder: format change (adds video mux track), samples, EOS. */
static void wt_drain_encoder(WtPipeline *p, int64_t timeout_us, int *encoder_eos) {
    AMediaCodecBufferInfo info;
    while (p->fail == NULL && !p->cancelled) {
        ssize_t idx = AMediaCodec_dequeueOutputBuffer(p->encoder, &info, timeout_us);
        if (idx == AMEDIA_ERROR_UNKNOWN) {
            wt_fail(p, "encoder failed", NULL);
            return;
        }
        if (idx < 0) {
            return; /* TRY_AGAIN / FORMAT_CHANGED handled below at >=0 path */
        }
        if ((info.flags & AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) != 0) {
            AMediaCodec_releaseOutputBuffer(p->encoder, (size_t)idx, false);
            continue;
        }
        if (!p->muxer_started) {
            AMediaFormat *fmt = AMediaCodec_getOutputFormat(p->encoder);
            p->video_mux_track = AMediaMuxer_addTrack(p->muxer, fmt);
            AMediaFormat_delete(fmt);
            if (p->video_mux_track < 0) {
                wt_fail(p, "muxer track add failed", "video");
                AMediaCodec_releaseOutputBuffer(p->encoder, (size_t)idx, false);
                return;
            }
            AMediaMuxer_start(p->muxer);
            p->muxer_started = 1;
            wt_pending_flush(p);
        }
        if (info.size > 0) {
            uint8_t *data = AMediaCodec_getOutputBuffer(p->encoder, (size_t)idx, NULL);
            if (data != NULL) {
                AMediaCodecBufferInfo write;
                memset(&write, 0, sizeof(write));
                write.size = info.size;
                write.presentationTimeUs = info.presentationTimeUs;
                /* NDK encoder flags carry no key-frame bit; claim sync for every
                   sample (muxer hint) — remux path preserves the real bit. */
                write.flags = WT_BUFFER_FLAG_KEY_FRAME;
                AMediaMuxer_writeSampleData(p->muxer, (size_t)p->video_mux_track,
                                            data + info.offset, &write);
            }
        }
        AMediaCodec_releaseOutputBuffer(p->encoder, (size_t)idx, false);
        if ((info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0) {
            *encoder_eos = 1;
            return;
        }
    }
}

/* Audio passthrough for one clip: copy samples <= end_us to the muxer (or the
   pending queue before the muxer starts) on the output timeline. */
static void wt_pass_audio(WtPipeline *p, AMediaExtractor *ex, int64_t start_us, int64_t end_us) {
    for (;;) {
        int64_t t = AMediaExtractor_getSampleTime(ex);
        ssize_t sample;
        uint32_t flags;
        if (t < 0 || t > end_us) {
            return;
        }
        sample = AMediaExtractor_readSampleData(ex, p->buf, WT_SAMPLE_BUF);
        if (sample < 0) {
            return;
        }
        flags = AMediaExtractor_getSampleFlags(ex) & AMEDIAEXTRACTOR_SAMPLE_FLAG_SYNC
                    ? WT_BUFFER_FLAG_KEY_FRAME
                    : 0;
        {
            int64_t rel = t - start_us;
            int64_t pts = p->timeline_us + (rel > 0 ? rel : 0);
            if (p->muxer_started) {
                AMediaCodecBufferInfo info;
                memset(&info, 0, sizeof(info));
                info.size = (size_t)sample;
                info.presentationTimeUs = pts;
                info.flags = flags;
                AMediaMuxer_writeSampleData(p->muxer, (size_t)p->audio_mux_track, p->buf, &info);
            } else {
                wt_pending_push(p, (size_t)p->audio_mux_track, p->buf, (size_t)sample, pts, flags);
            }
        }
        if (!AMediaExtractor_advance(ex)) {
            return;
        }
    }
}

WEIZHI_PLUGIN_EXPORT char *weizhi_transcode_run_job(int64_t job_id, uint32_t on_progress) {
    WtJob *job = find_job(job_id);
    WtPipeline pipe;
    AMediaFormat *enc_fmt = NULL;
    char *result = NULL;
    char open_err[128];
    int32_t enc_w = 0;
    int32_t enc_h = 0;
    int32_t enc_fps = 0;
    int c;
    char progress[96];
    int progress_tick = 0;

    if (job == NULL) {
        return json_err("bad job handle", NULL);
    }
    if (job->spec.nfilters > 0) {
        /* Filters execute on the GPU path (EGL/GLSL, P3b); keep the contract honest. */
        return json_err("unsupported: filter (GPU path lands in P3b)", NULL);
    }
    memset(&pipe, 0, sizeof(pipe));
    pipe.video_mux_track = -1;
    pipe.audio_mux_track = -1;
    pipe.out_fd = -1;

    pipe.out_abs = resolved_or_error(job->spec.output);
    if (pipe.out_abs == NULL) {
        return json_err("out of memory", NULL);
    }
    if (pipe.out_abs[0] != '/') {
        return pipe.out_abs; /* resolve error json */
    }
    pipe.buf = (uint8_t *)malloc(WT_SAMPLE_BUF);
    if (pipe.buf == NULL) {
        free(pipe.out_abs);
        return json_err("out of memory", NULL);
    }
    pipe.out_fd = open(pipe.out_abs, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (pipe.out_fd < 0) {
        result = json_err("output open failed", pipe.out_abs);
        goto done;
    }
    pipe.muxer = AMediaMuxer_new(pipe.out_fd, AMEDIAMUXER_OUTPUT_FORMAT_MPEG_4);

    /* Encoder config defaults to the first clip's video track. */
    {
        char *abs = resolved_or_error(job->spec.clips[0].path);
        WtSource src;
        if (abs == NULL) {
            pipe.fail = json_err("out of memory", NULL);
            goto done;
        }
        if (abs[0] != '/') {
            pipe.fail = abs;
            goto done;
        }
        open_err[0] = '\0';
        if (open_source(abs, "video/", &src, open_err, sizeof(open_err)) != 0) {
            pipe.fail = json_err(open_err, job->spec.clips[0].path);
            free(abs);
            goto done;
        }
        enc_w = src.width;
        enc_h = src.height;
        enc_fps = src.fps > 0 ? src.fps : 30;
        close_source(&src);
        free(abs);
    }
    if (job->spec.video.width > 0 || job->spec.video.height > 0) {
        if ((int)job->spec.video.width != enc_w || (int)job->spec.video.height != enc_h) {
            pipe.fail = json_err("unsupported: scaling (P3; EGL not wired yet)", NULL);
            goto done;
        }
    }
    if (job->spec.video.fps > 0) {
        enc_fps = (int32_t)job->spec.video.fps;
    }
    if (enc_w <= 0 || enc_h <= 0) {
        pipe.fail = json_err("source has no video track", job->spec.clips[0].path);
        goto done;
    }

    enc_fmt = AMediaFormat_new();
    AMediaFormat_setString(enc_fmt, AMEDIAFORMAT_KEY_MIME, "video/avc");
    AMediaFormat_setInt32(enc_fmt, AMEDIAFORMAT_KEY_WIDTH, enc_w);
    AMediaFormat_setInt32(enc_fmt, AMEDIAFORMAT_KEY_HEIGHT, enc_h);
    AMediaFormat_setInt32(enc_fmt, AMEDIAFORMAT_KEY_BIT_RATE,
                          job->spec.video.bitrate > 0 ? (int32_t)job->spec.video.bitrate : 4000000);
    AMediaFormat_setInt32(enc_fmt, AMEDIAFORMAT_KEY_FRAME_RATE, enc_fps);
    AMediaFormat_setInt32(enc_fmt, AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, 1);
    pipe.encoder = AMediaCodec_createEncoderByType("video/avc");
    if (pipe.encoder == NULL ||
        AMediaCodec_configure(pipe.encoder, enc_fmt, NULL, NULL, AMEDIACODEC_CONFIGURE_FLAG_ENCODE) !=
            AMEDIA_OK) {
        pipe.fail = json_err("encoder setup failed", "video/avc");
        goto done;
    }
    if (AMediaCodec_createInputSurface(pipe.encoder, &pipe.enc_surface) != AMEDIA_OK ||
        AMediaCodec_start(pipe.encoder) != AMEDIA_OK) {
        pipe.fail = json_err("encoder start failed", NULL);
        goto done;
    }

    for (c = 0; c < job->spec.nclips && pipe.fail == NULL && !pipe.cancelled; c++) {
        WtClip *clip = &job->spec.clips[c];
        char *abs = resolved_or_error(clip->path);
        WtSource vsrc;
        AMediaCodec *decoder = NULL;
        int64_t start_us = clip->start_ms * 1000;
        int64_t end_us = clip->end_ms > 0 ? clip->end_ms * 1000 : (int64_t)1 << 62;
        int64_t last_rel = 0;
        int wrote_any = 0;
        int input_eos = 0;
        int decode_eos = 0;
        int enc_eos_dummy;

        if (abs == NULL) {
            pipe.fail = json_err("out of memory", NULL);
            goto clip_done;
        }
        if (abs[0] != '/') {
            pipe.fail = abs;
            abs = NULL;
            goto clip_done;
        }
        open_err[0] = '\0';
        if (open_source(abs, "video/", &vsrc, open_err, sizeof(open_err)) != 0) {
            pipe.fail = json_err(open_err, clip->path);
            goto clip_done;
        }
        if (vsrc.width != enc_w || vsrc.height != enc_h) {
            pipe.fail = json_err("unsupported: mixed clip resolutions (all clips must match output)",
                                 clip->path);
            close_source(&vsrc);
            goto clip_done;
        }
        {
            AMediaFormat *dec_fmt = source_track_format(vsrc.ex, vsrc.track);
            decoder = AMediaCodec_createDecoderByType(vsrc.mime);
            if (decoder == NULL || dec_fmt == NULL ||
                AMediaCodec_configure(decoder, dec_fmt, pipe.enc_surface, NULL, 0) != AMEDIA_OK) {
                AMediaFormat_delete(dec_fmt);
                pipe.fail = json_err("decoder setup failed", vsrc.mime);
                close_source(&vsrc);
                goto clip_done;
            }
            AMediaFormat_delete(dec_fmt);
        }
        if (AMediaCodec_start(decoder) != AMEDIA_OK) {
            pipe.fail = json_err("decoder start failed", NULL);
            close_source(&vsrc);
            goto clip_done;
        }
        if (start_us > 0) {
            AMediaExtractor_seekTo(vsrc.ex, (uint64_t)start_us, AMEDIAEXTRACTOR_SEEK_PREVIOUS_SYNC);
        }

        while (pipe.fail == NULL && !pipe.cancelled && !decode_eos) {
            ssize_t in_idx = AMediaCodec_dequeueInputBuffer(decoder, 0);
            if (in_idx >= 0 && !input_eos) {
                ssize_t sample = AMediaExtractor_readSampleData(vsrc.ex, pipe.buf, WT_SAMPLE_BUF);
                int64_t t = AMediaExtractor_getSampleTime(vsrc.ex);
                if (sample < 0 || t < 0 || t > end_us) {
                    AMediaCodec_queueInputBuffer(decoder, (size_t)in_idx, 0, 0, 0,
                                                 AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                    input_eos = 1;
                } else {
                    size_t cap = 0;
                    uint8_t *dst = AMediaCodec_getInputBuffer(decoder, (size_t)in_idx, &cap);
                    if (dst == NULL || (size_t)sample > cap) {
                        pipe.fail = json_err("decoder input failed", NULL);
                        break;
                    }
                    memcpy(dst, pipe.buf, (size_t)sample);
                    AMediaCodec_queueInputBuffer(decoder, (size_t)in_idx, 0, (size_t)sample, t, 0);
                    AMediaExtractor_advance(vsrc.ex);
                }
            }
            {
                AMediaCodecBufferInfo info;
                ssize_t out_idx = AMediaCodec_dequeueOutputBuffer(decoder, &info, 10 * 1000);
                if (out_idx == AMEDIA_ERROR_UNKNOWN) {
                    pipe.fail = json_err("decoder failed", NULL);
                    break;
                }
                if (out_idx >= 0) {
                    if ((info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0) {
                        AMediaCodec_releaseOutputBuffer(decoder, (size_t)out_idx, false);
                        decode_eos = 1;
                    } else {
                        int64_t t = info.presentationTimeUs;
                        if (t > end_us) {
                            AMediaCodec_releaseOutputBuffer(decoder, (size_t)out_idx, false);
                            decode_eos = 1;
                        } else if (t < start_us) {
                            /* pre-roll before the trim point: decode and drop */
                            AMediaCodec_releaseOutputBuffer(decoder, (size_t)out_idx, false);
                        } else {
                            int64_t rel = t - start_us;
                            /* render into the encoder input surface at the output pts (ns) */
                            AMediaCodec_releaseOutputBufferAtTime(
                                decoder, (size_t)out_idx, (pipe.timeline_us + rel) * 1000);
                            last_rel = rel;
                            wrote_any = 1;
                            progress_tick++;
                            if ((progress_tick % 15) == 0 && g_host.cb_invoke_str != NULL &&
                                g_host.engine != NULL) {
                                int64_t span = end_us - start_us;
                                int pct = span > 0 ? (int)((rel * 100) / span) : 100;
                                int overall =
                                    (int)((((double)c + (pct > 100 ? 100 : pct) / 100.0) /
                                           job->spec.nclips) * 90.0);
                                snprintf(progress, sizeof(progress),
                                         "{\"clip\":%d,\"clipPercent\":%d,\"percent\":%d}", c,
                                         pct > 100 ? 100 : pct, overall);
                                g_host.cb_invoke_str(g_host.engine, on_progress, progress);
                            }
                        }
                    }
                }
            }
            enc_eos_dummy = 0;
            wt_drain_encoder(&pipe, 0, &enc_eos_dummy);
            if (g_host.cancel_poll != NULL && g_host.engine != NULL &&
                g_host.cancel_poll(g_host.engine)) {
                pipe.cancelled = 1;
            }
        }
        close_source(&vsrc);

        /* audio passthrough on its own extractor, after the video pass */
        if (pipe.fail == NULL && !pipe.cancelled) {
            WtSource asrc;
            open_err[0] = '\0';
            if (open_source(abs, "audio/", &asrc, open_err, sizeof(open_err)) == 0) {
                if (pipe.audio_mux_track < 0) {
                    AMediaFormat *afmt = source_track_format(asrc.ex, asrc.track);
                    pipe.audio_mux_track = AMediaMuxer_addTrack(pipe.muxer, afmt);
                    AMediaFormat_delete(afmt);
                    if (pipe.audio_mux_track < 0) {
                        pipe.fail = json_err("muxer track add failed", "audio");
                    }
                }
                if (pipe.fail == NULL) {
                    if (start_us > 0) {
                        AMediaExtractor_seekTo(asrc.ex, (uint64_t)start_us,
                                               AMEDIAEXTRACTOR_SEEK_PREVIOUS_SYNC);
                    }
                    wt_pass_audio(&pipe, asrc.ex, start_us, end_us);
                }
                close_source(&asrc);
            } else if (strcmp(open_err, "no audio/track") != 0) {
                pipe.fail = json_err(open_err, clip->path);
            }
        }

        if (wrote_any) {
            pipe.timeline_us += last_rel + WT_LAST_FRAME_GAP_US;
        }

    clip_done:
        if (decoder != NULL) {
            AMediaCodec_stop(decoder);
            AMediaCodec_delete(decoder);
        }
        free(abs);
    }

    if (pipe.fail == NULL && !pipe.cancelled) {
        int encoder_eos = 0;
        AMediaCodec_signalEndOfInputStream(pipe.encoder);
        while (!encoder_eos && pipe.fail == NULL) {
            wt_drain_encoder(&pipe, 100 * 1000, &encoder_eos);
        }
    }

done:
    if (pipe.encoder != NULL) {
        AMediaCodec_stop(pipe.encoder);
        AMediaCodec_delete(pipe.encoder);
    }
    if (pipe.enc_surface != NULL) {
        ANativeWindow_release(pipe.enc_surface);
    }
    if (enc_fmt != NULL) {
        AMediaFormat_delete(enc_fmt);
    }
    if (pipe.muxer_started) {
        AMediaMuxer_stop(pipe.muxer);
    }
    if (pipe.muxer != NULL) {
        AMediaMuxer_delete(pipe.muxer);
    }
    if (pipe.out_fd >= 0) {
        close(pipe.out_fd);
    }
    free(pipe.buf);
    wt_pending_free(&pipe);
    if (pipe.cancelled) {
        unlink(pipe.out_abs);
        result = (char *)malloc(64);
        if (result != NULL) {
            strcpy(result, "{\"cancelled\":true}");
        }
    } else if (pipe.fail != NULL) {
        result = pipe.fail;
    } else {
        result = (char *)malloc(strlen(pipe.out_abs) + 160);
        if (result != NULL) {
            snprintf(result, strlen(pipe.out_abs) + 160,
                     "{\"ok\":true,\"output\":\"%s\",\"clips\":%d,\"durationMs\":%lld,"
                     "\"width\":%d,\"height\":%d}",
                     pipe.out_abs, job->spec.nclips, (long long)(pipe.timeline_us / 1000), enc_w,
                     enc_h);
        }
    }
    free(pipe.out_abs);
    return result;
}

WEIZHI_PLUGIN_EXPORT void weizhi_transcode_close_job(int64_t job_id) {
    WtJob *job = find_job(job_id);
    if (job != NULL) {
        job->in_use = 0;
    }
}
