// HTTP server: WebSocket at /ws (JSON text frames = control, binary frames = audio), GET /status.
#include <string.h>
#include <unistd.h>
#include "lwip/sockets.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "esp_http_server.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "esp_wifi.h"
#include "esp_heap_caps.h"
#include "cJSON.h"
#include "evlog.h"
#include "fcm.h"
#include "intercom.h"
#include "line.h"
#include "ws_server.h"

static const char *TAG = "ws";

#define MAX_CLIENTS 6

typedef struct {
    int fd;  // 0 = free slot
    char name[32];
} client_t;

static client_t clients[MAX_CLIENTS];
static SemaphoreHandle_t cl_mtx;
static httpd_handle_t server;

// ---------- clients ----------

static void client_add(int fd, const char *name)
{
    xSemaphoreTake(cl_mtx, portMAX_DELAY);
    client_t *slot = NULL;
    for (int i = 0; i < MAX_CLIENTS; i++)
        if (clients[i].fd == fd) slot = &clients[i];
    for (int i = 0; i < MAX_CLIENTS && !slot; i++)
        if (clients[i].fd == 0) slot = &clients[i];
    if (slot) {
        slot->fd = fd;
        strlcpy(slot->name, name, sizeof slot->name);
    }
    xSemaphoreGive(cl_mtx);
    if (slot) evlog("Phone connected: %s", name);
    else ESP_LOGW(TAG, "client table full, fd %d ignored", fd);
}

static bool client_name(int fd, char *out, size_t n)
{
    bool found = false;
    xSemaphoreTake(cl_mtx, portMAX_DELAY);
    for (int i = 0; i < MAX_CLIENTS; i++)
        if (clients[i].fd == fd) {
            strlcpy(out, clients[i].name, n);
            found = true;
        }
    xSemaphoreGive(cl_mtx);
    return found;
}

static bool client_remove(int fd)
{
    bool found = false;
    xSemaphoreTake(cl_mtx, portMAX_DELAY);
    for (int i = 0; i < MAX_CLIENTS; i++)
        if (clients[i].fd == fd) {
            clients[i].fd = 0;
            found = true;
        }
    xSemaphoreGive(cl_mtx);
    return found;
}

void ws_for_each_client(void (*fn)(int fd, const char *name, void *ctx), void *ctx)
{
    client_t copy[MAX_CLIENTS];
    xSemaphoreTake(cl_mtx, portMAX_DELAY);
    memcpy(copy, clients, sizeof copy);
    xSemaphoreGive(cl_mtx);
    for (int i = 0; i < MAX_CLIENTS; i++)
        if (copy[i].fd) fn(copy[i].fd, copy[i].name, ctx);
}

// ---------- sending ----------

typedef struct {
    int fd;
    httpd_ws_type_t type;
    size_t len;
    uint8_t data[];
} send_job_t;

static void send_job(void *arg)
{
    send_job_t *j = arg;
    if (httpd_ws_get_fd_info(server, j->fd) == HTTPD_WS_CLIENT_WEBSOCKET) {
        httpd_ws_frame_t f = {.final = true, .type = j->type, .payload = j->data, .len = j->len};
        httpd_ws_send_frame_async(server, j->fd, &f);
    }
    free(j);
}

static void queue_send(int fd, httpd_ws_type_t type, const void *data, size_t len)
{
    if (!server || fd <= 0) return;
    send_job_t *j = malloc(sizeof *j + len);
    if (!j) return;
    j->fd = fd;
    j->type = type;
    j->len = len;
    memcpy(j->data, data, len);
    if (httpd_queue_work(server, send_job, j) != ESP_OK) free(j);
}

void ws_send_text(int fd, const char *text) { queue_send(fd, HTTPD_WS_TYPE_TEXT, text, strlen(text)); }
void ws_send_audio(int fd, const void *data, size_t len) { queue_send(fd, HTTPD_WS_TYPE_BINARY, data, len); }

// ---------- receiving ----------

