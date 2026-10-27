"""Shared timeline for the Appnova ad: video, sound design and voice-over all read from here."""

W = H = 1080
FPS = 30
DURATION = 23.0

# Scene boundaries (seconds)
SCENES = {
    "idea":    (0.0, 3.2),
    "devices": (3.2, 7.0),
    "punch":   (7.0, 10.0),
    "scale":   (10.0, 13.6),
    "logo":    (13.6, 19.0),
    "cta":     (19.0, 23.0),
}

# Hits: (time, strength). Drive flash / shake / chroma / zoom punch AND the impact sounds.
IMPACTS = [
    (0.60, 0.7),
    (3.20, 0.45),
    (5.80, 0.7),
    (7.20, 0.75),
    (7.90, 0.75),
    (8.60, 1.0),
    (10.00, 0.45),
    (14.30, 1.4),   # logo hit
    (19.00, 0.35),
    (20.20, 0.6),
]

# Whoosh sounds that lead into these cut times
WHOOSHES = [3.2, 7.0, 10.0, 19.0]

# Riser (start, end)
RISERS = [(12.6, 14.3)]

# Voice-over lines: start time (s) and text. make_voice.py places each clip exactly here.
VO = [
    {"id": "01", "start": 0.55,  "text": "Your idea... deserves more than a dream."},
    {"id": "02", "start": 3.45,  "text": "Web apps. Android apps. Built to perform."},
    {"id": "03", "start": 7.15,  "text": "Fast. Secure. Powerful."},
    {"id": "04", "start": 10.25, "text": "From the first line of code... to millions of users."},
    {"id": "05", "start": 14.70, "text": "Appnova."},
    {"id": "06", "start": 16.70, "text": "We build what's next."},
    {"id": "07", "start": 19.70, "text": "Start your project today."},
]
