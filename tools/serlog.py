"""Read a serial port for N seconds (reopens if USB re-enumerates). usage: serlog.py COM3 25 [reset]"""
import sys, time, serial

port, secs = sys.argv[1], float(sys.argv[2])
reset = len(sys.argv) > 3
end = time.time() + secs
s = None
while time.time() < end:
    try:
        if s is None:
            s = serial.Serial(port, 115200, timeout=0.2)
            if reset:  # USB-Serial-JTAG: toggle RTS to reset into the app
                s.dtr = False; s.rts = True; time.sleep(0.1); s.rts = False
                reset = False
        data = s.read(4096)
        if data:
            sys.stdout.write(data.decode("utf-8", "replace")); sys.stdout.flush()
    except Exception:
        s = None
        time.sleep(0.3)
