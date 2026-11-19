// Intercom state machine. Every event (line, phones, timers) goes through one queue and one task,
// so the state needs no locking. Only the audio path reads state/owner_fd directly.
//
//   IDLE --ring--> RINGING --answer--> TALKING --hangup / panel ends--> IDLE
//                  RINGING --open---> TALKING(open_only): lift, press open, hang up, IDLE
//                  RINGING --reject-> lift for 300 ms (ends the call on the panel), IDLE
#include <string.h>
#include <time.h>
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/task.h"
#include "esp_app_desc.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "evlog.h"
#include "fcm.h"
#include "intercom.h"
#include "line.h"
#include "ws_server.h"

static const char *TAG = "intercom";

typedef enum { IC_IDLE, IC_RINGING, IC_TALKING } ic_state_t;
static const char *const STATE_NAME[] = {"idle", "ringing", "talking"};

typedef enum { EV_HELLO, EV_CMD, EV_GONE, EV_LINE, EV_STEP } ev_kind_t;
typedef enum { STEP_REJECT_ONHOOK, STEP_OPEN_PRESS, STEP_OPEN_HANGUP } step_t;

typedef struct {
    ev_kind_t kind;
    int fd;
    int num;  // line_evt_t or step_t
    char name[32];
    char cmd[16];
    char arg[16];
} ev_t;

static QueueHandle_t q;
static esp_timer_handle_t step_timer;
static volatile int step_pending;

static volatile ic_state_t state = IC_IDLE;
static volatile int owner_fd = -1;
static volatile bool open_only;  // lifted only to open the door, no audio
static char owner_name[32];
static int64_t since_us;
static uint32_t n_calls, n_opens;

// The call in progress, sent to every phone as one history line when it ends.
static struct {
    time_t at;        // when the visitor rang (0 = no call)
    int64_t talk_us;  // when someone answered (0 = nobody talked)
    char by[32];      // who answered / declined / opened
    bool opened;
} call;

// ---------- messages ----------

static char *state_json(int fd, const char *reason)
{
    cJSON *o = cJSON_CreateObject();
    cJSON_AddStringToObject(o, "t", "state");
    cJSON_AddStringToObject(o, "state", STATE_NAME[state]);
    if (state == IC_TALKING) {
        cJSON_AddStringToObject(o, "owner", owner_name);
        cJSON_AddBoolToObject(o, "mine", fd == owner_fd);
        cJSON_AddBoolToObject(o, "open_only", open_only);
    }
    cJSON_AddNumberToObject(o, "since_ms", (double)((esp_timer_get_time() - since_us) / 1000));
    if (reason) cJSON_AddStringToObject(o, "reason", reason);
    char *s = cJSON_PrintUnformatted(o);
    cJSON_Delete(o);
    return s;
}

static void send_state(int fd, const char *name, void *reason)
{
    char *s = state_json(fd, reason);
    ws_send_text(fd, s);
    cJSON_free(s);
}

static void broadcast_state(const char *reason)
{
    // Phones that aren't connected learn about the call by push. When the call ends straight from
    // ringing (missed / declined), the "call" summary stops the ringing instead.
    static ic_state_t pushed = IC_IDLE;
    if (state == IC_RINGING && pushed != IC_RINGING) fcm_notify("ring", NULL, true);
    if (state == IC_TALKING && pushed == IC_RINGING) fcm_notify("ring_end", NULL, true);
    pushed = state;
    ws_for_each_client(send_state, (void *)reason);
}

static void send_text_cb(int fd, const char *name, void *text) { ws_send_text(fd, text); }

static void broadcast_event(const char *ev, const char *by)
{
    cJSON *o = cJSON_CreateObject();
    cJSON_AddStringToObject(o, "t", "event");
    cJSON_AddStringToObject(o, "ev", ev);
    if (by) cJSON_AddStringToObject(o, "by", by);
    char *s = cJSON_PrintUnformatted(o);
    cJSON_Delete(o);
    ws_for_each_client(send_text_cb, s);
    cJSON_free(s);
}

static void send_error(int fd, const char *msg)
{
    cJSON *o = cJSON_CreateObject();
    cJSON_AddStringToObject(o, "t", "error");
    cJSON_AddStringToObject(o, "msg", msg);
    char *s = cJSON_PrintUnformatted(o);
    cJSON_Delete(o);
    ws_send_text(fd, s);
    cJSON_free(s);
}

static void send_hello(int fd)
{
    cJSON *o = cJSON_CreateObject();
    cJSON_AddStringToObject(o, "t", "hello");
    cJSON_AddNumberToObject(o, "proto", 1);
    cJSON_AddStringToObject(o, "fw", esp_app_get_description()->version);
    cJSON_AddStringToObject(o, "device", CONFIG_DOORBELL_HOSTNAME);
    cJSON_AddBoolToObject(o, "emu", line_is_emulated());
    if (line_is_emulated())
        cJSON_AddStringToObject(o, "emu_audio", line_emu_get_audio() == LINE_EMU_AUDIO_TONE ? "tone" : "echo");
    cJSON *a = cJSON_AddObjectToObject(o, "audio");
    cJSON_AddNumberToObject(a, "rate", LINE_AUDIO_RATE);
    cJSON_AddNumberToObject(a, "frame", LINE_FRAME_SAMPLES);
    cJSON_AddStringToObject(a, "format", "s16le");
    char *s = cJSON_PrintUnformatted(o);
    cJSON_Delete(o);
    ws_send_text(fd, s);
    cJSON_free(s);
    send_state(fd, NULL, NULL);
}