static void handle_text(int fd, const char *txt)
{
    cJSON *m = cJSON_Parse(txt);
    const char *t = m ? cJSON_GetStringValue(cJSON_GetObjectItem(m, "t")) : NULL;
    if (!t) {
        ws_send_text(fd, "{\"t\":\"error\",\"msg\":\"bad message\"}");
    } else if (!strcmp(t, "hello")) {
        const char *name = cJSON_GetStringValue(cJSON_GetObjectItem(m, "name"));
        client_add(fd, name && *name ? name : "phone");
        intercom_hello(fd);
    } else if (!strcmp(t, "ping")) {
        ws_send_text(fd, "{\"t\":\"pong\"}");
    } else {
        char name[32];
        if (!client_name(fd, name, sizeof name)) {
            ws_send_text(fd, "{\"t\":\"error\",\"msg\":\"send hello first\"}");
        } else {
            const char *arg = cJSON_GetStringValue(cJSON_GetObjectItem(m, "mode"));
            intercom_command(fd, name, t, arg);
        }
    }
    cJSON_Delete(m);
}

static esp_err_t ws_handler(httpd_req_t *req)
{
    if (req->method == HTTP_GET) {
        ESP_LOGI(TAG, "websocket open, fd %d", httpd_req_to_sockfd(req));
        return ESP_OK;
    }
    static int16_t buf16[1024];  // 2 KB, aligned for PCM; only the server task touches it
    uint8_t *buf = (uint8_t *)buf16;

    httpd_ws_frame_t f = {0};
    esp_err_t err = httpd_ws_recv_frame(req, &f, 0);
    if (err != ESP_OK) return err;
    if (f.len >= sizeof buf16) {
        ESP_LOGW(TAG, "frame too big (%u bytes), closing", (unsigned)f.len);
        return ESP_FAIL;
    }
    if (f.len) {
        f.payload = buf;
        err = httpd_ws_recv_frame(req, &f, f.len);
        if (err != ESP_OK) return err;
    }
    int fd = httpd_req_to_sockfd(req);
    if (f.type == HTTPD_WS_TYPE_BINARY) {
        intercom_uplink(fd, buf16, f.len / 2);
    } else if (f.type == HTTPD_WS_TYPE_TEXT) {
        buf[f.len] = 0;
        handle_text(fd, (const char *)buf);
    }
    return ESP_OK;
}

// Audio frames are small: send them now, don't let Nagle hold them for an ACK.
static esp_err_t on_open(httpd_handle_t hd, int fd)
{
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one);
    return ESP_OK;
}

static void on_close(httpd_handle_t hd, int fd)
{
    char name[32];
    if (client_name(fd, name, sizeof name) && client_remove(fd)) {
        evlog("Phone disconnected: %s", name);
        intercom_client_gone(fd);
    }
    close(fd);
}

typedef struct {
    cJSON *arr;
} status_ctx_t;

static void status_client(int fd, const char *name, void *ctx)
{
    cJSON_AddItemToArray(((status_ctx_t *)ctx)->arr, cJSON_CreateString(name));
}

static esp_err_t status_handler(httpd_req_t *req)
{
    cJSON *root = cJSON_CreateObject();
    intercom_status(root);
    fcm_status(root);
    status_ctx_t ctx = {.arr = cJSON_AddArrayToObject(root, "clients")};
    ws_for_each_client(status_client, &ctx);
    cJSON_AddNumberToObject(root, "uptime_s", (double)(esp_timer_get_time() / 1000000));
    cJSON_AddNumberToObject(root, "free_heap", heap_caps_get_free_size(MALLOC_CAP_DEFAULT));
    wifi_ap_record_t ap;
    if (esp_wifi_sta_get_ap_info(&ap) == ESP_OK) cJSON_AddNumberToObject(root, "rssi", ap.rssi);
    char *s = cJSON_Print(root);
    cJSON_Delete(root);
    httpd_resp_set_type(req, "application/json");
    httpd_resp_sendstr(req, s);
    cJSON_free(s);
    return ESP_OK;
}

// ---------- test page ----------

extern const char index_html_start[] asm("_binary_index_html_start");
extern const char index_html_end[] asm("_binary_index_html_end");

