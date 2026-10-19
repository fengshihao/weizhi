package com.weizhi;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.view.Surface;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * Device-capability probe for the GL encoder path (docs/video-architecture.md
 * P3b). A pure-Java grafika-style pipeline, no engine / plugin involved, that
 * documents what this device's AVC encoder accepts in Surface mode:
 *
 * - glClearFramesIntoEncoderSurface: EGL core + swap at several sizes. On
 *   MAA-AN00 (Honor, media.hncodec) configure() accepts width 160 but the
 *   component then dies with zero frames encoded, while 176x144 and up work;
 *   the capability dump in the failure message records the advertised ranges.
 * - decodeSurfaceTextureIntoEncoder: decoder -> SurfaceTexture(OES) -> GL draw
 *   -> encoder. The standard-Java twin of the plugin's AImageReader path;
 *   passing proves the full chain is a device capability.
 *
 * Codec errors surface as IllegalStateException with the collected log, which
 * this repo's test devices cannot get from logcat.
 */
@RunWith(AndroidJUnit4.class)
public final class GlEncoderProbeInstrumentedTest {

    private static final int WIDTH = 320;
    private static final int HEIGHT = 240;
    private static final int FPS = 30;
    private static final int FRAMES = 30;
    private static final long FRAME_NS = 1_000_000_000L / FPS;

    /** Minimal EGL setup on a codec surface, mirroring wt_gl_init(): ES2 +
     * window bit + RECORDABLE (0x3142) + RGB888. */
    private static final class EglCore {
        final EGLDisplay dpy;
        final EGLContext ctx;
        final EGLSurface surf;

        EglCore(Surface target) {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            assertTrue("eglGetDisplay", dpy != null && dpy != EGL14.EGL_NO_DISPLAY);
            int[] ver = new int[2];
            assertTrue("eglInitialize", EGL14.eglInitialize(dpy, ver, 0, ver, 1));
            int[] cfgAttr = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                    0x3142 /* EGL_RECORDABLE_ANDROID */, 1,
                    EGL14.EGL_NONE};
            EGLConfig[] configs = new EGLConfig[1];
            int[] n = new int[1];
            assertTrue("eglChooseConfig",
                    EGL14.eglChooseConfig(dpy, cfgAttr, 0, configs, 0, 1, n, 0) && n[0] > 0);
            int[] ctxAttr = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
            ctx = EGL14.eglCreateContext(dpy, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0);
            assertTrue("eglCreateContext", ctx != null && ctx != EGL14.EGL_NO_CONTEXT);
            int[] surfAttr = {EGL14.EGL_NONE};
            surf = EGL14.eglCreateWindowSurface(dpy, configs[0], target, surfAttr, 0);
            assertTrue("eglCreateWindowSurface", surf != null && surf != EGL14.EGL_NO_SURFACE);
            assertTrue("eglMakeCurrent", EGL14.eglMakeCurrent(dpy, surf, surf, ctx));
        }

