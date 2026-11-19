#include <stdarg.h>
#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "evlog.h"

#define N 30

static struct {
    int64_t t_ms;
    char msg[72];
} lines[N];
static int head, count;
static SemaphoreHandle_t mtx;

void evlog_init(void) { mtx = xSemaphoreCreateMutex(); }

void evlog(const char *fmt, ...)
{
    if (!mtx) return;
    char buf[72];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    ESP_LOGI("event", "%s", buf);
    xSemaphoreTake(mtx, portMAX_DELAY);
    lines[head].t_ms = esp_timer_get_time() / 1000;
    snprintf(lines[head].msg, sizeof lines[head].msg, "%s", buf);
    head = (head + 1) % N;
    if (count < N) count++;
    xSemaphoreGive(mtx);
}

void evlog_to_json(cJSON *array)
{
    if (!mtx) return;
    xSemaphoreTake(mtx, portMAX_DELAY);
    for (int i = 0; i < count; i++) {
        int k = (head - 1 - i + N) % N;
        cJSON *o = cJSON_CreateObject();
        cJSON_AddNumberToObject(o, "t", (double)(lines[k].t_ms / 1000));
        cJSON_AddStringToObject(o, "msg", lines[k].msg);
        cJSON_AddItemToArray(array, o);
    }
    xSemaphoreGive(mtx);
}
