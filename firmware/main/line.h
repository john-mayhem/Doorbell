// The "line" = everything that touches the doorbell handset.
// line_emu.c emulates it on a bare ESP32; the real driver for the УКП-СМД-11 replaces that file.
#pragma once
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "esp_err.h"

#define LINE_AUDIO_RATE    16000  // Hz, mono, s16le
#define LINE_FRAME_SAMPLES 320    // 20 ms

typedef enum {
    LINE_EVT_RING,      // the panel started calling this apartment
    LINE_EVT_RELEASED,  // the panel ended the call (caller gave up, timeout, conversation over)
} line_evt_t;

typedef struct {
    void (*on_event)(line_evt_t evt);
    void (*on_audio)(const int16_t *pcm, size_t samples);  // audio from the door, while off hook
} line_callbacks_t;

esp_err_t line_init(const line_callbacks_t *cb);

// Lift / hang up the handset (SW1 on the real board).
void line_set_offhook(bool offhook);

// Hold the open button (SW3) for CONFIG_DOORBELL_DOOR_PULSE_MS. Like the real handset,
// it only works while off hook.
void line_door_pulse(void);

// Audio to the door speaker. Only used while off hook.
void line_audio_to_door(const int16_t *pcm, size_t samples);

// ---- emulator only ----
typedef enum { LINE_EMU_AUDIO_ECHO, LINE_EMU_AUDIO_TONE } line_emu_audio_t;

bool line_is_emulated(void);
void line_emu_ring(void);                       // the visitor calls the apartment
void line_emu_release(void);                    // the visitor hangs up
void line_emu_set_audio(line_emu_audio_t mode); // what the "door" sends back
line_emu_audio_t line_emu_get_audio(void);
bool line_emu_door_open(void);                  // door pulse in progress
