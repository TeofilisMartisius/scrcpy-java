#include "file_handler.h"

#include <assert.h>
#include <string.h>

#include "adb.h"
#include "util/log.h"

#define DEFAULT_PUSH_TARGET "/sdcard/Download/"

static void
file_handler_request_destroy(struct file_handler_request *req) {
    free(req->file);
}

bool
file_handler_init(struct file_handler *file_handler, const char *serial,
                  const char *push_target) {

    cbuf_init(&file_handler->queue);

    bool ok = sc_mutex_init(&file_handler->mutex);
    if (!ok) {
        return false;
    }

    ok = sc_cond_init(&file_handler->event_cond);
    if (!ok) {
        sc_mutex_destroy(&file_handler->mutex);
        return false;
    }

    if (serial) {
        file_handler->serial = strdup(serial);
        if (!file_handler->serial) {
            LOGW("Could not strdup serial");
            sc_cond_destroy(&file_handler->event_cond);
            sc_mutex_destroy(&file_handler->mutex);
            return false;
        }
    } else {
        file_handler->serial = NULL;
    }

    // lazy initialization
    file_handler->initialized = false;

    file_handler->stopped = false;
    file_handler->current_process = PROCESS_NONE;

    file_handler->push_target = push_target ? push_target : DEFAULT_PUSH_TARGET;

    return true;
}

void
file_handler_destroy(struct file_handler *file_handler) {
    sc_cond_destroy(&file_handler->event_cond);
    sc_mutex_destroy(&file_handler->mutex);
    free(file_handler->serial);

    struct file_handler_request req;
    while (cbuf_take(&file_handler->queue, &req)) {
        file_handler_request_destroy(&req);
    }
}

static process_t
install_apk(const char *serial, const char *file) {
    return adb_install(serial, file);
}

static process_t
push_file(const char *serial, const char *file, const char *push_target) {
    return adb_push(serial, file, push_target);
}

bool
file_handler_request(struct file_handler *file_handler,
                     file_handler_action_t action, char *file) {
    // start file_handler if it's used for the first time
    if (!file_handler->initialized) {
        if (!file_handler_start(file_handler)) {
            return false;
        }
        file_handler->initialized = true;
    }

    LOGI("Request to %s %s", action == ACTION_INSTALL_APK ? "install" : "push",
                             file);
    struct file_handler_request req = {
        .action = action,
        .file = file,
    };

    sc_mutex_lock(&file_handler->mutex);
    bool was_empty = cbuf_is_empty(&file_handler->queue);
    bool res = cbuf_push(&file_handler->queue, req);
    if (was_empty) {
        sc_cond_signal(&file_handler->event_cond);
    }
    sc_mutex_unlock(&file_handler->mutex);
    return res;
}

static int
run_file_handler(void *data) {
    struct file_handler *file_handler = data;

    for (;;) {
        sc_mutex_lock(&file_handler->mutex);
        file_handler->current_process = PROCESS_NONE;
        while (!file_handler->stopped && cbuf_is_empty(&file_handler->queue)) {
            sc_cond_wait(&file_handler->event_cond, &file_handler->mutex);
        }
        if (file_handler->stopped) {
            // stop immediately, do not process further events
            sc_mutex_unlock(&file_handler->mutex);
            break;
        }
        struct file_handler_request req;
        bool non_empty = cbuf_take(&file_handler->queue, &req);
        assert(non_empty);
        (void) non_empty;

        process_t process;
        if (req.action == ACTION_INSTALL_APK) {
            LOGI("Installing %s...", req.file);
            process = install_apk(file_handler->serial, req.file);
        } else {
            LOGI("Pushing %s...", req.file);
            process = push_file(file_handler->serial, req.file,
                                file_handler->push_target);
        }
        file_handler->current_process = process;
        sc_mutex_unlock(&file_handler->mutex);

        if (req.action == ACTION_INSTALL_APK) {
            if (process_check_success(process, "adb install", false)) {
                LOGI("%s successfully installed", req.file);
            } else {
                LOGE("Failed to install %s", req.file);
            }
        } else {
            if (process_check_success(process, "adb push", false)) {
                LOGI("%s successfully pushed to %s", req.file,
                                                     file_handler->push_target);
            } else {
                LOGE("Failed to push %s to %s", req.file,
                                                file_handler->push_target);
            }
        }

        sc_mutex_lock(&file_handler->mutex);
        // Close the process (it is necessary already terminated)
        // Execute this call with mutex locked to avoid race conditions with
        // file_handler_stop()
        process_close(file_handler->current_process);
        file_handler->current_process = PROCESS_NONE;
        sc_mutex_unlock(&file_handler->mutex);

        file_handler_request_destroy(&req);
    }
    return 0;
}

bool
file_handler_start(struct file_handler *file_handler) {
    LOGD("Starting file_handler thread");

    bool ok = sc_thread_create(&file_handler->thread, run_file_handler,
                               "file_handler", file_handler);
    if (!ok) {
        LOGC("Could not start file_handler thread");
        return false;
    }

    return true;
}

void
file_handler_stop(struct file_handler *file_handler) {
    sc_mutex_lock(&file_handler->mutex);
    file_handler->stopped = true;
    sc_cond_signal(&file_handler->event_cond);
    if (file_handler->current_process != PROCESS_NONE) {
        if (!process_terminate(file_handler->current_process)) {
            LOGW("Could not terminate push/install process");
        }
    }
    sc_mutex_unlock(&file_handler->mutex);
}

void
file_handler_join(struct file_handler *file_handler) {
    sc_thread_join(&file_handler->thread, NULL);
}
