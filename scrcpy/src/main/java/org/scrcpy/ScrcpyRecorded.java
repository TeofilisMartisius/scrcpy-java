package org.scrcpy;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.global.swscale;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.PointerPointer;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.sws_scale;

/**
 * Scrcpy implementation that plays back a video, and remembers all events pushed.
 * Used for testing. Borrowed most of the code from:
 * <p>
 * https://github.com/vzhn/ffmpeg-java-samples/blob/master/src/main/java/DemuxAndDecodeH264.java
 * <p>
 * TODO: remember events and time when they happened
 * TODO: Playback speed? Play back at the right speed? Right now playback is as fast as decoder will run.
 * TODO: generalize and reuse with Scrcpy
 */
public class ScrcpyRecorded implements IScrcpy {
    private final String videoFile;
    /**
     * Matroska format context
     */
    private AVFormatContext avfmtCtx;

    /**
     * Matroska video stream information
     */
    private AVStream videoStream;

    /**
     * matroska packet
     */
    private AVPacket avpacket;

    /**
     * H264 Decoder ID
     */
    private AVCodec codec;

    /**
     * H264 Decoder context
     */
    private AVCodecContext codecContext;

    /**
     * yuv420 frame
     */
    private AVFrame yuv420Frame;

    /**
     * number of frame
     */
    private int nframe;

    private boolean continueVideo = true;
    private Thread thread;

    public ScrcpyRecorded(String videoFile) throws IOException {
        av_log_set_level(AV_LOG_VERBOSE);
        this.videoFile = videoFile;
        openInput(videoFile);
        findVideoStream();
        initDecoder();
        initYuv420Frame();

        avpacket = new AVPacket();
    }

    private AVFormatContext openInput(String file) throws IOException {
        avfmtCtx = new AVFormatContext(null);
        BytePointer filePointer = new BytePointer(file);
        int r = avformat_open_input(avfmtCtx, filePointer, null, null);
        filePointer.deallocate();
        if (r < 0) {
            avfmtCtx.close();
            throw new IOException("avformat_open_input error: " + r);
        }
        return avfmtCtx;
    }

    private void findVideoStream() throws IOException {
        int r = avformat_find_stream_info(avfmtCtx, (PointerPointer) null);
        if (r < 0) {
            avformat_close_input(avfmtCtx);
            avfmtCtx.close();
            throw new IOException("error: " + r);
        }

        PointerPointer<AVCodec> decoderRet = new PointerPointer<>(1);
        int videoStreamNumber = av_find_best_stream(avfmtCtx, AVMEDIA_TYPE_VIDEO, -1, -1, decoderRet, 0);
        if (videoStreamNumber < 0) {
            throw new IOException("failed to find video stream");
        }

        if (decoderRet.get(AVCodec.class).id() != AV_CODEC_ID_H264) {
            throw new IOException("failed to find h264 stream");
        }
        decoderRet.deallocate();
        videoStream = avfmtCtx.streams(videoStreamNumber);
    }

    private void initDecoder() {
        codec = avcodec_find_decoder(AV_CODEC_ID_H264);
        codecContext = avcodec_alloc_context3(codec);
        if ((codec.capabilities() & AV_CODEC_CAP_TRUNCATED) != 0) {
            codecContext.flags(codecContext.flags() | AV_CODEC_CAP_TRUNCATED);
        }
        avcodec_parameters_to_context(codecContext, videoStream.codecpar());
        if (avcodec_open2(codecContext, codec, (PointerPointer) null) < 0) {
            throw new RuntimeException("Error: could not open codec.\n");
        }
    }

    private void initYuv420Frame() {
        yuv420Frame = av_frame_alloc();
        if (yuv420Frame == null) {
            throw new RuntimeException("Could not allocate video frame\n");
        }
    }

    private void free() {
        av_packet_unref(avpacket);
        avcodec_close(codecContext);
        avcodec_free_context(codecContext);

        av_frame_free(yuv420Frame);
        avformat_close_input(avfmtCtx);
        avformat_free_context(avfmtCtx);
    }

