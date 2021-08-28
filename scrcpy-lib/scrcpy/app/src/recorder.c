#include "recorder.h"

#include <assert.h>
#include <libavutil/time.h>

#include "util/log.h"
#include "util/str_util.h"

/** Downcast packet_sink to recorder */
#define DOWNCAST(SINK) container_of(SINK, struct recorder, packet_sink)

static const AVRational SCRCPY_TIME_BASE = {1, 1000000}; // timestamps in us

static const AVOutputFormat *
find_muxer(const char *name) {
#ifdef SCRCPY_LAVF_HAS_NEW_MUXER_ITERATOR_API
    void *opaque = NULL;
#endif
    const AVOutputFormat *oformat = NULL;
    do {
#ifdef SCRCPY_LAVF_HAS_NEW_MUXER_ITERATOR_API
        oformat = av_muxer_iterate(&opaque);
#else
        oformat = av_oformat_next(oformat);
#endif
        // until null or containing the requested name
    } while (oformat && !strlist_contains(oformat->name, ',', name));
    return oformat;
}

static struct record_packet *
record_packet_new(const AVPacket *packet) {
    struct record_packet *rec = malloc(sizeof(*rec));
    if (!rec) {
        return NULL;
    }

    rec->packet = av_packet_alloc();
    if (!rec->packet) {
        free(rec);
        return NULL;
    }

    if (av_packet_ref(rec->packet, packet)) {
        av_packet_free(&rec->packet);
        free(rec);
        return NULL;
    }
    return rec;
}

static void
record_packet_delete(struct record_packet *rec) {
    av_packet_free(&rec->packet);
    free(rec);
}

static void
recorder_queue_clear(struct recorder_queue *queue) {
    while (!sc_queue_is_empty(queue)) {
        struct record_packet *rec;
        sc_queue_take(queue, next, &rec);
        record_packet_delete(rec);
    }
}

static const char *
recorder_get_format_name(enum sc_record_format format) {
    switch (format) {
        case SC_RECORD_FORMAT_MP4: return "mp4";
        case SC_RECORD_FORMAT_MKV: return "matroska";
        default: return NULL;
    }
}

static bool
recorder_write_header(struct recorder *recorder, const AVPacket *packet) {
    AVStream *ostream = recorder->ctx->streams[0];

    uint8_t *extradata = av_malloc(packet->size * sizeof(uint8_t));
    if (!extradata) {
        LOGC("Could not allocate extradata");
        return false;
    }

    // copy the first packet to the extra data
    memcpy(extradata, packet->data, packet->size);

    ostream->codecpar->extradata = extradata;
    ostream->codecpar->extradata_size = packet->size;

    int ret = avformat_write_header(recorder->ctx, NULL);
    if (ret < 0) {
        LOGE("Failed to write header to %s", recorder->filename);
        return false;
    }

    return true;
}

static void
recorder_rescale_packet(struct recorder *recorder, AVPacket *packet) {
    AVStream *ostream = recorder->ctx->streams[0];
    av_packet_rescale_ts(packet, SCRCPY_TIME_BASE, ostream->time_base);
}

static bool
recorder_write(struct recorder *recorder, AVPacket *packet) {
    if (!recorder->header_written) {
        if (packet->pts != AV_NOPTS_VALUE) {
            LOGE("The first packet is not a config packet");
            return false;
        }
        bool ok = recorder_write_header(recorder, packet);
        if (!ok) {
            return false;
        }
        recorder->header_written = true;
        return true;
    }

    if (packet->pts == AV_NOPTS_VALUE) {
        // ignore config packets
        return true;
    }

    recorder_rescale_packet(recorder, packet);
    return av_write_frame(recorder->ctx, packet) >= 0;
}

