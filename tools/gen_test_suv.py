#!/usr/bin/env python3
"""Generates the Test SUV: model.json (visual parts) and vehicle.json (physics).

Body frame: +X = left, +Y = up, +Z = forward, y = 0 is the underbody floor.
Run from the repository root:  python3 tools/gen_test_suv.py
"""
import json, math, os

OUT = "src/main/resources/data/beamphysics/vehicles/test_suv"

W, WR = 1.0, 0.84                       # half width at belt line / roof
SILL, ARCH, BELT, ROOF = 0.12, 0.86, 1.06, 1.70
FRONT, REAR = 2.32, -2.32
AF0, AF1 = 0.83, 2.07                   # front wheel arch (z range)
AR0, AR1 = -2.07, -0.83                 # rear wheel arch
WELL = 0.64                             # inner wall of the wheel wells
COWL, ROOF_F, ROOF_R = 0.95, 0.30, -2.05
RT = ROOF - 0.05                        # underside of the roof panel

def box(frm, to, color, faces="", mirror=False, **kw):
    s = {"type": "box", "from": frm, "to": to, "color": color}
    if faces: s["faces"] = faces
    if mirror: s["mirror"] = True
    s.update(kw)
    return s

def quad(pts, color, mirror=False, **kw):
    s = {"type": "quad", "points": pts, "color": color}
    if mirror: s["mirror"] = True
    s.update(kw)
    return s

def hexa(bottom, top, color, mirror=False, faces=""):
    """bottom/top: (x0, x1, z0, z1, y)"""
    def ring(x0, x1, z0, z1, y):
        return [[x0, y, z0], [x1, y, z0], [x1, y, z1], [x0, y, z1]]
    s = {"type": "hexa", "points": ring(*bottom) + ring(*top), "color": color}
    if mirror: s["mirror"] = True
    if faces: s["faces"] = faces
    return s

def flip(shape):
    """Mirror a shape to the right-hand side (x -> -x), keeping outward winding."""
    s = json.loads(json.dumps(shape))
    if s["type"] == "box":
        s["from"][0], s["to"][0] = -s["to"][0], -s["from"][0]
        if "faces" in s:
            s["faces"] = s["faces"].translate(str.maketrans("LR", "RL"))
    elif s["type"] == "quad":
        s["points"] = [[-p[0], p[1], p[2]] for p in reversed(s["points"])]
    elif s["type"] == "hexa":
        m = [1, 0, 3, 2, 5, 4, 7, 6]
        p = s["points"]
        s["points"] = [[-p[i][0], p[i][1], p[i][2]] for i in m]
        if "faces" in s:
            s["faces"] = s["faces"].translate(str.maketrans("LR", "RL"))
    return s

GLASS = {"alpha": 0.38}

