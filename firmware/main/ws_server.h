#pragma once
#include <stddef.h>
#include "esp_err.h"

esp_err_t ws_server_start(void);

// Thread-safe: the data is copied and sent from the HTTP server task.
void ws_send_text(int fd, const char *text);
void ws_send_audio(int fd, const void *data, size_t len);

// Calls fn for every client that said hello (fn runs outside the client lock).
void ws_for_each_client(void (*fn)(int fd, const char *name, void *ctx), void *ctx);
