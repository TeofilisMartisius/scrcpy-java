#include "device_msg.h"

#include <stdlib.h>
#include <string.h>

#include "util/buffer_util.h"
#include "util/log.h"

ssize_t
device_msg_deserialize(const unsigned char *buf, size_t len,
                       struct device_msg *msg) {
    if (len < 5) {
        // at least type + empty string length
        return 0; // not available
    }

    msg->type = buf[0];
    switch (msg->type) {
        case DEVICE_MSG_TYPE_CLIPBOARD: {
            size_t clipboard_len = buffer_read32be(&buf[1]);
            if (clipboard_len > len - 5) {
                return 0; // not available
            }
            char *text = malloc(clipboard_len + 1);
            if (!text) {
                LOGW("Could not allocate text for clipboard");
                return -1;
            }
            if (clipboard_len) {
                memcpy(text, &buf[5], clipboard_len);
            }
            text[clipboard_len] = '\0';

            msg->clipboard.text = text;
            return 5 + clipboard_len;
        }
        default:
            LOGW("Unknown device message type: %d", (int) msg->type);
            return -1; // error, we cannot recover
    }
}

void
device_msg_destroy(struct device_msg *msg) {
    if (msg->type == DEVICE_MSG_TYPE_CLIPBOARD) {
        free(msg->clipboard.text);
    }
}
