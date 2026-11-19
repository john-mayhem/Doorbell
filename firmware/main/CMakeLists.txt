if(NOT EXISTS "${CMAKE_CURRENT_LIST_DIR}/fcm-key.json")
    message(FATAL_ERROR "main/fcm-key.json is missing: Firebase console > Project settings > Service accounts > Generate new private key")
endif()
if(NOT EXISTS "${CMAKE_CURRENT_LIST_DIR}/secrets.h")
    message(FATAL_ERROR "main/secrets.h is missing: copy main/secrets.h.example to main/secrets.h and fill in your Wi-Fi")
endif()

# line_emu.c = the doorbell emulator. The real handset driver will be line_ukp.c and replaces it here.
idf_component_register(SRCS "main.c" "intercom.c" "ws_server.c" "line_emu.c" "evlog.c" "fcm.c"
                       INCLUDE_DIRS "."
                       EMBED_TXTFILES "web/index.html" "fcm-key.json"
                       PRIV_REQUIRES esp_wifi esp_netif esp_event nvs_flash esp_http_server json
                                     esp_timer esp_app_format esp_http_client mbedtls)
