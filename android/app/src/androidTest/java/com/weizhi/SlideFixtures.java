package com.weizhi;

import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

import static org.junit.Assert.assertTrue;

/**
 * Deterministic slide fixtures for the weizhi:video slideshow tests (P3d):
 * solid-gray JPEGs and a 440 Hz sine AAC soundtrack. No committed binaries,
 * same contract as {@link AvcFixtures}.
 */
public final class SlideFixtures {

    private SlideFixtures() {
    }

    /** Solid gray JPEG (RGB equal channels; luma lands near {@code gray} in
     * limited range after the JPEG round trip). */
    public static File jpeg(File dir, String name, int width, int height, int gray)
            throws Exception {
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bmp.eraseColor(0xFF000000 | (gray << 16) | (gray << 8) | gray);
        File out = new File(dir, name);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            assertTrue("jpeg compress failed", bmp.compress(Bitmap.CompressFormat.JPEG, 95, fos));
        } finally {
            bmp.recycle();
        }
        return out;
    }

    /** durationMs of a 440 Hz sine, mono 44.1 kHz AAC-LC in an m4a container. */
    public static File aac(File dir, String name, int durationMs) throws Exception {
        final int sampleRate = 44_100;
        final int frameSamples = 1024; // AAC frame size
        int samples = (int) Math.round(sampleRate * durationMs / 1000.0);
        File out = new File(dir, name);
        MediaFormat fmt =
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 64_000);
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();
        MediaMuxer muxer =
                new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int[] track = {-1};
        boolean[] started = {false};
        try {
            int sent = 0;
            long ptsUs = 0;
            while (true) {
                int inIdx = codec.dequeueInputBuffer(10_000);
                assertTrue("aac input timeout", inIdx >= 0);
                ByteBuffer in = codec.getInputBuffer(inIdx);
                assertTrue("aac input buffer null", in != null);
                in.clear();
                in.order(ByteOrder.LITTLE_ENDIAN);
                int n = Math.min(frameSamples, samples - sent);
                if (n <= 0) {
                    codec.queueInputBuffer(inIdx, 0, 0, ptsUs,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    drainAac(codec, muxer, track, started, true);
                    break;
                }
                ShortBuffer pcm = in.asShortBuffer();
                for (int i = 0; i < n; i++) {
                    double t = (sent + i) / (double) sampleRate;
                    pcm.put((short) (Math.sin(2 * Math.PI * 440.0 * t) * 8000.0));
                }
                codec.queueInputBuffer(inIdx, 0, n * 2, ptsUs, 0);
                ptsUs += n * 1_000_000L / sampleRate;
                sent += n;
                drainAac(codec, muxer, track, started, false);
            }
            assertTrue("aac track never configured", track[0] >= 0);
            return out;
        } finally {
            try {
                if (started[0]) {
                    muxer.stop();
                }
            } finally {
                muxer.release();
                codec.stop();
                codec.release();
            }
        }
    }

    private static void drainAac(MediaCodec codec, MediaMuxer muxer, int[] track,
            boolean[] started, boolean eos) throws Exception {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int idx = codec.dequeueOutputBuffer(info, eos ? 10_000 : 0);
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (eos) {
                    throw new IllegalStateException("aac encoder EOS timeout");
                }
                return;
            }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track[0] = muxer.addTrack(codec.getOutputFormat());
                muxer.start();
                started[0] = true;
                continue;
            }
            if (idx < 0) {
                continue;
            }
            if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    && started[0]) {
                ByteBuffer data = codec.getOutputBuffer(idx);
                data.position(info.offset);
                data.limit(info.offset + info.size);
                MediaCodec.BufferInfo write = new MediaCodec.BufferInfo();
                write.set(0, info.size, info.presentationTimeUs, MediaCodec.BUFFER_FLAG_KEY_FRAME);
                muxer.writeSampleData(track[0], data, write);
            }
            codec.releaseOutputBuffer(idx, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                return;
            }
        }
    }
}