static int
run_recorder(void *data) {
    struct recorder *recorder = data;

    for (;;) {
        sc_mutex_lock(&recorder->mutex);

        while (!recorder->stopped && sc_queue_is_empty(&recorder->queue)) {
            sc_cond_wait(&recorder->queue_cond, &recorder->mutex);
        }

        // if stopped is set, continue to process the remaining events (to
        // finish the recording) before actually stopping

        if (recorder->stopped && sc_queue_is_empty(&recorder->queue)) {
            sc_mutex_unlock(&recorder->mutex);
            struct record_packet *last = recorder->previous;
            if (last) {
                // assign an arbitrary duration to the last packet
                last->packet->duration = 100000;
                bool ok = recorder_write(recorder, last->packet);
                if (!ok) {
                    // failing to write the last frame is not very serious, no
                    // future frame may depend on it, so the resulting file
                    // will still be valid
                    LOGW("Could not record last packet");
                }
                record_packet_delete(last);
            }
            break;
        }

        struct record_packet *rec;
        sc_queue_take(&recorder->queue, next, &rec);

        sc_mutex_unlock(&recorder->mutex);

        // recorder->previous is only written from this thread, no need to lock
        struct record_packet *previous = recorder->previous;
        recorder->previous = rec;

        if (!previous) {
            // we just received the first packet
            continue;
        }

        // config packets have no PTS, we must ignore them
        if (rec->packet->pts != AV_NOPTS_VALUE
            && previous->packet->pts != AV_NOPTS_VALUE) {
            // we now know the duration of the previous packet
            previous->packet->duration =
                rec->packet->pts - previous->packet->pts;
        }

        bool ok = recorder_write(recorder, previous->packet);
        record_packet_delete(previous);
        if (!ok) {
            LOGE("Could not record packet");

            sc_mutex_lock(&recorder->mutex);
            recorder->failed = true;
            // discard pending packets
            recorder_queue_clear(&recorder->queue);
            sc_mutex_unlock(&recorder->mutex);
            break;
        }
    }

    if (!recorder->failed) {
        if (recorder->header_written) {
            int ret = av_write_trailer(recorder->ctx);
            if (ret < 0) {
                LOGE("Failed to write trailer to %s", recorder->filename);
                recorder->failed = true;
            }
        } else {
            // the recorded file is empty
            recorder->failed = true;
        }
    }

    if (recorder->failed) {
        LOGE("Recording failed to %s", recorder->filename);
    } else {
        const char *format_name = recorder_get_format_name(recorder->format);
        LOGI("Recording complete to %s file: %s", format_name,
                                                  recorder->filename);
    }

    LOGD("Recorder thread ended");

    return 0;
}

static bool
recorder_open(struct recorder *recorder, const AVCodec *input_codec) {
    bool ok = sc_mutex_init(&recorder->mutex);
    if (!ok) {
        LOGC("Could not create mutex");
        return false;
    }

    ok = sc_cond_init(&recorder->queue_cond);
    if (!ok) {
        LOGC("Could not create cond");
        goto error_mutex_destroy;
    }

    sc_queue_init(&recorder->queue);
    recorder->stopped = false;
    recorder->failed = false;
    recorder->header_written = false;
    recorder->previous = NULL;

    const char *format_name = recorder_get_format_name(recorder->format);
    assert(format_name);
    const AVOutputFormat *format = find_muxer(format_name);
    if (!format) {
        LOGE("Could not find muxer");
        goto error_cond_destroy;
    }

    recorder->ctx = avformat_alloc_context();
    if (!recorder->ctx) {
        LOGE("Could not allocate output context");
        goto error_cond_destroy;
    }

    // contrary to the deprecated API (av_oformat_next()), av_muxer_iterate()
    // returns (on purpose) a pointer-to-const, but AVFormatContext.oformat
    // still expects a pointer-to-non-const (it has not be updated accordingly)
    // <https://github.com/FFmpeg/FFmpeg/commit/0694d8702421e7aff1340038559c438b61bb30dd>
    recorder->ctx->oformat = (AVOutputFormat *) format;

    av_dict_set(&recorder->ctx->metadata, "comment",
                "Recorded by scrcpy " SCRCPY_VERSION, 0);

    AVStream *ostream = avformat_new_stream(recorder->ctx, input_codec);
    if (!ostream) {
        goto error_avformat_free_context;
    }

    ostream->codecpar->codec_type = AVMEDIA_TYPE_VIDEO;
    ostream->codecpar->codec_id = input_codec->id;
    ostream->codecpar->format = AV_PIX_FMT_YUV420P;
    ostream->codecpar->width = recorder->declared_frame_size.width;
    ostream->codecpar->height = recorder->declared_frame_size.height;

    int ret = avio_open(&recorder->ctx->pb, recorder->filename,
                        AVIO_FLAG_WRITE);
    if (ret < 0) {
        LOGE("Failed to open output file: %s", recorder->filename);
        // ostream will be cleaned up during context cleaning
        goto error_avformat_free_context;
    }

    LOGD("Starting recorder thread");
    ok = sc_thread_create(&recorder->thread, run_recorder, "recorder",
                               recorder);
    if (!ok) {
        LOGC("Could not start recorder thread");
        goto error_avio_close;
    }

    LOGI("Recording started to %s file: %s", format_name, recorder->filename);

    return true;

error_avio_close:
    avio_close(recorder->ctx->pb);
error_avformat_free_context:
    avformat_free_context(recorder->ctx);
error_cond_destroy:
    sc_cond_destroy(&recorder->queue_cond);
error_mutex_destroy:
    sc_mutex_destroy(&recorder->mutex);

    return false;
}

