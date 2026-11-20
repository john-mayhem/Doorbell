"""How long does the home router keep an idle TCP connection open?

Opens one connection per test time to a public echo server, leaves each idle for its time,
then sends a few bytes and waits for the echo. No echo = the router forgot the connection.
Phones keep their Google push connection idle for 15-20 min between checks, so a router
timeout shorter than that delays every push until the phone's next check.

    python tools/nat_timeout.py
"""
import socket
import time

HOST, PORT = "tcpbin.com", 4242
IDLE = [60, 120, 180, 240, 300, 420, 600, 900, 1200]  # seconds


def echo(s: socket.socket, msg: bytes) -> bool:
    try:
        s.sendall(msg)
        s.settimeout(8)
        return s.recv(64).startswith(msg.strip())
    except OSError:
        return False


conns = {}
for t in IDLE:
    s = socket.create_connection((HOST, PORT), timeout=10)
    if not echo(s, b"hello\n"):
        raise SystemExit("echo server didn't answer")
    conns[t] = s
start = time.time()
print(f"{len(conns)} connections open, all echo. Testing after idle times {IDLE} s", flush=True)

for t in IDLE:
    time.sleep(max(0, start + t - time.time()))
    ok = echo(conns[t], b"ping\n")
    print(f"idle {t:5d} s: {'alive' if ok else 'DEAD'}", flush=True)
    conns[t].close()