static void hello_cb(int fd, const char *name, void *ctx) { send_hello(fd); }

// ---------- state changes ----------

static void set_state(ic_state_t s)
{
    state = s;
    since_us = esp_timer_get_time();
    ESP_LOGI(TAG, "state -> %s", STATE_NAME[s]);
}

static void push_summary(const char *reason)
{
    if (!call.at) return;
    const char *outcome = !strcmp(reason, "rejected") ? "declined"
                        : call.talk_us              ? "answered"
                        : call.opened               ? "opened"
                        : call.by[0]                ? "answered"
                                                    : "missed";
    int secs = call.talk_us ? (int)((esp_timer_get_time() - call.talk_us) / 1000000) : 0;
    char n[24];
    cJSON *d = cJSON_CreateObject();
    cJSON_AddStringToObject(d, "outcome", outcome);
    cJSON_AddStringToObject(d, "by", call.by);
    snprintf(n, sizeof n, "%d", secs);
    cJSON_AddStringToObject(d, "secs", n);
    cJSON_AddStringToObject(d, "opened", call.opened ? "1" : "0");
    snprintf(n, sizeof n, "%lld", (long long)call.at);
    cJSON_AddStringToObject(d, "at", n);
    fcm_notify("call", d, !strcmp(outcome, "missed"));  // only a missed call shows a notification
    call.at = 0;
}

static void go_idle(const char *reason)
{
    owner_fd = -1;
    owner_name[0] = 0;
    open_only = false;
    set_state(IC_IDLE);
    broadcast_state(reason);
    push_summary(reason);
}

static void take_line(int fd, const char *name, bool only_open)
{
    line_set_offhook(true);
    strlcpy(owner_name, name, sizeof owner_name);
    open_only = only_open;
    owner_fd = only_open ? -1 : fd;  // no audio when lifted just to open
    set_state(IC_TALKING);
}

static void step_after(step_t step, int ms)
{
    esp_timer_stop(step_timer);
    step_pending = step;
    esp_timer_start_once(step_timer, (uint64_t)ms * 1000);
}

static void on_command(const ev_t *e)
{
    const char *c = e->cmd;
    if (!strcmp(c, "answer")) {
        if (state == IC_RINGING) {
            take_line(e->fd, e->name, false);
            strlcpy(call.by, e->name, sizeof call.by);
            call.talk_us = esp_timer_get_time();
            evlog("%s answered, talking", e->name);
            broadcast_state("answered");
        } else if (state == IC_TALKING) {
            send_error(e->fd, "already answered");
        } else {
            send_error(e->fd, "nobody is calling");
        }
    } else if (!strcmp(c, "hangup")) {
        if (state == IC_TALKING) {
            line_set_offhook(false);
            evlog("%s hung up", e->name);
            go_idle("hangup");
        }
    } else if (!strcmp(c, "reject")) {
        if (state == IC_RINGING) {
            line_set_offhook(true);  // a short lift ends the call on the panel
            step_after(STEP_REJECT_ONHOOK, 300);
            strlcpy(call.by, e->name, sizeof call.by);
            evlog("%s declined the call", e->name);
            go_idle("rejected");
        }
    } else if (!strcmp(c, "open")) {
        if (state == IC_TALKING) {
            line_door_pulse();
            n_opens++;
            call.opened = true;
            evlog("%s opened the door", e->name);
            broadcast_event("door_opened", e->name);
        } else if (state == IC_RINGING) {
            take_line(e->fd, e->name, true);
            strlcpy(call.by, e->name, sizeof call.by);
            evlog("%s is opening the door without talking", e->name);
            broadcast_state("opening");
            step_after(STEP_OPEN_PRESS, 600);  // let the panel see the lift first
        } else {
            send_error(e->fd, "the panel only opens the door during a call");
        }
    } else if (!strcmp(c, "test_ring")) {
        if (line_is_emulated()) line_emu_ring();
        else send_error(e->fd, "test_ring works only on the emulator");
    } else if (!strcmp(c, "emu_audio")) {
        if (!line_is_emulated()) {
            send_error(e->fd, "emu_audio works only on the emulator");
        } else {
            line_emu_set_audio(!strcmp(e->arg, "tone") ? LINE_EMU_AUDIO_TONE : LINE_EMU_AUDIO_ECHO);
            ws_for_each_client(hello_cb, NULL);  // everyone sees the new mode
        }
    } else {
        send_error(e->fd, "unknown command");
    }
}