# ---------------------------------------------------------------- body
body = [
    # floor pan and rocker panels
    box([-WELL, 0.0, REAR + 0.05], [WELL, 0.05, FRONT - 0.05], "under"),
    box([WELL, 0.0, AR1], [W - 0.02, 0.05, AF0], "under", mirror=True),
    box([W - 0.07, 0.0, AR1], [W, SILL, AF0], "trim", mirror=True),
    # wheel wells: inner wall, roof and end walls of each arch
    box([WELL, 0.0, AF0], [W, ARCH, AF1], "under", faces="RUBF", mirror=True),
    box([WELL, 0.0, AR0], [W, ARCH, AR1], "under", faces="RUBF", mirror=True),
    # lower B-post between the doors
    box([W - 0.06, SILL, -0.05], [W, BELT, 0.08], "paint", mirror=True),
    # fender flares
    box([W, ARCH - 0.05, AF0 - 0.04], [W + 0.06, ARCH + 0.03, AF1 + 0.04], "trim", mirror=True),
    box([W, ARCH - 0.05, AR0 - 0.04], [W + 0.06, ARCH + 0.03, AR1 + 0.04], "trim", mirror=True),
    # front fenders (above the arches) and front lower corners
    box([W - 0.05, ARCH, AF0], [W, BELT, FRONT - 0.02], "paint", mirror=True),
    box([WELL, 0.2, AF1], [W, ARCH, FRONT], "paint", faces="LFDR", mirror=True),
    # rear quarters
    box([W - 0.05, ARCH, REAR + 0.02], [W, BELT, AR1], "paint", mirror=True),
    box([WELL, 0.2, REAR], [W, ARCH, AR0], "paint", faces="LBDR", mirror=True),
    # front fascia with grille and headlights
    box([-W, 0.2, FRONT - 0.06], [W, BELT - 0.05, FRONT], "paint"),
    box([-0.52, 0.52, FRONT], [0.52, 0.94, FRONT + 0.025], "grille"),
    box([-0.46, 0.60, FRONT + 0.025], [0.46, 0.64, FRONT + 0.04], "chrome"),
    box([-0.46, 0.71, FRONT + 0.025], [0.46, 0.75, FRONT + 0.04], "chrome"),
    box([-0.46, 0.82, FRONT + 0.025], [0.46, 0.86, FRONT + 0.04], "chrome"),
    box([0.58, 0.66, FRONT], [0.95, 0.94, FRONT + 0.035], "lamp", mirror=True),
    box([0.60, 0.50, FRONT], [0.93, 0.60, FRONT + 0.03], "indicator", mirror=True),
    # firewall, engine bay
    box([-W + 0.05, 0.05, COWL - 0.03], [W - 0.05, BELT, COWL + 0.01], "under"),
    box([-0.40, 0.22, 1.12], [0.40, 0.84, 1.95], "engine"),
    box([-0.30, 0.84, 1.25], [0.30, 0.93, 1.80], "engineTop"),
    box([-0.60, 0.28, 2.08], [0.60, 0.96, 2.16], "radiator"),
    box([0.34, 0.55, 1.20], [0.58, 0.80, 1.50], "battery"),
    # rear lower panel and tail lights
    box([-W, 0.2, REAR], [W, 0.56, REAR + 0.06], "paint"),
    box([0.78, 0.62, REAR - 0.02], [0.99, 1.02, REAR + 0.02], "tail", mirror=True),
    # pillars A, B, C, D
    hexa((W - 0.09, W - 0.02, COWL - 0.09, COWL, BELT), (WR - 0.07, WR, ROOF_F - 0.09, ROOF_F, RT), "paint", mirror=True),
    hexa((W - 0.09, W - 0.02, -0.04, 0.07, BELT), (WR - 0.07, WR, -0.04, 0.07, RT), "trim", mirror=True),
    hexa((W - 0.09, W - 0.02, -0.92, -0.80, BELT), (WR - 0.07, WR, -0.92, -0.80, RT), "paint", mirror=True),
    hexa((W - 0.09, W - 0.02, REAR + 0.02, REAR + 0.14, BELT), (WR - 0.07, WR, ROOF_R, ROOF_R + 0.12, RT), "paint", mirror=True),
    # roof and rails
    box([-WR, RT, ROOF_R], [WR, ROOF, ROOF_F], "paint"),
    box([0.68, ROOF, -1.85], [0.74, ROOF + 0.06, 0.05], "trim", mirror=True),
    # fixed glass: windshield and rear side windows
    quad([[-0.90, BELT, COWL], [0.90, BELT, COWL], [0.77, RT, ROOF_F], [-0.77, RT, ROOF_F]], "glass", **GLASS),
    quad([[W - 0.03, BELT, -0.92], [W - 0.03, BELT, REAR + 0.14], [WR - 0.01, RT, ROOF_R + 0.12], [WR - 0.01, RT, -0.92]], "glass", mirror=True, **GLASS),
    # cargo floor trim
    box([-WELL, 0.05, REAR + 0.08], [WELL, 0.22, -1.48], "carpet"),
]

# ---------------------------------------------------------------- hinged / detachable panels
hood = [
    hexa((-0.95, 0.95, COWL, FRONT - 0.01, BELT - 0.04), (-0.95, 0.95, COWL, FRONT - 0.01, BELT + 0.01), "paint"),
    box([-0.26, BELT + 0.01, 1.25], [0.26, BELT + 0.07, 1.85], "trim"),
]
# the hood slopes slightly towards the nose
for s in hood[:1]:
    for i in (2, 3):
        s["points"][i][1] -= 0.06
    for i in (6, 7):
        s["points"][i][1] -= 0.06

front_bumper = [
    box([-W - 0.05, 0.20, FRONT], [W + 0.05, 0.50, FRONT + 0.20], "bumper"),
    box([-0.55, 0.12, FRONT + 0.02], [0.55, 0.20, FRONT + 0.16], "chrome"),
    box([0.66, 0.30, FRONT + 0.20], [0.86, 0.42, FRONT + 0.22], "lamp", mirror=True),
]
rear_bumper = [
    box([-W - 0.05, 0.20, REAR - 0.20], [W + 0.05, 0.50, REAR], "bumper"),
    box([-0.55, 0.12, REAR - 0.16], [0.55, 0.20, REAR - 0.02], "chrome"),
]

