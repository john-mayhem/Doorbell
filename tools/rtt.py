import json, statistics, sys, time, websocket
ws = websocket.create_connection(f"ws://{sys.argv[1]}/ws", timeout=5)
def wait(pred):
    while True:
        op, d = ws.recv_data()
        if op == 1:
            m = json.loads(d)
            if pred(m): return m
ws.send(json.dumps({"t": "hello", "name": "PC-rtt"})); wait(lambda m: m["t"] == "state")
ws.send(json.dumps({"t": "test_ring"})); wait(lambda m: m.get("state") == "ringing")
ws.send(json.dumps({"t": "answer"})); wait(lambda m: m.get("state") == "talking")
frame = bytes(640); rtts = []
for i in range(40):
    t = time.perf_counter(); ws.send_binary(frame)
    while ws.recv_data()[0] != 2: pass
    rtts.append((time.perf_counter() - t) * 1000); time.sleep(0.02)
ws.send(json.dumps({"t": "hangup"})); ws.close()
print(f"RTT ms: median {statistics.median(rtts):.1f}  min {min(rtts):.1f}  max {max(rtts):.1f}  first {rtts[0]:.1f}")