static void on_step(step_t step)
{
    switch (step) {
    case STEP_REJECT_ONHOOK:
        if (state == IC_IDLE) line_set_offhook(false);
        break;
    case STEP_OPEN_PRESS:
        if (state == IC_TALKING && open_only) {
            line_door_pulse();
            n_opens++;
            call.opened = true;
            evlog("Door opened");
            broadcast_event("door_opened", owner_name);
            step_after(STEP_OPEN_HANGUP, CONFIG_DOORBELL_DOOR_PULSE_MS + 800);
        }
        break;
    case STEP_OPEN_HANGUP:
        if (state == IC_TALKING && open_only) {
            line_set_offhook(false);
            go_idle("door_opened");
        }
        break;
    }
}

static void handle(const ev_t *e)
{
    switch (e->kind) {
    case EV_HELLO:
        send_hello(e->fd);
        break;
    case EV_CMD:
        ESP_LOGI(TAG, "%s: %s", e->name, e->cmd);
        on_command(e);
        break;
    case EV_GONE:
        if (state == IC_TALKING && e->fd == owner_fd) {
            evlog("The phone in the call disconnected, hung up");
            line_set_offhook(false);
            go_idle("phone_lost");
        }
        break;
    case EV_LINE:
        if (e->num == LINE_EVT_RING && state == IC_IDLE) {
            n_calls++;
            memset(&call, 0, sizeof call);
            call.at = time(NULL);
            evlog("Visitor is calling the apartment");
            set_state(IC_RINGING);
            broadcast_state("call");
        } else if (e->num == LINE_EVT_RELEASED && state != IC_IDLE) {
            bool missed = state == IC_RINGING;
            if (missed) evlog("Missed call");
            else evlog("Call ended by the panel");
            line_set_offhook(false);
            go_idle(missed ? "missed" : "panel_ended");
        }
        break;
    case EV_STEP:
        on_step((step_t)e->num);
        break;
    }
}

static void task(void *arg)
{
    ev_t e;
    for (;;)
        if (xQueueReceive(q, &e, portMAX_DELAY)) handle(&e);
}

static void post(ev_kind_t kind, int fd, int num, const char *name, const char *cmd, const char *arg)
{
    ev_t e = {.kind = kind, .fd = fd, .num = num};
    if (name) strlcpy(e.name, name, sizeof e.name);
    if (cmd) strlcpy(e.cmd, cmd, sizeof e.cmd);
    if (arg) strlcpy(e.arg, arg, sizeof e.arg);
    if (xQueueSend(q, &e, pdMS_TO_TICKS(100)) != pdTRUE) ESP_LOGE(TAG, "event queue full");
}

// ---------- callbacks ----------

static void line_event(line_evt_t evt) { post(EV_LINE, 0, evt, NULL, NULL, NULL); }

static void line_audio(const int16_t *pcm, size_t samples)
{
    int fd = owner_fd;
    if (state == IC_TALKING && fd > 0) ws_send_audio(fd, pcm, samples * sizeof(int16_t));
}

static void step_fire(void *arg) { post(EV_STEP, 0, step_pending, NULL, NULL, NULL); }

void intercom_hello(int fd) { post(EV_HELLO, fd, 0, NULL, NULL, NULL); }
void intercom_client_gone(int fd) { post(EV_GONE, fd, 0, NULL, NULL, NULL); }

void intercom_command(int fd, const char *name, const char *cmd, const char *arg)
{
    post(EV_CMD, fd, 0, name, cmd, arg);
}

void intercom_uplink(int fd, const int16_t *pcm, size_t samples)
{
    if (state == IC_TALKING && fd == owner_fd) line_audio_to_door(pcm, samples);
}

void intercom_status(cJSON *root)
{
    cJSON_AddStringToObject(root, "fw", esp_app_get_description()->version);
    cJSON_AddBoolToObject(root, "emu", line_is_emulated());
    cJSON_AddStringToObject(root, "state", STATE_NAME[state]);
    if (state == IC_TALKING) cJSON_AddStringToObject(root, "owner", owner_name);
    cJSON_AddNumberToObject(root, "calls", n_calls);
    cJSON_AddNumberToObject(root, "opens", n_opens);
    if (line_is_emulated()) {
        cJSON_AddStringToObject(root, "emu_audio", line_emu_get_audio() == LINE_EMU_AUDIO_TONE ? "tone" : "echo");
        cJSON_AddBoolToObject(root, "door_open", line_emu_door_open());
    }
    evlog_to_json(cJSON_AddArrayToObject(root, "log"));
}

esp_err_t intercom_init(void)
{
    q = xQueueCreate(16, sizeof(ev_t));
    const esp_timer_create_args_t ta = {.callback = step_fire, .name = "ic_step"};
    ESP_ERROR_CHECK(esp_timer_create(&ta, &step_timer));
    since_us = esp_timer_get_time();
    xTaskCreate(task, "intercom", 4096, NULL, 7, NULL);
    const line_callbacks_t cb = {.on_event = line_event, .on_audio = line_audio};
    return line_init(&cb);
}
