"""УКП-СМД-11 (PCB-7047-V1.0) schematic, drawn from the traced + meter-verified netlist.

Usage: python schematic.py [png]   -> writes schematic.svg (and schematic.png with 'png')
Pad numbers in brackets refer to board_numbered.jpg.
"""
import sys
import schemdraw
import schemdraw.elements as elm

PNG = len(sys.argv) > 1 and sys.argv[1] == "png"
schemdraw.use("matplotlib" if PNG else "svg")

d = schemdraw.Drawing(show=False)
d.config(unit=2, fontsize=10, lw=1.4)


def W(a, b):
    d.add(elm.Line().at(a).to(b))


def dot(p):
    d.add(elm.Dot().at(p))


def flag(p, direction, text):
    """Net flag: a short stub with the net name at its end (same name = same net)."""
    dx, dy = {"up": (0, .6), "down": (0, -.6), "left": (-.6, 0), "right": (.6, 0)}[direction]
    q = (p[0] + dx, p[1] + dy)
    W(p, q)
    loc = {"up": "top", "down": "bottom", "left": "left", "right": "right"}[direction]
    d.add(elm.Label().at(q).label(text, loc=loc, fontsize=9, color="#b4231c"))


LR_Y, RAIL_Y, LB_Y = 14, 12, 0

# ---- line terminals and buses -------------------------------------------------
d.add(elm.Dot(open=True).at((0, LR_Y)).label("LINE +\nred wire [13]\n(box 'L-' [8])", loc="left", fontsize=9))
d.add(elm.Dot(open=True).at((0, LB_Y)).label("LINE −\nblack wire [14]\n(box 'L+' [7])", loc="left", fontsize=9))
W((0, LR_Y), (23, LR_Y))
W((0, LB_Y), (21, LB_Y))

# ---- D1: reverse-polarity diode, LINE+ -> RAIL ---------------------------------
dot((2, LR_Y))
d.add(elm.Diode().at((2, LR_Y)).down().label("D1\nLL4148", loc="bottom", ofst=0.15))
dot((2, RAIL_Y))
W((2, RAIL_Y), (22.2, RAIL_Y))
d.add(elm.Label().at((14.5, RAIL_Y + 0.35)).label("RAIL (protected line +)", fontsize=9))

# ---- LED1 + R3 -> N14 ----------------------------------------------------------
dot((3.4, RAIL_Y))
d.add(elm.LED().at((3.4, RAIL_Y)).down().label("LED1\ncall", loc="top", ofst=0.15))
d.add(elm.Resistor().at((3.4, 10)).down().label("R3\n2k", loc="top", ofst=0.15))
W((3.4, 8), (3.4, 5))
dot((3.4, 5))
d.add(elm.Resistor().at((3.4, 5)).down().label("R14\n0Ω", loc="top", ofst=0.15))
flag((3.4, 3), "down", "N6")

# ---- Q2 + R11 + SW3 (open door) --------------------------------------------------
d.add(elm.Resistor().at((3.4, 5)).right().length(2.6).label("R11 10k", ofst=0.1))
dot((6, 5))
q2 = d.add(elm.BjtNpn(circle=True).right().anchor("base").at((6.6, 5)).label("Q2\nS8050", loc="right", ofst=0.1))
W((6, 5), q2.base)
d.add(elm.Button().at((6, 5)).down().label("SW3\nOPEN", loc="top", ofst=0.15))
W((6, 3), (6, LB_Y))
dot((6, LB_Y))
W(q2.emitter, (q2.emitter[0], LB_Y))
dot((q2.emitter[0], LB_Y))
NQ2 = (q2.collector[0], 7)
W(q2.collector, NQ2)

# ---- Q1 (series pass / speech amp) ------------------------------------------------
q1 = d.add(elm.BjtNpn(circle=True).right().anchor("base").at((11.6, 10.3)).label("Q1\nS8050", loc="right", ofst=0.1))
W(q1.collector, (q1.collector[0], RAIL_Y))
dot((q1.collector[0], RAIL_Y))
dot((10.6, RAIL_Y))
d.add(elm.Resistor().at((10.6, RAIL_Y)).down().length(1.7).label("R1\n100k", loc="top", ofst=0.1))
dot((10.6, 10.3))
W((10.6, 10.3), q1.base)
Q1E = (q1.emitter[0], 9)
W(q1.emitter, Q1E)
dot(Q1E)
flag(Q1E, "right", "Q1E")
d.add(elm.Resistor().at(Q1E).down().length(2).label("R4 560", loc="bottom", ofst=0.1))

