// FCM HTTP v1 sender.
//  1. A service-account key (fcm-key.json, gitignored, embedded at build) signs a JWT (RS256).
//  2. The JWT is traded at oauth2.googleapis.com for an access token, cached ~55 min and
//     refreshed in the background so a ring never waits for it.
//  3. Each push is a high-priority DATA message, so the app's code runs even when it is closed
//     and draws the full-screen call itself.
#include <string.h>
#include <sys/time.h>
#include <time.h>
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "esp_crt_bundle.h"
#include "esp_http_client.h"
#include "esp_log.h"
#include "esp_netif.h"
#include "esp_netif_sntp.h"
#include "esp_random.h"
#include "esp_timer.h"
#include "mbedtls/base64.h"
#include "mbedtls/pk.h"
#include "mbedtls/sha256.h"
#include "nvs.h"
#include "evlog.h"
#include "fcm.h"

static const char *TAG = "fcm";

#define MAX_DEV 6
#define TOKEN_URL "https://oauth2.googleapis.com/token"

extern const char key_json_start[] asm("_binary_fcm_key_json_start");

typedef struct {
    char token[200];
    char name[32];
    char id[24];  // Android ID: survives reinstalls, unlike the token
} dev_t_;

static dev_t_ devs[MAX_DEV];
static int n_devs;
static SemaphoreHandle_t mtx;
static QueueHandle_t q;

static char project_id[64], client_email[128];
static char *private_key;  // PEM
static char access_token[1200];
static time_t token_expires;
static char last_result[64] = "nothing sent yet";

// ---------- storage ----------

static void save(void)
{
    nvs_handle_t h;
    if (nvs_open("fcm", NVS_READWRITE, &h) != ESP_OK) return;
    nvs_set_blob(h, "phones", devs, sizeof(dev_t_) * n_devs);
    nvs_set_i32(h, "n_phones", n_devs);
    nvs_commit(h);
    nvs_close(h);
}

static void load(void)
{
    nvs_handle_t h;
    if (nvs_open("fcm", NVS_READWRITE, &h) != ESP_OK) return;
    if (nvs_erase_key(h, "devs") == ESP_OK) {  // fw 0.2.0 list (no phone ids): phones re-register on app start
        nvs_erase_key(h, "n");
        nvs_commit(h);
    }
    int32_t n = 0;
    size_t sz = sizeof devs;
    if (nvs_get_i32(h, "n_phones", &n) == ESP_OK && n > 0 && n <= MAX_DEV && nvs_get_blob(h, "phones", devs, &sz) == ESP_OK &&
        sz == sizeof(dev_t_) * n)
        n_devs = n;
    nvs_close(h);
}

esp_err_t fcm_register(const char *token, const char *name, const char *id)
{
    if (!token || strlen(token) >= sizeof devs[0].token || !name) return ESP_ERR_INVALID_ARG;
    if (!id) id = "";
    xSemaphoreTake(mtx, portMAX_DELAY);
    int slot = -1;
    for (int i = 0; i < n_devs; i++)  // same phone (id) or same install (token); names may repeat
        if (!strcmp(devs[i].token, token) || (*id && !strcmp(devs[i].id, id))) slot = i;
    bool fresh = slot < 0;
    if (slot < 0) slot = n_devs < MAX_DEV ? n_devs++ : MAX_DEV - 1;
    bool changed = fresh || strcmp(devs[slot].token, token) || strcmp(devs[slot].name, name) || strcmp(devs[slot].id, id);
    strlcpy(devs[slot].token, token, sizeof devs[slot].token);
    strlcpy(devs[slot].name, name, sizeof devs[slot].name);
    strlcpy(devs[slot].id, id, sizeof devs[slot].id);
    if (changed) save();
    xSemaphoreGive(mtx);
    if (changed) evlog("%s will get push notifications", name);
    return ESP_OK;
}

static void forget(const char *token)
{
    xSemaphoreTake(mtx, portMAX_DELAY);
    for (int i = 0; i < n_devs; i++)
        if (!strcmp(devs[i].token, token)) {
            evlog("%s's push token expired, removed", devs[i].name);
            devs[i] = devs[--n_devs];
            save();
            break;
        }
    xSemaphoreGive(mtx);
}

// ---------- HTTP ----------

static int http_post(const char *url, const char *ctype, const char *auth, const char *body, char *resp, int resp_len)
{
    esp_http_client_config_t c = {
        .url = url,
        .method = HTTP_METHOD_POST,
        .crt_bundle_attach = esp_crt_bundle_attach,
        .timeout_ms = 10000,
        .buffer_size_tx = 2048,
    };
    esp_http_client_handle_t h = esp_http_client_init(&c);
    esp_http_client_set_header(h, "Content-Type", ctype);
    if (auth) esp_http_client_set_header(h, "Authorization", auth);
    int len = strlen(body), status = -1;
    if (esp_http_client_open(h, len) == ESP_OK) {
        esp_http_client_write(h, body, len);
        esp_http_client_fetch_headers(h);
        status = esp_http_client_get_status_code(h);
        int n = esp_http_client_read_response(h, resp, resp_len - 1);
        resp[n > 0 ? n : 0] = 0;
        esp_http_client_close(h);
    } else {
        resp[0] = 0;
    }
    esp_http_client_cleanup(h);
    return status;
}

