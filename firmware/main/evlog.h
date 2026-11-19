// Short human-readable event history for the test page (last 30 lines, RAM only).
#pragma once
#include "cJSON.h"

void evlog_init(void);
void evlog(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
void evlog_to_json(cJSON *array);  // newest first: [{"t": seconds since boot, "msg": "..."}]
