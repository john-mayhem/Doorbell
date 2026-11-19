#pragma once
#include <stddef.h>
#include <stdint.h>
#include "esp_err.h"
#include "cJSON.h"

esp_err_t intercom_init(void);

// From the WebSocket server (any task).
void intercom_hello(int fd);
void intercom_command(int fd, const char *client_name, const char *cmd, const char *arg);
void intercom_uplink(int fd, const int16_t *pcm, size_t samples);
void intercom_client_gone(int fd);

// Adds the current state to a JSON object (for GET /status).
void intercom_status(cJSON *root);
