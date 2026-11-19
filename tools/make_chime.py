"""Makes the app's default ringtone: a soft two-note doorbell chime ("ding-dong").

    python tools/make_chime.py   ->  android/app/src/main/res/raw/chime.wav

Each note is a small bell: a few inharmonic partials with their own decays. The file ends in a
short silence so it can loop while the phone rings.
"""
import math
import struct
import wave
from pathlib import Path

RATE = 22050
LENGTH = 3.2  # seconds, including the pause before the next loop
NOTES = [(0.00, 659.25), (0.60, 523.25)]  # E5, then C5
PARTIALS = [(1.0, 1.0, 1.6), (2.0, 0.35, 0.9), (3.0, 0.16, 0.55), (4.16, 0.08, 0.35)]  # ratio, level, decay s

out = [0.0] * int(RATE * LENGTH)
for start, f in NOTES:
    s0 = int(start * RATE)
    for i in range(s0, len(out)):
        t = (i - s0) / RATE
        attack = min(1.0, t / 0.004)
        out[i] += attack * sum(lvl * math.exp(-t / dec) * math.sin(2 * math.pi * f * r * t) for r, lvl, dec in PARTIALS)

fade = int(0.15 * RATE)  # nothing audible may be cut at the loop point
for k in range(fade):
    out[-1 - k] *= k / fade
peak = max(abs(x) for x in out)
gain = 0.7 / peak  # about -3 dBFS

dst = Path(__file__).resolve().parent.parent / "android/app/src/main/res/raw/chime.wav"
dst.parent.mkdir(parents=True, exist_ok=True)
with wave.open(str(dst), "wb") as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(RATE)
    w.writeframes(b"".join(struct.pack("<h", int(x * gain * 32767)) for x in out))
print(dst, f"{dst.stat().st_size // 1024} KB")