def door(z0, z1, glass_top_front, mirror_z=None):
    """Left door between z0 (rear edge) and z1 (front edge)."""
    shapes = [
        box([W - 0.06, SILL, z0], [W, BELT, z1], "paint"),
        box([W - 0.11, SILL + 0.1, z0 + 0.05], [W - 0.06, BELT - 0.08, z1 - 0.05], "doorTrim"),
        box([W, 0.90, z0 + 0.08], [W + 0.025, 0.95, z0 + 0.26], "chrome"),
        quad([[W - 0.03, BELT, z1], [W - 0.03, BELT, z0], [WR - 0.01, RT, z0], [WR - 0.01, RT, glass_top_front]], "glass", **GLASS),
    ]
    if mirror_z is not None:
        shapes.append(box([W, BELT + 0.02, mirror_z], [W + 0.17, BELT + 0.15, mirror_z + 0.07], "trim"))
    return shapes

door_fl = door(0.08, COWL - 0.10, ROOF_F - 0.10, mirror_z=COWL - 0.20)
door_rl = door(-0.83, -0.05, -0.05)

tailgate = [
    box([-0.76, 0.56, REAR], [0.76, BELT, REAR + 0.05], "paint"),
    box([-0.28, 0.64, REAR - 0.012], [0.28, 0.78, REAR], "plate"),
    box([-0.20, 0.90, REAR - 0.02], [0.20, 0.94, REAR], "chrome"),
    quad([[0.90, BELT, REAR + 0.03], [-0.90, BELT, REAR + 0.03], [-0.77, RT, ROOF_R + 0.02], [0.77, RT, ROOF_R + 0.02]], "glass", **GLASS),
]

# ---------------------------------------------------------------- interior
SX = 0.43
def seat(x):
    return [
        box([x - 0.24, 0.20, -0.30], [x + 0.24, 0.44, 0.24], "seat"),
        hexa((x - 0.24, x + 0.24, -0.42, -0.28, 0.40), (x - 0.22, x + 0.22, -0.56, -0.44, 1.14), "seat"),
        box([x - 0.13, 1.14, -0.56], [x + 0.13, 1.36, -0.46], "seatDark"),
    ]
interior = seat(SX) + seat(-SX) + [
    box([-0.72, 0.20, -1.34], [0.72, 0.44, -0.86], "seat"),
    box([-0.72, 0.44, -1.46], [0.72, 1.10, -1.32], "seat"),
    # dashboard, instrument cluster, console
    hexa((-0.93, 0.93, 0.66, 0.92, 0.66), (-0.93, 0.93, 0.74, 0.92, 1.04), "dash"),
    box([SX - 0.20, 1.02, 0.70], [SX + 0.20, 1.13, 0.80], "dashDark"),
    box([SX - 0.15, 1.04, 0.695], [SX - 0.02, 1.11, 0.70], "gauge"),
    box([SX + 0.02, 1.04, 0.695], [SX + 0.15, 1.11, 0.70], "gauge"),
    box([-0.20, 0.80, 0.655], [0.20, 0.98, 0.66], "screen"),
    box([-0.13, 0.05, -0.45], [0.13, 0.48, 0.68], "dashDark"),
    box([-0.03, 0.48, 0.10], [0.03, 0.66, 0.16], "chrome"),
    box([SX - 0.03, 0.86, 0.50], [SX + 0.03, 0.94, 0.70], "dashDark"),
    # pedals
    box([SX - 0.16, 0.10, 0.74], [SX - 0.08, 0.26, 0.77], "chrome"),
    box([SX + 0.04, 0.10, 0.74], [SX + 0.12, 0.26, 0.77], "chrome"),
    box([-0.90, 0.05, -0.80], [0.90, 0.07, 0.90], "carpet"),
]

# steering wheel: a ring of small blocks in the plane facing the driver
SW_C = [SX, 0.99, 0.48]
steering = []
N = 14
R = 0.185
for i in range(N):
    a = 2 * math.pi * i / N
    cx, cy = SW_C[0] + math.cos(a) * R, SW_C[1] + math.sin(a) * R
    steering.append(box([round(cx - 0.035, 4), round(cy - 0.035, 4), SW_C[2] - 0.025],
                        [round(cx + 0.035, 4), round(cy + 0.035, 4), SW_C[2] + 0.025], "wheelRim", tessellate=False))
