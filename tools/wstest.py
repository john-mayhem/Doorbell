"""End-to-end protocol test against the emulator, acting as one phone."""
import json, math, struct, sys, time, urllib.request
import websocket  # websocket-client

host = sys.argv[1]
print("status:", urllib.request.urlopen(f"http://{host}/status", timeout=5).read().decode().replace("\n", " "))

ws = websocket.create_connection(f"ws://{host}/ws", timeout=5)

def recv_until(pred, secs=5):
    end = time.time() + secs
    while time.time() < end:
        ws.settimeout(max(0.05, end - time.time()))
        try:
            op, data = ws.recv_data()
        except websocket.WebSocketTimeoutException:
            break
        if op == websocket.ABNF.OPCODE_TEXT:
            m = json.loads(data)
            print("  <-", m)
            if pred(m):
                return m
        elif op == websocket.ABNF.OPCODE_BINARY:
            if pred(data):
                return data
    raise SystemExit("TIMEOUT waiting")

def send(**m):
    print("  ->", m); ws.send(json.dumps(m))

def is_state(s, **kw):
    return lambda m: isinstance(m, dict) and m.get("t") == "state" and m.get("state") == s and all(m.get(k) == v for k, v in kw.items())

print("1 hello"); send(t="hello", name="PC-test")
recv_until(lambda m: isinstance(m, dict) and m.get("t") == "hello")
recv_until(is_state("idle"))

print("2 open while idle -> error"); send(t="open")
recv_until(lambda m: isinstance(m, dict) and m.get("t") == "error")

print("3 test_ring -> ringing"); send(t="test_ring")
recv_until(is_state("ringing"))

print("4 answer -> talking, mine"); send(t="answer")
recv_until(is_state("talking", mine=True))

print("5 echo: send 25 frames of 1 kHz, expect them back")
frame = struct.pack("<320h", *[int(8000 * math.sin(2 * math.pi * 1000 * i / 16000)) for i in range(320)])
t0 = time.time()
for _ in range(25):
    ws.send_binary(frame); time.sleep(0.02)
got, first = 0, None
end = time.time() + 2
while time.time() < end and got < 25:
    ws.settimeout(0.5)
    try:
        op, data = ws.recv_data()
    except websocket.WebSocketTimeoutException:
        break
    if op == websocket.ABNF.OPCODE_BINARY:
        got += 1; first = first or time.time()
        assert data == frame, "echo differs"
print(f"  echoed {got}/25 frames, identical, first after {(first - t0) * 1000:.0f} ms" if got else "  NO ECHO")

print("6 tone mode -> ~50 frames/s of beep"); send(t="emu_audio", mode="tone")
recv_until(lambda m: isinstance(m, dict) and m.get("t") == "hello" and m.get("emu_audio") == "tone")
n, t0 = 0, time.time()
while time.time() - t0 < 1.0:
    ws.settimeout(0.5)
    try:
        op, data = ws.recv_data()
    except websocket.WebSocketTimeoutException:
        break
    if op == websocket.ABNF.OPCODE_BINARY: n += 1
print(f"  {n} tone frames in 1 s")
send(t="emu_audio", mode="echo")

print("7 open while talking -> door_opened"); send(t="open")
recv_until(lambda m: isinstance(m, dict) and m.get("ev") == "door_opened")

print("8 hangup -> idle"); send(t="hangup")
recv_until(is_state("idle", reason="hangup"))

print("9 ring, then open from ringing -> door opened, idle"); send(t="test_ring")
recv_until(is_state("ringing")); send(t="open")
recv_until(is_state("talking", open_only=True))
recv_until(lambda m: isinstance(m, dict) and m.get("ev") == "door_opened")
recv_until(is_state("idle", reason="door_opened"), secs=6)

print("10 ring, reject -> idle"); send(t="test_ring")
recv_until(is_state("ringing")); send(t="reject")
recv_until(is_state("idle", reason="rejected"))

ws.close()
print("ALL PASSED")
