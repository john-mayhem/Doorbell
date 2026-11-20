# ESP32 ↔ phone protocol (v1)

Everything runs on the home Wi-Fi. The ESP32 announces itself over mDNS as `_doorbell._tcp`
(host `doorbell.local`, port 80). `GET /status` returns JSON for a quick check from a browser.

One WebSocket per phone at `ws://<esp>/ws`:
- **Text frames** carry JSON control messages. Every message has a `t` field.
- **Binary frames** carry audio: PCM s16le, 16 kHz mono, 20 ms per frame (640 bytes).

## Phone → ESP32

| `t` | Fields | Meaning |
|---|---|---|
| `hello` | `name`, `app` | Must come first. `name` shows up as the owner of a call. |
| `answer` | | Lift the handset. Only works while it's `ringing`. |
| `hangup` | | Put the handset down. |
| `reject` | | Lift it for 300 ms, which ends the call on the panel. |
| `open` | | While `talking`, press open. While `ringing`, lift, press open, then hang up (no audio). |
| `ping` | | The ESP32 replies `pong`. |
| `test_ring` | | Emulator only: fake a call. |
| `emu_audio` | `mode`: `echo` or `tone` | Emulator only: choose what the "door" sends back. |

Audio frames from the phone are used only if that phone owns the call.

## ESP32 → phone

| `t` | Fields | When |
|---|---|---|
| `hello` | `proto`, `fw`, `device`, `emu`, `emu_audio`, `audio{rate,frame,format}` | In reply to hello, and again when the emulator audio mode changes. |
| `state` | `state` (`idle`, `ringing`, `talking`), `owner`, `mine`, `open_only`, `since_ms`, `reason` | After hello and on every change. |
| `event` | `ev`: `door_opened`, `by` | When the open button was pressed. |
| `error` | `msg` | A command was refused. |

Values of `reason` when the state becomes `idle`:

| `reason` | What happened |
|---|---|
| `missed` | Nobody answered, so the panel gave up. |
| `rejected` | Someone pressed Decline. |
| `hangup` | Someone hung up. |
| `panel_ended` | The panel ended the conversation (its time limit, or the caller left). |
| `door_opened` | An open-from-ringing sequence finished. |
| `phone_lost` | The phone in the call disconnected. |

## Push messages (Firebase, ESP32 → every registered phone)

Phones register with `POST /api/register {"token", "name", "id"}` each time the app opens. `id` is the
Android ID, which stays the same across reinstalls (the token doesn't), so the doorbell keeps one entry per
phone. Two phones with the same name are still two phones. All data fields are strings;
every message also carries `host` (the ESP32's IP) and `ts` (send time, Unix seconds).

| `type` | Priority | When | Extra fields |
|---|---|---|---|
| `ring` | high, 40 s | Someone is calling | none |
| `ring_end` | high, 40 s | A phone picked up: the others stop ringing | none |
| `call` | high if missed, else normal; kept 1 day | The call is over (also stops ringing) | `outcome` (`missed` / `declined` / `answered` / `opened`), `by` (phone name), `secs` (talk time), `opened` (`1` if the door was opened), `at` (when it rang, Unix seconds) |

The app builds its Recent list only from `call`, so every phone shows the same history.

## Firmware layout

| File | What it does |
|---|---|
| `line.h` | The handset interface: ring / released events, off-hook, door pulse, audio in/out. |
| `line_emu.c` | The emulator. The real driver (`line_ukp.c`) will replace only this file. |
| `intercom.c` | The state machine. One queue and one task, so no locking. |
| `ws_server.c` | The HTTP/WebSocket server and the client list. |