steering += [
    box([SW_C[0] - 0.06, SW_C[1] - 0.05, SW_C[2] - 0.02], [SW_C[0] + 0.06, SW_C[1] + 0.05, SW_C[2] + 0.03], "dashDark", tessellate=False),
    box([SW_C[0] - R, SW_C[1] - 0.02, SW_C[2] - 0.015], [SW_C[0] + R, SW_C[1] + 0.02, SW_C[2] + 0.015], "dashDark", tessellate=False),
    box([SW_C[0] - 0.02, SW_C[1] - R, SW_C[2] - 0.015], [SW_C[0] + 0.02, SW_C[1], SW_C[2] + 0.015], "dashDark", tessellate=False),
    box([SW_C[0] - 0.02, SW_C[1] + R - 0.05, SW_C[2] - 0.03], [SW_C[0] + 0.02, SW_C[1] + R + 0.02, SW_C[2] + 0.03], "marker", tessellate=False),
]

model = {
    "_comment": "Generated by tools/gen_test_suv.py. Shapes: box, hexa, quad, cylinder. Faces: D U B(-z) F(+z) R(-x) L(+x).",
    "tessellation": 0.3,
    "colors": {
        "paint": "#D4541C", "under": "#1C1D20", "trim": "#232427", "bumper": "#2E3035", "grille": "#111214",
        "chrome": "#C4C8CE", "lamp": "#FFF3C4", "indicator": "#FF9A1F", "tail": "#B5121B", "glass": "#9CC4E4",
        "engine": "#6C7078", "engineTop": "#2A2C30", "radiator": "#3E4248", "battery": "#15171A",
        "seat": "#5A4636", "seatDark": "#3F3126", "dash": "#2A2B2F", "dashDark": "#18191C", "gauge": "#E8F2FF",
        "screen": "#2C6FB0", "carpet": "#2B2824", "doorTrim": "#4A3A2E", "plate": "#F2F2F2", "wheelRim": "#151515",
        "marker": "#D4541C"
    },
    "parts": [
        {"name": "body", "shapes": body},
        {"name": "interior", "shapes": interior},
        {"name": "steeringWheel", "role": "steering", "pivot": SW_C, "hingeAxis": [0, 0, 1], "hingeAngle": -110, "shapes": steering},
        {"name": "hood", "pivot": [0, BELT, COWL], "hingeAxis": [1, 0, 0], "hingeAngle": -34, "shapes": hood},
        {"name": "frontBumper", "pivot": [W, 0.35, FRONT + 0.05], "hingeAxis": [0, 0, 1], "hingeAngle": 13, "shapes": front_bumper},
        {"name": "rearBumper", "pivot": [-W, 0.35, REAR - 0.05], "hingeAxis": [0, 0, 1], "hingeAngle": -13, "shapes": rear_bumper},
        {"name": "doorFL", "pivot": [W, 0.6, COWL - 0.10], "hingeAxis": [0, 1, 0], "hingeAngle": -38, "shapes": door_fl},
        {"name": "doorFR", "pivot": [-W, 0.6, COWL - 0.10], "hingeAxis": [0, 1, 0], "hingeAngle": 38, "shapes": [flip(s) for s in door_fl]},
        {"name": "doorRL", "pivot": [W, 0.6, -0.05], "hingeAxis": [0, 1, 0], "hingeAngle": -38, "shapes": door_rl},
        {"name": "doorRR", "pivot": [-W, 0.6, -0.05], "hingeAxis": [0, 1, 0], "hingeAngle": 38, "shapes": [flip(s) for s in door_rl]},
        {"name": "tailgate", "pivot": [0, RT, ROOF_R], "hingeAxis": [1, 0, 0], "hingeAngle": 42, "shapes": tailgate},
    ],
}

# ---------------------------------------------------------------- physics
WHEELBASE, TRACK = 2.9, 1.72
MOUNT, SUSP_LEN = 0.55, 0.60
HUB_Y = MOUNT - SUSP_LEN + 0.155
def wheel_part(name, i):
    x = (1 if i % 2 == 0 else -1) * TRACK / 2
    z = (1 if i < 2 else -1) * WHEELBASE / 2
    return {"name": name, "anchor": [x, round(HUB_Y, 3), z], "health": 100, "detachThreshold": 1000, "radius": 1.0, "mass": 28, "wheel": i}