    private void processAVPacket(AVPacket avpacket) {
        int ret = avcodec_send_packet(codecContext, avpacket);
        if (ret < 0) {
            throw new RuntimeException("Error sending a packet for decoding\n");
        }
        receiveFrames();
    }

    private void receiveFrames() {
        int ret = 0;
        while (ret >= 0) {
            ret = avcodec_receive_frame(codecContext, yuv420Frame);
            if (ret == AVERROR_EAGAIN() || ret == AVERROR_EOF()) {
                continue;
            } else if (ret < 0) {
                throw new RuntimeException("error during decoding");
            }

            listeners.values().forEach(l -> {
                l.push(yuv420Frame);
            });
        }
    }

    public boolean start() {
        this.thread = new Thread("ScrcpyRecorded") {
            @Override
            public void run() {
                long time1 = System.nanoTime();
                while ((av_read_frame(avfmtCtx, avpacket)) >= 0) {
                    if (avpacket.stream_index() == videoStream.index()) {
                        processAVPacket(avpacket);
                    }
                    av_packet_unref(avpacket);
                }
                // now process delayed frames
                processAVPacket(null);
                long time2 = System.nanoTime();
                double ms = (time2 - time1) / 1_000_000.0;
                System.out.format("finished playback in %.2f ms\n", ms);
            }
        };
        this.thread.start();
        return true;
    }

    public void stop() {
        this.continueVideo = false;
        this.thread.interrupt();
        try {
            this.thread.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        free();
    }

    @Override
    public Dimension originalSize() {
        return new Dimension(codecContext.width(), codecContext.height());
    }

    // TODO: some way to unregister?
    private final Map<Consumer<BufferedImage>, ScreenListener> listeners = new HashMap<>();

    @Override
    public void registerScreenListener(Dimension size, Consumer<BufferedImage> onScreenRefresh) {
        ScreenListener listener = new ScreenListener(originalSize(), size, onScreenRefresh);
        listeners.put(onScreenRefresh, listener);
    }

    private class ScreenListener {
        final Dimension frameSize;
        final Dimension targetSize;
        final Consumer<BufferedImage> onScreenRefresh;
        final BufferedImage img;
        final AVFrame rgbFrame;
        final SwsContext swsContext;

        public ScreenListener(Dimension frameSize, Dimension targetSize, Consumer<BufferedImage> onScreenRefresh) {
            this.frameSize = frameSize;
            this.targetSize = targetSize;
            this.onScreenRefresh = onScreenRefresh;

            System.out.println("target=" + targetSize);
            System.out.println("target=" + targetSize.width);
            img = new BufferedImage(targetSize.width,
                    targetSize.height, BufferedImage.TYPE_3BYTE_BGR);

            rgbFrame = av_frame_alloc();
            rgbFrame.format(AV_PIX_FMT_BGR24);
            rgbFrame.width(targetSize.width);
            rgbFrame.height(targetSize.height);
            // align must be 1 for this to work, as Java aligns to 1
            int ret = av_image_alloc(rgbFrame.data(),
                    rgbFrame.linesize(),
                    rgbFrame.width(),
                    rgbFrame.height(),
                    rgbFrame.format(),
                    1);
            if (ret < 0) {
                throw new RuntimeException("could not allocate buffer!");
            }

            // Use this to scale inside ffmpeg. Faster than doing it in Java
            swsContext = swscale.sws_getContext(
                    frameSize.width, frameSize.height, AV_PIX_FMT_YUV420P,
                    rgbFrame.width(), rgbFrame.height(), rgbFrame.format(),
                    0, null, null, (DoublePointer) null);
        }

        public void push(AVFrame yuv420Frame) {
            sws_scale(swsContext, yuv420Frame.data(), yuv420Frame.linesize(), 0,
                    yuv420Frame.height(), rgbFrame.data(), rgbFrame.linesize());
            DataBufferByte buffer = (DataBufferByte) img.getRaster().getDataBuffer();
            rgbFrame.data(0).get(buffer.getData());

            onScreenRefresh.accept(img);
        }
    }

    @Override
    public void mouseDown(Point p, int buttons) {
        // TODO:
    }

    @Override
    public void mouseUp(Point p, int buttons) {
        // TODO:
    }

}
