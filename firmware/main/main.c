// Smart doorbell bridge: ESP32 <-> apartment handset line, phones over Wi-Fi.
#include <string.h>
#include "freertos/FreeRTOS.h"
#include "esp_app_desc.h"
#include "esp_event.h"
#include "esp_log.h"
#include "esp_netif.h"
#include "esp_wifi.h"
#include "mdns.h"
#include "nvs_flash.h"
#include "evlog.h"
#include "fcm.h"
#include "intercom.h"
#include "line.h"
#include "secrets.h"
#include "ws_server.h"

static const char *TAG = "main";

static void wifi_event(void *arg, esp_event_base_t base, int32_t id, void *data)
{
    if (base == WIFI_EVENT && id == WIFI_EVENT_STA_START) {
        esp_wifi_connect();
    } else if (base == WIFI_EVENT && id == WIFI_EVENT_STA_DISCONNECTED) {
        wifi_event_sta_disconnected_t *d = data;
        ESP_LOGW(TAG, "Wi-Fi disconnected (reason %d), retrying", d->reason);
        esp_wifi_connect();
    } else if (base == IP_EVENT && id == IP_EVENT_STA_GOT_IP) {
        ip_event_got_ip_t *e = data;
        ESP_LOGI(TAG, "online: http://%s.local/  (http://" IPSTR "/)", CONFIG_DOORBELL_HOSTNAME,
                 IP2STR(&e->ip_info.ip));
        evlog("Online at " IPSTR, IP2STR(&e->ip_info.ip));
    }
}

static void wifi_start(void)
{
    esp_netif_t *sta = esp_netif_create_default_wifi_sta();
    esp_netif_set_hostname(sta, CONFIG_DOORBELL_HOSTNAME);
    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&cfg));
    ESP_ERROR_CHECK(esp_event_handler_register(WIFI_EVENT, ESP_EVENT_ANY_ID, wifi_event, NULL));
    ESP_ERROR_CHECK(esp_event_handler_register(IP_EVENT, IP_EVENT_STA_GOT_IP, wifi_event, NULL));
    wifi_config_t wc = {0};
    strlcpy((char *)wc.sta.ssid, WIFI_SSID, sizeof wc.sta.ssid);
    strlcpy((char *)wc.sta.password, WIFI_PASS, sizeof wc.sta.password);
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_STA, &wc));
    ESP_ERROR_CHECK(esp_wifi_start());
    esp_wifi_set_ps(WIFI_PS_NONE);  // mains powered; power save adds 100+ ms to audio
}

static void mdns_start(void)
{
    ESP_ERROR_CHECK(mdns_init());
    mdns_hostname_set(CONFIG_DOORBELL_HOSTNAME);
    mdns_instance_name_set("Doorbell");
    mdns_txt_item_t txt[] = {
        {"proto", "1"},
        {"fw", esp_app_get_description()->version},
        {"emu", line_is_emulated() ? "1" : "0"},
    };
    mdns_service_add(NULL, "_doorbell", "_tcp", 80, txt, 3);
}

void app_main(void)
{
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(err);
    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    evlog_init();

    ESP_LOGI(TAG, "doorbell %s%s", esp_app_get_description()->version, line_is_emulated() ? " (EMULATOR)" : "");
    fcm_init();
    ESP_ERROR_CHECK(intercom_init());
    wifi_start();
    mdns_start();
    ESP_ERROR_CHECK(ws_server_start());
}