// ---------- OAuth token from a signed JWT ----------

static size_t b64url(char *out, size_t cap, const unsigned char *in, size_t len)
{
    size_t olen = 0;
    mbedtls_base64_encode((unsigned char *)out, cap, &olen, in, len);
    size_t w = 0;
    for (size_t i = 0; i < olen; i++) {
        char ch = out[i];
        if (ch == '=') break;
        out[w++] = ch == '+' ? '-' : ch == '/' ? '_' : ch;
    }
    out[w] = 0;
    return w;
}

static int rng(void *ctx, unsigned char *buf, size_t len)
{
    esp_fill_random(buf, len);
    return 0;
}

static bool refresh_token(void)
{
    time_t now = time(NULL);
    char claims[512];
    snprintf(claims, sizeof claims,
             "{\"iss\":\"%s\",\"scope\":\"https://www.googleapis.com/auth/firebase.messaging\","
             "\"aud\":\"" TOKEN_URL "\",\"iat\":%lld,\"exp\":%lld}",
             client_email, (long long)now, (long long)now + 3600);

    static char jwt[1200];
    size_t p = b64url(jwt, sizeof jwt, (const unsigned char *)"{\"alg\":\"RS256\",\"typ\":\"JWT\"}", 27);
    jwt[p++] = '.';
    p += b64url(jwt + p, sizeof jwt - p, (const unsigned char *)claims, strlen(claims));

    unsigned char hash[32], sig[512];
    size_t sig_len = 0;
    mbedtls_sha256((const unsigned char *)jwt, p, hash, 0);
    mbedtls_pk_context pk;
    mbedtls_pk_init(&pk);
    int rc = mbedtls_pk_parse_key(&pk, (const unsigned char *)private_key, strlen(private_key) + 1, NULL, 0, rng, NULL);
    if (rc == 0) rc = mbedtls_pk_sign(&pk, MBEDTLS_MD_SHA256, hash, sizeof hash, sig, sizeof sig, &sig_len, rng, NULL);
    mbedtls_pk_free(&pk);
    if (rc != 0) {
        ESP_LOGE(TAG, "signing failed (-0x%04x)", -rc);
        return false;
    }
    jwt[p++] = '.';
    b64url(jwt + p, sizeof jwt - p, sig, sig_len);

    static char body[1400], resp[2048];
    snprintf(body, sizeof body, "grant_type=urn%%3Aietf%%3Aparams%%3Aoauth%%3Agrant-type%%3Ajwt-bearer&assertion=%s", jwt);
    int st = http_post(TOKEN_URL, "application/x-www-form-urlencoded", NULL, body, resp, sizeof resp);
    cJSON *r = cJSON_Parse(resp);
    const char *tok = r ? cJSON_GetStringValue(cJSON_GetObjectItem(r, "access_token")) : NULL;
    bool ok = st == 200 && tok && strlen(tok) + 8 < sizeof access_token;
    if (ok) {
        snprintf(access_token, sizeof access_token, "Bearer %s", tok);
        token_expires = now + 3300;  // refresh 5 min early
        ESP_LOGI(TAG, "got an access token");
    } else {
        ESP_LOGE(TAG, "token request failed: HTTP %d %.200s", st, resp);
        snprintf(last_result, sizeof last_result, "Google login failed (HTTP %d)", st);
    }
    cJSON_Delete(r);
    return ok;
}

// ---------- sending ----------

static void my_ip(char *out, size_t n)
{
    esp_netif_ip_info_t ip = {0};
    esp_netif_get_ip_info(esp_netif_get_handle_from_ifkey("WIFI_STA_DEF"), &ip);
    snprintf(out, n, IPSTR, IP2STR(&ip.ip));
}

typedef struct {
    char type[12];
    cJSON *data;
    bool urgent;
} job_t;

