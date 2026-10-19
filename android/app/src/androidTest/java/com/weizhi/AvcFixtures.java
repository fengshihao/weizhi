package com.weizhi;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;

/** Deterministic all-intra AVC fixtures for media plugin tests (no committed binaries). */
public final class AvcFixtures {
    public static final int WIDTH = 320;
    public static final int HEIGHT = 240;
    public static final int FPS = 30;
    public static final int DEFAULT_MS = 1000;

    private AvcFixtures() {
    }

    /** Every frame is a sync frame filled with a solid luma value, so trim
     * boundaries land exactly on the pts grid. */
    public static File encode(File dir, String name, int durationMs, int luma) throws Exception {
        return new FixtureEncoder(new File(dir, name),
                (int) Math.round(durationMs / 1000.0 * FPS), luma).run();
    }

    private static final class FixtureEncoder {
        private final File file;
        private final int frames;
        private final int luma;
        private MediaCodec codec;
        private MediaMuxer muxer;
        private int track = -1;
        private boolean muxStarted;

        FixtureEncoder(File file, int frames, int luma) {
            this.file = file;
            this.frames = frames;
            this.luma = luma;
        }

        File run() throws Exception {
            MediaFormat fmt =
                    MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0);
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            muxer = new MediaMuxer(file.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            try {
                long frameUs = TimeUnit.SECONDS.toMicros(1) / FPS;
                for (int i = 0; i < frames; i++) {
                    int inIdx = codec.dequeueInputBuffer(10_000);
                    assertTrue("encoder input timeout", inIdx >= 0);
                    fillYuvFrame(inIdx);
                    codec.queueInputBuffer(inIdx, 0, WIDTH * HEIGHT * 3 / 2, i * frameUs, 0);
                    drain(false);
                }
                int eosIdx = codec.dequeueInputBuffer(10_000);
                assertTrue("encoder eos input timeout", eosIdx >= 0);
                codec.queueInputBuffer(eosIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                drain(true);
                assertTrue("track never configured", track >= 0);
                return file;
            } finally {
                try {
                    if (muxStarted) {
                        muxer.stop();
                    }
                } finally {
                    muxer.release();
                    codec.stop();
                    codec.release();
                }
            }
        }

        private void fillYuvFrame(int index) {
            android.media.Image image = codec.getInputImage(index);
            assertTrue(image != null);
            android.media.Image.Plane[] planes = image.getPlanes();
            for (int p = 0; p < planes.length; p++) {
                ByteBuffer buf = planes[p].getBuffer();
                buf.rewind();
                byte fill = (byte) (p == 0 ? luma : 128);
                while (buf.hasRemaining()) {
                    buf.put(fill);
                }
            }
            image.close();
        }

        private void drain(boolean eos) throws Exception {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                int idx = codec.dequeueOutputBuffer(info, eos ? 10_000 : 0);
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (eos) {
                        throw new IllegalStateException("encoder EOS timeout");
                    }
                    return;
                }
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    muxStarted = true;
                    continue;
                }
                if (idx < 0) {
                    continue;
                }
                if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        && muxStarted) {
                    ByteBuffer data = codec.getOutputBuffer(idx);
                    data.position(info.offset);
                    data.limit(info.offset + info.size);
                    MediaCodec.BufferInfo write = new MediaCodec.BufferInfo();
                    write.set(0, info.size, info.presentationTimeUs,
                            info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME);
                    muxer.writeSampleData(track, data, write);
                }
                codec.releaseOutputBuffer(idx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    return;
                }
            }
        }
    }
}
