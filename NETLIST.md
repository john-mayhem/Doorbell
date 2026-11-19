# УКП-СМД-11 (PCB-7047-V1.0-20100417) — netlist

Numbers = pads in board_numbered.jpg (copper side, photo 20261119_223928.jpg).
Status: traced from photos 2026-11-19. Items marked VERIFY wait for meter beeps.

## Parts
R1 100k, R2 2k, R3 2k, R4 560, R6/R7/R8/R10 220 (R6 is fitted upside down: "221" reads as "122"), R11 10k, R12 10k, R5/R9 not fitted.
R13/R14/R15/R26 = 0 ohm on 4-way option pads (unused side pads under the body).
C1 ceramic (?), E1 10uF 50V, D1 LL4148-type (0.55 V), Q1/Q2 J3Y = S8050 NPN (SOT-23: single pin = C).
SW1 DPDT hook: A col 1/3/5, B col 2/4/6. Lever up (handset lifted) = 1-5, 2-6. Pressed (on hook) = 3-5, 4-6.
SW2 SPDT slide: 19 NC, 20 COM, 21 NO, 18/22 tabs. "ON" = COM-NC, "OFF" = COM-NO.
SW3 tactile: 23+25 strapped, 24+26 strapped; press = 23/25 to 24/26.
CON2 (handset): 9 Green SP-, 10 Yellow SP+, 11 Black MIC-, 12 Red MIC+ (each has a test hole).
CON1 (line): 13 Red, 14 Black. Box pads 7 "L+", 8 "L-" unpopulated.

## Nets (photo-traced)
- LINE_RED (13): D1 anode. 8 (box "L-") joins it (VERIFY).
- LINE_BLK (14): Q2 emitter, SW3 24/26, 7 (box "L+"), SW1 4. -> box silk is reversed vs CON1 front silk.
- RAIL: D1 cathode, Q1 C, R1, SW1 3, LED pad 27, hole 34.
- Q1 E: R4, SW1 1.
- Q1 B: R1, R13 -> C1 -> R2 (R2 other end VERIFY).
- E1-: 16 = 17 = R4 other end = R12 = Q2 C.
- E1+: 15 = 12 (MIC+ Red).
- R12 other end = 11 (MIC- Black).
- SW1 5 -> R15 -> 10 (SP+ Yellow).  SW1 2 -> R26 -> 9 (SP- Green).
- Q2 B: R11, SW3 23/25.
- R11 other end = R3 = R14 (32) -> 33 = SW2 COM (20).
- R3 other end = LED pad 28.
- R6/R7/R8/R10 in parallel (69 ohm): left side 29 bus = SW2 NO (21) + hole 35; right side 30 -> SW2 NC (19)? (VERIFY)
- R9 right (31) -> NC.  SW1 6 -> long trace down right edge (destination VERIFY).

## Meter results 2026-11-19 (board out of case, hook lever released = 1-5, 2-6 closed)
- A: 6 -> SW2 "COM-NO" position: beeps 19, 20, 21.  "COM-NC" position: beeps 19, 20 only.
- B: 9 (Green) same as A (expected: 9 -> R26 -> 2 -> 6 through SW1 with the lever released).
- C: 10 (Yellow) -> 18..22: nothing in either position.
- D: 30 (bank right) -> 19, 18: nothing.
- E: 7 = 14 and 8 = 13 CONFIRMED (box silk L+/L- is reversed vs CON1 front silk).
- F: R2 far end = 11 (MIC- Black).
- OPEN: A does not fit a plain SPDT. Needs resistance readings (ohm mode), not beeps. The 69 ohm bank may sit near the beep threshold.
- OPEN: LED1 polarity (27/28).

## Ohm readings 2026-11-19 (lever released)
| | SW2 19/20 | SW2 20/21 |
|---|---|---|
| 6-19 | 0 | 0 |
| 6-20 | 0 | 0 |
| 6-21 | inf | 0 |
| 30-20 | inf | 55 |
| 29-21 | 0 | 0 |
=> 6 = 19 = 20 permanently on copper. SW2 only switches NO (21) onto that node.
=> Bank = 4 x 220 in parallel = 55 ohm (matches the reading), between 21 (29 side) and an unknown node X (30 side).
=> LED: meter shows nothing either way (in-circuit, or the meter's diode test voltage is too low). From the copper: 27 = rail = anode side, 28 -> R3.
OPEN: X = where 30 goes.
- 30 = 8 = 13 (LINE_RED). => SW2 "20/21" position puts the 55 ohm bank across LINE_RED and node 6. On hook (4-6 closed) node 6 = LINE_BLK, so that means 55 ohm straight across the line. That is the "mute": the line looks loaded/busy.
- Handset (photo 20261119_231804.jpg): earpiece speaker = Green/White(Yellow) pair, mic capsule (looks like an electret) = Red/Black pair. Owner thinks the ring comes out of the earpiece.
OPEN: by the trace, the speaker's SP- (9 -> R26 -> 2) is open on hook. That contradicts ringing through the earpiece. Also MIC+ (12) only sees E1+ (no DC bias found). Next check: lever pressed, 9->6 and 10->27; then 12->9 and 12->10.
- Lever pressed: 9-6 open, 10-27 = 0 (SP+ on the rail when on hook). Released: 10-27 open.
- 12 = 9 always (MIC+ and SP- share one node, so the mic is biased through the speaker coil).
- 1-13 open; 1-16 = 560 (R4) -> pad 1 = Q1 E net CONFIRMED.
- 12-10 (released) = 55 ohm with SW2 at 20/21, open at 19/20 -> the speaker's + side reaches the bank's far side (LINE_RED) by some path NOT yet found. Next: 10-13, 10-6, 5-13, 10-31.