static esp_err_t page_handler(httpd_req_t *req)
{
    httpd_resp_set_type(req, "text/html; charset=utf-8");
    // EMBED_TXTFILES adds a trailing NUL; don't send it
    return httpd_resp_send(req, index_html_start, index_html_end - index_html_start - 1);
}

static esp_err_t ok(httpd_req_t *req)
{
    httpd_resp_set_type(req, "application/json");
    return httpd_resp_sendstr(req, "{\"ok\":true}");
}

static esp_err_t ring_handler(httpd_req_t *req)
{
    line_emu_ring();
    return ok(req);
}

static esp_err_t release_handler(httpd_req_t *req)
{
    line_emu_release();
    return ok(req);
}

static esp_err_t audio_handler(httpd_req_t *req)
{
    char q[32], mode[8] = "";
    if (httpd_req_get_url_query_str(req, q, sizeof q) == ESP_OK) httpd_query_key_value(q, "mode", mode, sizeof mode);
    intercom_command(-1, "Test page", "emu_audio", mode);
    return ok(req);
}

// POST /api/register {"token": "<FCM token>", "name": "<phone name>", "id": "<phone id>"}
static esp_err_t register_handler(httpd_req_t *req)
{
    char body[512];
    int len = req->content_len;
    if (len <= 0 || len >= (int)sizeof body) return httpd_resp_send_err(req, HTTPD_400_BAD_REQUEST, "bad length");
    int got = 0;
    while (got < len) {
        int r = httpd_req_recv(req, body + got, len - got);
        if (r <= 0) return ESP_FAIL;
        got += r;
    }
    body[got] = 0;
    cJSON *m = cJSON_Parse(body);
    const char *tok = cJSON_GetStringValue(cJSON_GetObjectItem(m, "token"));
    const char *name = cJSON_GetStringValue(cJSON_GetObjectItem(m, "name"));
    const char *id = cJSON_GetStringValue(cJSON_GetObjectItem(m, "id"));
    esp_err_t err = fcm_register(tok, name, id);
    cJSON_Delete(m);
    if (err != ESP_OK) return httpd_resp_send_err(req, HTTPD_400_BAD_REQUEST, "need token and name");
    return ok(req);
}

esp_err_t ws_server_start(void)
{
    cl_mtx = xSemaphoreCreateMutex();
    httpd_config_t cfg = HTTPD_DEFAULT_CONFIG();
    cfg.open_fn = on_open;
    cfg.close_fn = on_close;
    cfg.lru_purge_enable = true;
    cfg.stack_size = 6144;
    cfg.keep_alive_enable = true;  // notice phones that vanished without closing
    cfg.keep_alive_idle = 10;
    cfg.keep_alive_interval = 5;
    cfg.keep_alive_count = 3;
    esp_err_t err = httpd_start(&server, &cfg);
    if (err != ESP_OK) return err;

    const httpd_uri_t ws = {.uri = "/ws", .method = HTTP_GET, .handler = ws_handler, .is_websocket = true};
    const httpd_uri_t st = {.uri = "/status", .method = HTTP_GET, .handler = status_handler};
    const httpd_uri_t pg = {.uri = "/", .method = HTTP_GET, .handler = page_handler};
    const httpd_uri_t rg = {.uri = "/api/ring", .method = HTTP_POST, .handler = ring_handler};
    const httpd_uri_t rl = {.uri = "/api/release", .method = HTTP_POST, .handler = release_handler};
    const httpd_uri_t au = {.uri = "/api/audio", .method = HTTP_POST, .handler = audio_handler};
    const httpd_uri_t rgs = {.uri = "/api/register", .method = HTTP_POST, .handler = register_handler};
    httpd_register_uri_handler(server, &ws);
    httpd_register_uri_handler(server, &st);
    httpd_register_uri_handler(server, &pg);
    httpd_register_uri_handler(server, &rg);
    httpd_register_uri_handler(server, &rl);
    httpd_register_uri_handler(server, &au);
    httpd_register_uri_handler(server, &rgs);
    ESP_LOGI(TAG, "listening on :%d", cfg.server_port);
    return ESP_OK;
}
