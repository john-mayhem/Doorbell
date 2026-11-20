// Push notifications through Firebase Cloud Messaging (HTTP v1).
// Phones register their FCM token over the LAN (POST /api/register). Messages sent to every phone:
//   ring      someone is calling (urgent)
//   ring_end  a phone picked up, the others stop ringing (urgent)
//   call      the call is over: one history line {outcome, by, secs, opened, at}
#pragma once
#include <stdbool.h>
#include "cJSON.h"
#include "esp_err.h"

void fcm_init(void);
// `id` stays the same when the app is reinstalled (the token doesn't), so one phone = one entry.
esp_err_t fcm_register(const char *token, const char *name, const char *id);
// Async. `data` (string fields only, may be NULL) is owned and freed by fcm.
// Urgent = high priority with a short life; otherwise normal priority, kept for a day.
void fcm_notify(const char *type, cJSON *data, bool urgent);
void fcm_status(cJSON *root);