vehicle = {
    "id": "test_suv",
    "displayName": "Test SUV",
    "mass": 1900,
    "centerOfMass": [0, 0.28, 0.08],
    "inertiaBox": [1.9, 1.3, 4.4],
    "wheelBase": WHEELBASE,
    "trackWidth": TRACK,
    "dragArea": 1.15,
    "rollingResistance": 0.014,
    "engine": {
        "idleRPM": 850, "redline": 6200, "inertia": 0.28,
        "torqueCurve": [[0, 180], [1000, 300], [2000, 380], [3000, 430], [4000, 440], [5000, 400], [6000, 330], [6500, 240]],
        "engineBraking": 90, "friction": 18,
        "normalTemperature": 90, "overheatTemperature": 115, "failTemperature": 135
    },
    "gearbox": {
        "reverse": -4.0, "gears": [4.2, 2.5, 1.65, 1.2, 0.92, 0.74], "finalDrive": 5.3, "efficiency": 0.9,
        "shiftTime": 0.28, "drive": "awd", "frontTorqueSplit": 0.45, "clutchMaxTorque": 780
    },
    "suspension": {
        "mountHeight": MOUNT, "length": SUSP_LEN, "travel": 0.36,
        "springStiffness": 31000, "damperCompression": 3000, "damperRebound": 4200, "antiRoll": 9000,
        "stepAssistReach": 0.5, "stepAssistHeight": 1.1
    },
    "wheels": {"radius": 0.5, "width": 0.32, "mass": 28, "inertia": 2.4, "grip": 1.08,
               "peakSlipAngleDeg": 8, "slideGrip": 0.82, "axleOffset": 0},
    "steering": {"maxAngleDeg": 36, "speedSensitivity": 14, "minAngleDeg": 5, "steeringSpeed": 2.6, "steeringReturnSpeed": 4.2},
    "brakes": {"maxTorque": 4200, "frontBias": 0.62, "handbrakeTorque": 6500},
    "damage": {
        "boundsMin": [-1.08, -0.05, -2.55], "boundsMax": [1.08, 1.75, 2.55], "lattice": [5, 4, 9],
        "minImpactSpeed": 3.0, "deformPerSpeed": 0.034, "maxDeform": 0.55, "deformRadius": 1.35,
        "partDamagePerSpeed": 6.0, "radiatorZ": 2.1,
        "parts": [
            {"name": "hood", "anchor": [0, 1.05, 1.65], "health": 100, "detachThreshold": 85, "radius": 1.5, "mass": 18, "wheel": -1},
            {"name": "frontBumper", "anchor": [0, 0.36, 2.42], "health": 70, "detachThreshold": 60, "radius": 1.5, "mass": 12, "wheel": -1},
            {"name": "rearBumper", "anchor": [0, 0.36, -2.42], "health": 70, "detachThreshold": 60, "radius": 1.5, "mass": 12, "wheel": -1},
            {"name": "doorFL", "anchor": [1.0, 0.65, 0.45], "health": 90, "detachThreshold": 75, "radius": 1.15, "mass": 22, "wheel": -1},
            {"name": "doorFR", "anchor": [-1.0, 0.65, 0.45], "health": 90, "detachThreshold": 75, "radius": 1.15, "mass": 22, "wheel": -1},
            {"name": "doorRL", "anchor": [1.0, 0.65, -0.42], "health": 90, "detachThreshold": 75, "radius": 1.15, "mass": 20, "wheel": -1},
            {"name": "doorRR", "anchor": [-1.0, 0.65, -0.42], "health": 90, "detachThreshold": 75, "radius": 1.15, "mass": 20, "wheel": -1},
            {"name": "tailgate", "anchor": [0, 1.0, -2.3], "health": 100, "detachThreshold": 85, "radius": 1.4, "mass": 20, "wheel": -1},
            wheel_part("wheelFL", 0), wheel_part("wheelFR", 1), wheel_part("wheelRL", 2), wheel_part("wheelRR", 3),
        ]
    },
    "seats": [
        {"x": SX, "y": -0.27, "z": -0.05, "driver": True},
        {"x": -SX, "y": -0.27, "z": -0.05, "driver": False}
    ],
    "cameras": {"interior": [SX, 1.36, -0.02], "hood": [0, 1.32, 1.05], "chaseDistance": 7.5, "chaseHeight": 2.6, "orbitDistance": 8.0},
    "collision": []
}

os.makedirs(OUT, exist_ok=True)
with open(os.path.join(OUT, "model.json"), "w") as f:
    json.dump(model, f, indent=1)
with open(os.path.join(OUT, "vehicle.json"), "w") as f:
    json.dump(vehicle, f, indent=2)
print("wrote", OUT)
