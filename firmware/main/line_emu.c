// Doorbell emulator: the three handset functions, no hardware attached.
//
//  1. Call comes in   line_emu_ring()   (test page "Ring the apartment")
//  2. Talk            off hook -> audio: ECHO sends the phone's voice back, TONE sends a beep
//  3. Open the door   line_door_pulse() is logged and shown on the test page
//
// Like a real coordinate panel: it stops ringing after RING_TIMEOUT_S and ends a conversation
// after TALK_TIMEOUT_S; the visitor can also hang up (line_emu_release()).
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "evlog.h"
#include "line.h"

static const char *TAG = "line_emu";

static line_callbacks_t cb;
static SemaphoreHandle_t mtx;
static bool in_call, ringing, offhook;
static volatile line_emu_audio_t audio_mode = LINE_EMU_AUDIO_ECHO;
static volatile int64_t door_until_us;
static esp_timer_handle_t call_timer;  // ring timeout, then talk timeout

static void fire(line_evt_t evt)
{
    if (cb.on_event) cb.on_event(evt);
}

void line_emu_ring(void)
{
    xSemaphoreTake(mtx, portMAX_DELAY);
    bool start = !in_call;
    if (start) {
        in_call = ringing = true;
        esp_timer_stop(call_timer);
        esp_timer_start_once(call_timer, (uint64_t)CONFIG_DOORBELL_RING_TIMEOUT_S * 1000000);
    }
    xSemaphoreGive(mtx);
    if (!start) return;
    ESP_LOGI(TAG, "RING: the visitor calls the apartment");
    fire(LINE_EVT_RING);
}

static void release(const char *why)
{
    xSemaphoreTake(mtx, portMAX_DELAY);
    bool was = in_call;
    in_call = ringing = false;
    esp_timer_stop(call_timer);
    xSemaphoreGive(mtx);
    if (!was) return;
    ESP_LOGI(TAG, "RELEASED: %s", why);
    fire(LINE_EVT_RELEASED);
}

void line_emu_release(void)
{
    if (in_call) evlog("Visitor hung up");
    release("the visitor hung up");
}

static void call_timeout(void *arg)
{
    if (ringing) evlog("Nobody answered for %d s, the panel gave up", CONFIG_DOORBELL_RING_TIMEOUT_S);
    else evlog("Talk time over (%d s), the panel ended the call", CONFIG_DOORBELL_TALK_TIMEOUT_S);
    release("timeout");
}

void line_set_offhook(bool on)
{
    xSemaphoreTake(mtx, portMAX_DELAY);
    if (on == offhook) {
        xSemaphoreGive(mtx);
        return;
    }
    offhook = on;
    if (on && in_call && ringing) {
        ringing = false;
        esp_timer_stop(call_timer);
        esp_timer_start_once(call_timer, (uint64_t)CONFIG_DOORBELL_TALK_TIMEOUT_S * 1000000);
    } else if (!on && in_call) {
        // Hanging up ends the conversation on a coordinate line.
        in_call = ringing = false;
        esp_timer_stop(call_timer);
    }
    xSemaphoreGive(mtx);
    ESP_LOGI(TAG, "handset %s", on ? "lifted" : "down");
}

void line_door_pulse(void)
{
    if (!offhook) {
        ESP_LOGW(TAG, "open ignored: handset is down (the real button does nothing then either)");
        return;
    }
    ESP_LOGI(TAG, "DOOR: open button pressed for %d ms", CONFIG_DOORBELL_DOOR_PULSE_MS);
    door_until_us = esp_timer_get_time() + (int64_t)CONFIG_DOORBELL_DOOR_PULSE_MS * 1000;
}

void line_audio_to_door(const int16_t *pcm, size_t samples)
{
    if (audio_mode == LINE_EMU_AUDIO_ECHO && offhook && cb.on_audio) cb.on_audio(pcm, samples);
}

bool line_is_emulated(void) { return true; }
void line_emu_set_audio(line_emu_audio_t mode) { audio_mode = mode; }
line_emu_audio_t line_emu_get_audio(void) { return audio_mode; }
bool line_emu_door_open(void) { return esp_timer_get_time() < door_until_us; }

// TONE mode: 440 Hz, 300 ms on / 700 ms off, sent in 20 ms frames at real-time pace.
static void tone_task(void *arg)
{
    static int16_t frame[LINE_FRAME_SAMPLES];
    float phase = 0;
    const float step = 2.0f * (float)M_PI * 440.0f / LINE_AUDIO_RATE;
    uint32_t n = 0;
    TickType_t wake = xTaskGetTickCount();
    for (;;) {
        vTaskDelayUntil(&wake, pdMS_TO_TICKS(20));
        if (!(offhook && audio_mode == LINE_EMU_AUDIO_TONE && cb.on_audio)) continue;
        bool on = (n++ % 50) < 15;
        for (int i = 0; i < LINE_FRAME_SAMPLES; i++) {
            frame[i] = on ? (int16_t)(8000.0f * sinf(phase)) : 0;
            phase += step;
            if (phase > 2.0f * (float)M_PI) phase -= 2.0f * (float)M_PI;
        }
        cb.on_audio(frame, LINE_FRAME_SAMPLES);
    }
}

esp_err_t line_init(const line_callbacks_t *callbacks)
{
    cb = *callbacks;
    mtx = xSemaphoreCreateMutex();
    const esp_timer_create_args_t ct = {.callback = call_timeout, .name = "emu_call"};
    ESP_ERROR_CHECK(esp_timer_create(&ct, &call_timer));
    xTaskCreate(tone_task, "emu_tone", 3072, NULL, 6, NULL);
    ESP_LOGW(TAG, "EMULATOR: use the test page at http://%s.local/", CONFIG_DOORBELL_HOSTNAME);
    return ESP_OK;
}
