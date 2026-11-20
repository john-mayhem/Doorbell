"""How long does the home router keep an idle TCP connection open?

Opens one connection per test time to an IMAP server (they must tolerate >= 30 min idle), leaves
each idle for its time, then sends NOOP and waits for "OK". It tells apart:
  server closed  - the server said goodbye (FIN arrived, so the router still knew the connection)
  DEAD           - no answer at all: the router silently forgot the connection
Phones keep their Google push connection idle for 15-20 min between checks, so a router timeout
shorter than that delays every push until the phone's next check.

    python tools/nat_timeout.py
"""
import select
import socket
import ssl
import time

HOST, PORT = "imap.gmail.com", 993
IDLE = [120, 240, 360, 480, 600, 900, 1200]  # seconds

ctx = ssl.create_default_context()


def open_conn():
    s = ctx.wrap_socket(socket.create_connection((HOST, PORT), timeout=10), server_hostname=HOST)
    s.settimeout(10)
    if not s.recv(256).startswith(b"* OK"):
        raise SystemExit("no IMAP greeting")
    return s


conns = {t: open_conn() for t in IDLE}
closed = {}
start = time.time()
print(f"{len(conns)} connections open. Testing after idle times {IDLE} s", flush=True)

for n, t in enumerate(IDLE):
    while time.time() < start + t:  # meanwhile, notice any server goodbye
        for tt, s in conns.items():
            if tt not in closed and select.select([s], [], [], 0)[0]:
                try:
                    if s.recv(256) == b"":
                        closed[tt] = round(time.time() - start)
                except OSError:
                    closed[tt] = round(time.time() - start)
        time.sleep(1)
    if t in closed:
        print(f"idle {t:5d} s: server closed at {closed[t]} s", flush=True)
        conns.pop(t).close()
        continue
    s = conns[t]
    try:
        s.sendall(f"a{n} NOOP\r\n".encode())
        s.settimeout(10)
        ok = f"a{n} OK".encode() in s.recv(256)
        print(f"idle {t:5d} s: {'alive' if ok else 'odd answer'}", flush=True)
    except OSError as e:
        print(f"idle {t:5d} s: DEAD ({type(e).__name__})", flush=True)
    s.close()
    del conns[t]