static void
recorder_close(struct recorder *recorder) {
    sc_mutex_lock(&recorder->mutex);
    recorder->stopped = true;
    sc_cond_signal(&recorder->queue_cond);
    sc_mutex_unlock(&recorder->mutex);

    sc_thread_join(&recorder->thread, NULL);

    avio_close(recorder->ctx->pb);
    avformat_free_context(recorder->ctx);
    sc_cond_destroy(&recorder->queue_cond);
    sc_mutex_destroy(&recorder->mutex);
}

static bool
recorder_push(struct recorder *recorder, const AVPacket *packet) {
    sc_mutex_lock(&recorder->mutex);
    assert(!recorder->stopped);

    if (recorder->failed) {
        // reject any new packet (this will stop the stream)
        sc_mutex_unlock(&recorder->mutex);
        return false;
    }

    struct record_packet *rec = record_packet_new(packet);
    if (!rec) {
        LOGC("Could not allocate record packet");
        sc_mutex_unlock(&recorder->mutex);
        return false;
    }

    sc_queue_push(&recorder->queue, next, rec);
    sc_cond_signal(&recorder->queue_cond);

    sc_mutex_unlock(&recorder->mutex);
    return true;
}

static bool
recorder_packet_sink_open(struct sc_packet_sink *sink, const AVCodec *codec) {
    struct recorder *recorder = DOWNCAST(sink);
    return recorder_open(recorder, codec);
}

static void
recorder_packet_sink_close(struct sc_packet_sink *sink) {
    struct recorder *recorder = DOWNCAST(sink);
    recorder_close(recorder);
}

static bool
recorder_packet_sink_push(struct sc_packet_sink *sink, const AVPacket *packet) {
    struct recorder *recorder = DOWNCAST(sink);
    return recorder_push(recorder, packet);
}

bool
recorder_init(struct recorder *recorder,
              const char *filename,
              enum sc_record_format format,
              struct size declared_frame_size) {
    recorder->filename = strdup(filename);
    if (!recorder->filename) {
        LOGE("Could not strdup filename");
        return false;
    }

    recorder->format = format;
    recorder->declared_frame_size = declared_frame_size;

    static const struct sc_packet_sink_ops ops = {
        .open = recorder_packet_sink_open,
        .close = recorder_packet_sink_close,
        .push = recorder_packet_sink_push,
    };

    recorder->packet_sink.ops = &ops;

    return true;
}

void
recorder_destroy(struct recorder *recorder) {
    free(recorder->filename);
}