static void send_all(const job_t *job)
{
    if (time(NULL) >= token_expires && !refresh_token()) {
        evlog("Push failed: no Google access token");
        return;
    }
    dev_t_ copy[MAX_DEV];
    xSemaphoreTake(mtx, portMAX_DELAY);
    int n = n_devs;
    memcpy(copy, devs, sizeof copy);
    xSemaphoreGive(mtx);
    if (!n) return;

    char url[160], ip[16], ts[16];
    snprintf(url, sizeof url, "https://fcm.googleapis.com/v1/projects/%s/messages:send", project_id);
    my_ip(ip, sizeof ip);
    snprintf(ts, sizeof ts, "%lld", (long long)time(NULL));
    static char resp[1024];
    for (int i = 0; i < n; i++) {
        cJSON *root = cJSON_CreateObject();
        cJSON *m = cJSON_AddObjectToObject(root, "message");
        cJSON_AddStringToObject(m, "token", copy[i].token);
        cJSON *d = job->data ? cJSON_Duplicate(job->data, true) : cJSON_CreateObject();
        cJSON_AddStringToObject(d, "type", job->type);
        cJSON_AddStringToObject(d, "host", ip);
        cJSON_AddStringToObject(d, "ts", ts);
        cJSON_AddItemToObject(m, "data", d);
        cJSON *a = cJSON_AddObjectToObject(m, "android");
        cJSON_AddStringToObject(a, "priority", job->urgent ? "high" : "normal");
        cJSON_AddStringToObject(a, "ttl", job->urgent ? "40s" : "86400s");
        char *body = cJSON_PrintUnformatted(root);
        cJSON_Delete(root);
        int64_t t0 = esp_timer_get_time();
        int st = http_post(url, "application/json", access_token, body, resp, sizeof resp);
        int ms = (int)((esp_timer_get_time() - t0) / 1000);
        cJSON_free(body);
        if (st == 200) {
            snprintf(last_result, sizeof last_result, "%s to %s: ok (%d ms)", job->type, copy[i].name, ms);
            if (!strcmp(job->type, "ring")) evlog("Push sent to %s (%d ms)", copy[i].name, ms);
        } else if (st == 404 || strstr(resp, "UNREGISTERED") || strstr(resp, "INVALID_ARGUMENT")) {
            forget(copy[i].token);
        } else {
            if (st == 401) token_expires = 0;
            snprintf(last_result, sizeof last_result, "%s to %s: HTTP %d", job->type, copy[i].name, st);
            evlog("Push to %s failed (HTTP %d)", copy[i].name, st);
            ESP_LOGE(TAG, "%.300s", resp);
        }
    }
}

static void task(void *arg)
{
    // Google rejects JWTs with a wrong clock: wait for a real NTP sync. That also means Wi-Fi is up
    // (the RTC keeps the old time across a reset, so checking the clock alone is not enough).
    while (esp_netif_sntp_sync_wait(pdMS_TO_TICKS(2000)) != ESP_OK) {}
    ESP_LOGI(TAG, "clock set, logging in to Google");
    refresh_token();
    job_t job;
    for (;;) {
        bool have = time(NULL) < token_expires;
        if (xQueueReceive(q, &job, pdMS_TO_TICKS(have ? 60000 : 10000))) {
            send_all(&job);
            cJSON_Delete(job.data);
        } else if (time(NULL) >= token_expires) {
            refresh_token();  // keep a fresh token ready
        }
    }
}

void fcm_notify(const char *type, cJSON *data, bool urgent)
{
    job_t j = {.data = data, .urgent = urgent};
    strlcpy(j.type, type, sizeof j.type);
    if (!q || xQueueSend(q, &j, 0) != pdTRUE) cJSON_Delete(data);
}

void fcm_status(cJSON *root)
{
    cJSON *o = cJSON_AddObjectToObject(root, "push");
    cJSON_AddBoolToObject(o, "ready", time(NULL) < token_expires);
    cJSON_AddStringToObject(o, "last", last_result);
    cJSON *arr = cJSON_AddArrayToObject(o, "phones");
    xSemaphoreTake(mtx, portMAX_DELAY);
    for (int i = 0; i < n_devs; i++) cJSON_AddItemToArray(arr, cJSON_CreateString(devs[i].name));
    xSemaphoreGive(mtx);
}

void fcm_init(void)
{
    mtx = xSemaphoreCreateMutex();
    q = xQueueCreate(6, sizeof(job_t));
    load();

    cJSON *k = cJSON_Parse(key_json_start);
    const char *pid = cJSON_GetStringValue(cJSON_GetObjectItem(k, "project_id"));
    const char *em = cJSON_GetStringValue(cJSON_GetObjectItem(k, "client_email"));
    const char *pk = cJSON_GetStringValue(cJSON_GetObjectItem(k, "private_key"));
    if (!pid || !em || !pk) {
        ESP_LOGE(TAG, "fcm-key.json is not a service-account key; push disabled");
        cJSON_Delete(k);
        return;
    }
    strlcpy(project_id, pid, sizeof project_id);
    strlcpy(client_email, em, sizeof client_email);
    private_key = strdup(pk);
    cJSON_Delete(k);

    esp_sntp_config_t sc = ESP_NETIF_SNTP_DEFAULT_CONFIG("pool.ntp.org");
    esp_netif_sntp_init(&sc);
    xTaskCreate(task, "fcm", 8192, NULL, 4, NULL);
    ESP_LOGI(TAG, "project %s, %d phone(s) registered", project_id, n_devs);
}