        void release() {
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(dpy, surf);
            EGL14.eglDestroyContext(dpy, ctx);
            EGL14.eglTerminate(dpy);
        }
    }

    /** MP4 sink for the encoder output, tracking sample pts for diagnostics. */
    private static final class MuxerSink {
        final MediaMuxer muxer;
        int track = -1;
        boolean started;
        boolean eos;
        final List<Long> ptsUs = new ArrayList<>();

        MuxerSink(File file) throws IOException {
            muxer = new MediaMuxer(file.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        }

        void close() {
            try {
                if (started) {
                    muxer.stop();
                }
            } finally {
                muxer.release();
            }
        }
    }

    /** Drain encoder output into the sink; IllegalStateException carries the log
     * so the failure signature (samples before the error) lands in the report. */
    private static void drain(MediaCodec codec, MuxerSink sink, long timeoutMs, StringBuilder log)
            throws Exception {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int idx;
            try {
                idx = codec.dequeueOutputBuffer(info, timeoutMs);
            } catch (IllegalStateException e) {
                throw new IllegalStateException("encoder error state after "
                        + sink.ptsUs.size() + " samples; pts=" + sink.ptsUs + "\n" + log, e);
            }
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return;
            }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                sink.track = sink.muxer.addTrack(codec.getOutputFormat());
                sink.muxer.start();
                sink.started = true;
                log.append("format-changed\n");
                continue;
            }
            if (idx < 0) {
                continue;
            }
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                codec.releaseOutputBuffer(idx, false);
                continue;
            }
            if (!sink.started) {
                codec.releaseOutputBuffer(idx, false);
                continue;
            }
            if (info.size > 0) {
                ByteBuffer data = codec.getOutputBuffer(idx);
                data.position(info.offset);
                data.limit(info.offset + info.size);
                MediaCodec.BufferInfo w = new MediaCodec.BufferInfo();
                w.set(0, info.size, info.presentationTimeUs, info.flags);
                sink.muxer.writeSampleData(sink.track, data, w);
                sink.ptsUs.add(info.presentationTimeUs);
            }
            codec.releaseOutputBuffer(idx, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                sink.eos = true;
                return;
            }
        }
    }

    /** AVC encoder capability ranges, for the record (sizes the device claims
     * to support vs. what actually works in Surface mode). */
    private static String capabilityDump() {
        StringBuilder sb = new StringBuilder("avc encoders:\n");
        android.media.MediaCodecList list =
                new android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS);
        for (android.media.MediaCodecInfo ci : list.getCodecInfos()) {
            if (!ci.isEncoder()) {
                continue;
            }
            for (String type : ci.getSupportedTypes()) {
                if (!"video/avc".equals(type)) {
                    continue;
                }
                android.media.MediaCodecInfo.VideoCapabilities vc =
                        ci.getCapabilitiesForType(type).getVideoCapabilities();
                sb.append(ci.getName()).append(" widths=").append(vc.getSupportedWidths())
                        .append(" heights=").append(vc.getSupportedHeights())
                        .append(" align=").append(vc.getWidthAlignment()).append('x')
                        .append(vc.getHeightAlignment())
                        .append(" 160x120=").append(vc.isSizeSupported(160, 120))
                        .append(" 176x144=").append(vc.isSizeSupported(176, 144))
                        .append('\n');
            }
        }
        return sb.toString();
    }

    private static File newWorkspace(String prefix) {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File ws = new File(context.getCacheDir(), prefix + System.currentTimeMillis());
        assertTrue(ws.mkdirs());
        return ws;
    }

    private static MediaCodec configureSurfaceEncoder(int width, int height) throws IOException {
        MediaFormat fmt = MediaFormat.createVideoFormat("video/avc", width, height);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000);
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        MediaCodec codec = MediaCodec.createEncoderByType("video/avc");
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        return codec;
    }

    private static long videoDurationUs(File mp4) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(mp4.getAbsolutePath());
        try {
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    return ex.getTrackFormat(i).getLong(MediaFormat.KEY_DURATION);
                }
            }
            return -1;
        } finally {
            ex.release();
        }
    }

    /** Leg 1: synthesized GL frames (glClear) -> encoder input surface, at the
     * sizes the device's AVC codec accepts in Surface mode (176x144 QCIF and
     * 320x240; width 160 configures but encodes nothing — see class docs). */
    @Test
    public void glClearFramesIntoEncoderSurface() {
        int[][] sizes = {{176, 144}, {320, 240}};
        StringBuilder report = new StringBuilder(capabilityDump());
        for (int[] size : sizes) {
            try {
                glClearAtSize(size[0], size[1]);
                report.append(size[0]).append('x').append(size[1]).append(": PASS\n");
            } catch (Throwable t) {
                report.append(size[0]).append('x').append(size[1]).append(": FAIL ")
                        .append(t.getClass().getSimpleName()).append(": ")
                        .append(t.getMessage()).append("\n");
            }
        }
        assertTrue("size probe results:\n" + report,
                report.indexOf(": FAIL") < 0);
    }

    private static void glClearAtSize(int width, int height) throws Exception {
        File out = new File(newWorkspace("weizhi-glp1-" + width + "x" + height + "-"), "gl.mp4");
        MediaCodec codec = configureSurfaceEncoder(width, height);
        Surface input = codec.createInputSurface();
        codec.start();
        MuxerSink sink = new MuxerSink(out);
        EglCore egl = new EglCore(input);
        StringBuilder log = new StringBuilder();
        try {
            for (int i = 0; i < FRAMES; i++) {
                float g = (i % 2 == 0) ? 0.25f : 0.75f;
                GLES20.glClearColor(g, g, g, 1f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                EGLExt.eglPresentationTimeANDROID(egl.dpy, egl.surf, i * FRAME_NS);
                assertTrue("eglSwapBuffers failed at frame " + i + " (" + width + "x" + height + ")",
                        EGL14.eglSwapBuffers(egl.dpy, egl.surf));
                if (i % 8 == 0) {
                    drain(codec, sink, 0, log);
                }
            }
            codec.signalEndOfInputStream();
            long deadline = System.currentTimeMillis() + 10_000;
            while (!sink.eos && System.currentTimeMillis() < deadline) {
                drain(codec, sink, 50, log);
            }
            assertTrue(width + "x" + height + ": encoder EOS not reached; samples=" + sink.ptsUs
                    + "\n" + log, sink.eos);
            assertTrue(width + "x" + height + ": expected ~30 samples, got " + sink.ptsUs
                    + "\n" + log, sink.ptsUs.size() >= FRAMES - 2);
        } finally {
            egl.release();
            sink.close();
            try {
                codec.stop();
            } catch (IllegalStateException e) {
                /* already in error state; the try block carries the diagnosis */
            }
            codec.release();
            input.release();
        }
        /* probe only after the muxer finalized the file */
        long durUs = videoDurationUs(out);
        assertTrue(width + "x" + height + ": durationUs=" + durUs + " samples=" + sink.ptsUs,
                durUs > 800_000 && durUs < 1_400_000);
    }

    /** Leg 2: decoder -> SurfaceTexture(OES) -> GL draw -> encoder surface. */
    @Test
    public void decodeSurfaceTextureIntoEncoder() throws Exception {
        File ws = newWorkspace("weizhi-glp2-");
        File src = AvcFixtures.encode(ws, "in.mp4", 1000, 120);
        File out = new File(ws, "gl-dec.mp4");

        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        SurfaceTexture st = new SurfaceTexture(tex[0]);
        st.setDefaultBufferSize(WIDTH, HEIGHT);
        Surface decSurface = new Surface(st);

        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(src.getAbsolutePath());
        int track = -1;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                track = i;
                break;
            }
        }
        assertTrue("no video track", track >= 0);
        ex.selectTrack(track);
        MediaCodec decoder = MediaCodec.createDecoderByType("video/avc");
        decoder.configure(ex.getTrackFormat(track), decSurface, null, 0);
        decoder.start();

        MediaCodec encoder = configureSurfaceEncoder(WIDTH, HEIGHT);
        Surface input = encoder.createInputSurface();
        encoder.start();
        MuxerSink sink = new MuxerSink(out);
        EglCore egl = new EglCore(input);
        StringBuilder log = new StringBuilder();
        int prog = buildOesProgram();
        float[] texMatrix = new float[16];
        try {
            boolean inputEos = false;
            int frames = 0;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                int inIdx = decoder.dequeueInputBuffer(10_000);
                if (inIdx >= 0 && !inputEos) {
                    ByteBuffer dst = decoder.getInputBuffer(inIdx);
                    int size = ex.readSampleData(dst, 0);
                    if (size < 0) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputEos = true;
                    } else {
                        decoder.queueInputBuffer(inIdx, 0, size, ex.getSampleTime(), 0);
                        ex.advance();
                    }
                }
                int outIdx = decoder.dequeueOutputBuffer(info, 10_000);
                if (outIdx >= 0) {
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    decoder.releaseOutputBuffer(outIdx, !eos);
                    if (!eos) {
                        Thread.sleep(10); /* let the frame reach the texture */
                        st.updateTexImage();
                        st.getTransformMatrix(texMatrix);
                        drawOes(prog, tex[0], texMatrix, frames * FRAME_NS, egl);
                        frames++;
                    } else {
                        break;
                    }
                }
                drain(encoder, sink, 0, log);
            }
            assertTrue("decoded " + frames + " frames", frames >= FRAMES - 2);
            encoder.signalEndOfInputStream();
            long eosDeadline = System.currentTimeMillis() + 10_000;
            while (!sink.eos && System.currentTimeMillis() < eosDeadline) {
                drain(encoder, sink, 50, log);
            }
            assertTrue("encoder EOS not reached; samples=" + sink.ptsUs + "\n" + log, sink.eos);
            assertTrue("expected ~30 samples, got " + sink.ptsUs + "\n" + log,
                    sink.ptsUs.size() >= FRAMES - 2);
        } finally {
            egl.release();
            sink.close();
            encoder.stop();
            encoder.release();
            input.release();
            decoder.stop();
            decoder.release();
            decSurface.release();
            st.release();
            ex.release();
            GLES20.glDeleteProgram(prog);
            GLES20.glDeleteTextures(1, tex, 0);
        }
        /* probe only after the muxer finalized the file */
        long durUs = videoDurationUs(out);
        assertTrue("durationUs=" + durUs + " samples=" + sink.ptsUs,
                durUs > 800_000 && durUs < 1_400_000);
    }

    private static int buildOesProgram() {
        String vs = "attribute vec2 aPos; attribute vec2 aUV; uniform mat4 uTexMatrix;"
                + "varying vec2 vUV;"
                + "void main() { vUV = (uTexMatrix * vec4(aUV, 0.0, 1.0)).xy;"
                + " gl_Position = vec4(aPos, 0.0, 1.0); }";
        String fs = "#extension GL_OES_EGL_image_external : require\n"
                + "precision mediump float; varying vec2 vUV;"
                + "uniform samplerExternalOES uVideo;"
                + "void main() { gl_FragColor = texture2D(uVideo, vUV); }";
        return link(vs, fs);
    }

    private static int link(String vsSrc, String fsSrc) {
        int vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
        GLES20.glShaderSource(vs, vsSrc);
        GLES20.glCompileShader(vs);
        int fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
        GLES20.glShaderSource(fs, fsSrc);
        GLES20.glCompileShader(fs);
        int prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, vs);
        GLES20.glAttachShader(prog, fs);
        GLES20.glLinkProgram(prog);
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, ok, 0);
        assertTrue("program link failed: " + GLES20.glGetProgramInfoLog(prog), ok[0] != 0);
        return prog;
    }

    private static void drawOes(int prog, int texName, float[] texMatrix, long ptsNs,
            EglCore egl) {
        /* pos(2)+uv(2) per vertex, triangle strip quad */
        float[] quad = {
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f,
        };
        FloatBuffer buf = ByteBuffer.allocateDirect(quad.length * 4)
                .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
        buf.put(quad).position(0);
        GLES20.glUseProgram(prog);
        int aPos = GLES20.glGetAttribLocation(prog, "aPos");
        int aUV = GLES20.glGetAttribLocation(prog, "aUV");
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, buf);
        GLES20.glEnableVertexAttribArray(aUV);
        GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 16,
                buf.position(2) /* offset 8 bytes */);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texName);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uVideo"), 0);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uTexMatrix"),
                1, false, texMatrix, 0);
        GLES20.glViewport(0, 0, WIDTH, HEIGHT);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        EGLExt.eglPresentationTimeANDROID(egl.dpy, egl.surf, ptsNs);
        assertTrue("eglSwapBuffers failed at pts " + ptsNs,
                EGL14.eglSwapBuffers(egl.dpy, egl.surf));
    }
}