# ---- mic feedback chain: Q1B -R13- C1 - R2 - MIC− -------------------------------------
d.add(elm.Resistor().at((10.6, 10.3)).left().length(1.6).label("R13 0Ω", loc="bottom", ofst=0.1))
d.add(elm.Capacitor().at((9.0, 10.3)).left().length(1.6).label("C1", loc="bottom", ofst=0.1))
d.add(elm.Resistor().at((7.4, 10.3)).left().length(1.6).label("R2 2k", loc="bottom", ofst=0.1))
MICN = (5.8, 10.3)
dot(MICN)
flag(MICN, "up", "MIC−")
d.add(elm.Resistor().at(MICN).down().length(2).label("R12\n10k", loc="bottom", ofst=0.1))
W((5.8, 8.3), (5.8, 7))

# ---- NQ2 bus (Q2 collector, R4, R12, E1−) -----------------------------------------
W((5.8, 7), (14, 7))
for x in (q2.collector[0], Q1E[0]):
    dot((x, 7))
dot((14, 7))
d.add(elm.Label().at((9.2, 6.6)).label("E1− / Q2 C  [16][17]", fontsize=8))
d.add(elm.Capacitor(polar=True).at((14, 7)).up().length(2).reverse().label("E1\n10µ 50V", loc="bottom", ofst=0.15))

# ---- handset: speaker + mic share the SP−/MIC+ node -----------------------------------
SPN = (17, 9)
W((14, 9), SPN)
dot(SPN)
d.add(elm.Label().at((15.5, 8.6)).label("SP− = MIC+  [9][12]", fontsize=8))
W(SPN, (17, 9.6))
sp = d.add(elm.Speaker().right().anchor("in2").at((17, 9.6)).label("earpiece\nGreen −\nYellow +", loc="right", ofst=0.1))
SPP = sp.in1
W(SPN, (17, 8.2))
mic = d.add(elm.Mic().right().anchor("in1").at((17, 8.2)).label("mic\n(Red +, Black −)", loc="right", ofst=0.1))
flag(mic.in2, "down", "MIC−")

# ---- SW1 pole A: SP+ -R15- common[5]; on hook[3]->RAIL, lifted[1]->Q1E ----------------
W(SPP, (SPP[0], 11))
d.add(elm.Resistor().at((SPP[0], 11)).right().length(3.4).label("R15 0Ω", ofst=0.1))
sw1a = d.add(elm.SwitchSpdt2().right().anchor("a").at((SPP[0] + 3.4, 11)).label("SW1a\nhook", loc="bottom", ofst=0.25))
W(sw1a.b, (sw1a.b[0], RAIL_Y))
dot((sw1a.b[0], RAIL_Y))
d.add(elm.Label().at((sw1a.b[0] + 0.35, 11.7)).label("on hook [3]", fontsize=8, halign="left"))
W(sw1a.c, (sw1a.c[0], 10))
flag((sw1a.c[0], 10), "down", "Q1E")
d.add(elm.Label().at((sw1a.c[0] + 0.35, 10.4)).label("lifted [1]", fontsize=8, halign="left"))

# ---- SW1 pole B: N6 = common[6]; lifted[2] -R26- SP−, on hook[4] -> LINE− ---------------
flag((17.5, 4), "left", "N6")
W((17.5, 4), (18.5, 4))
dot((18.5, 4))
sw1b = d.add(elm.SwitchSpdt2().right().anchor("a").at((18.5, 4)).label("SW1b\nhook", loc="bottom", ofst=0.35))
W(sw1b.b, (sw1b.b[0], 5))
d.add(elm.Resistor().at((sw1b.b[0], 5)).up().length(1.6).label("R26 0Ω", loc="bottom", ofst=0.1))
flag((sw1b.b[0], 6.6), "up", "SP−")
d.add(elm.Label().at((sw1b.b[0] + 0.35, 4.7)).label("lifted [2]", fontsize=8, halign="left"))
W(sw1b.c, (sw1b.c[0], LB_Y))
dot((sw1b.c[0], LB_Y))
d.add(elm.Label().at((sw1b.c[0] + 0.35, 2.6)).label("on hook [4]", fontsize=8, halign="left"))
d.add(elm.Label().at((15.6, 4.6)).label("N6 = SW1[6] = SW2 COM/NC", fontsize=8))

# ---- SW2 mute + 55 Ω bank: N6 -> LINE+ ---------------------------------------------------
flag((23, 2.2), "down", "N6")
d.add(elm.Switch().at((23, 2.2)).up().length(2).label("SW2 call vol.\nclosed = '0' (quiet)\n(NO [21])", loc="bottom", ofst=0.15))
d.add(elm.Resistor().at((23, 4.2)).up().length(2.2).label("R6‖R7‖R8‖R10\n4×220 = 55Ω", loc="bottom", ofst=0.15))
W((23, 6.4), (23, LR_Y))
dot((23, LR_Y))

if PNG:
    d.save("schematic.png", dpi=110)
d.save("schematic.svg")
print("ok")
