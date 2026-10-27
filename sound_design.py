"""Synthesised music bed + SFX for the Appnova ad (no samples needed).
Writes build/music_sfx.wav, timed from timeline.py.
"""
import os
import numpy as np
from scipy.signal import butter, sosfilt, fftconvolve
from scipy.io import wavfile
from timeline import DURATION, IMPACTS, WHOOSHES, RISERS, SCENES

SR = 44100
N = int(DURATION * SR) + SR
rng = np.random.default_rng(11)
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "build", "music_sfx.wav")

def tt(n): return np.arange(n) / SR
def lp(x, f, o=2): return sosfilt(butter(o, f, "low", fs=SR, output="sos"), x)
def hp(x, f, o=2): return sosfilt(butter(o, f, "high", fs=SR, output="sos"), x)
def bp(x, lo, hi, o=2): return sosfilt(butter(o, [lo, hi], "band", fs=SR, output="sos"), x)

L = np.zeros(N); R = np.zeros(N)       # dry
RV = np.zeros(N)                        # reverb send (mono)

def put(sig, t0, gain=1.0, pan=0.0, rev=0.0):
    i = int(t0 * SR)
    if i >= N: return
    sig = sig[: N - i]
    l = np.cos((pan + 1) * np.pi / 4); r = np.sin((pan + 1) * np.pi / 4)
    L[i:i + len(sig)] += sig * gain * l * 1.41
    R[i:i + len(sig)] += sig * gain * r * 1.41
    RV[i:i + len(sig)] += sig * gain * rev

# ---------------- instruments
def kick(dur=0.45):
    t = tt(int(dur * SR))
    f = 45 + 110 * np.exp(-t * 28)
    ph = 2 * np.pi * np.cumsum(f) / SR
    s = np.sin(ph) * np.exp(-t * 7.5)
    click = hp(rng.standard_normal(len(t)), 2000) * np.exp(-t * 300) * 0.25
    return np.tanh((s + click) * 1.6)

def hat(dur=0.06):
    t = tt(int(dur * SR))
    return hp(rng.standard_normal(len(t)), 7000, 4) * np.exp(-t * 70) * 0.5

def impact(strength=1.0, dur=2.5):
    t = tt(int(dur * SR))
    f = 32 + 90 * np.exp(-t * 9)
    sub = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * (2.2 / strength))
    noise = lp(rng.standard_normal(len(t)), 3500) * np.exp(-t * 14) * 0.6
    body = bp(rng.standard_normal(len(t)), 80, 400) * np.exp(-t * 6) * 0.8
    return np.tanh((sub * 1.3 + noise + body) * 1.4) * 0.9

def whoosh(dur=0.55, up=True):
    n = int(dur * SR); t = tt(n)
    env = np.sin(np.pi * np.clip(t / dur, 0, 1)) ** 2
    if up: env = env * (t / dur) ** 0.6
    noise = rng.standard_normal(n)
    lo, mid, hi = bp(noise, 150, 700), bp(noise, 700, 2500), bp(noise, 2500, 9000)
    c = (t / dur) if up else np.sin(np.pi * t / dur)
    out = lo * (1 - c) + mid * (1 - abs(2 * c - 1)) * 1.3 + hi * c * 1.2
    return out * env * 0.9

def riser(dur):
    n = int(dur * SR); t = tt(n); x = t / dur
    f = 180 + 1400 * x ** 2.2
    tone = np.sin(2 * np.pi * np.cumsum(f) / SR) * 0.25 + np.sin(2 * np.pi * np.cumsum(f * 1.5) / SR) * 0.12
    nz = hp(rng.standard_normal(n), 1500) * 0.35
    return (tone + nz) * x ** 2.5

def glitch_blip(dur=0.12):
    n = int(dur * SR); t = tt(n)
    sq = np.sign(np.sin(2 * np.pi * (900 + 600 * rng.uniform()) * t)) * 0.25
    gate = (np.floor(t * 70) % 2)
    return sq * gate * np.exp(-t * 18)

def tick():
    t = tt(int(0.03 * SR))
    return np.sin(2 * np.pi * 2400 * t) * np.exp(-t * 220) * 0.4

def shimmer(dur=1.2):
    n = int(dur * SR); t = tt(n); out = np.zeros(n)
    for k, f in enumerate([1760, 2217, 2637, 3520]):
        out += np.sin(2 * np.pi * f * t + k) * np.exp(-((t - 0.15 * k - 0.2) * 5) ** 2)
    return out * 0.18

def saw(f, n, harmonics=12):
    t = tt(n); s = np.zeros(n)
    for h in range(1, harmonics + 1):
        s += np.sin(2 * np.pi * f * h * t) / h
    return s

# ---------------- music bed: dark pad in D minor + pulse
total = N
pad = np.zeros(total)
chords = [(0.0, [73.42, 110.0, 146.83, 174.61]),      # Dm
          (7.0, [58.27, 116.54, 146.83, 174.61]),     # Bb
          (10.0, [65.41, 98.0, 130.81, 196.0]),       # C
          (13.6, [73.42, 110.0, 146.83, 220.0]),      # Dm (bright top)
          ]
for idx, (t0, notes) in enumerate(chords):
    t1 = chords[idx + 1][0] if idx + 1 < len(chords) else DURATION + 1
    n = int((t1 - t0 + 0.6) * SR)
    s = sum(saw(f * (1 + d), n, 8) for f in notes for d in (-0.003, 0.003)) / (len(notes) * 2)
    s = lp(s, 900)
    tt_ = tt(n); env = np.clip(tt_ / 0.4, 0, 1) * np.clip((t1 - t0 + 0.6 - tt_) / 0.6, 0, 1)
    i = int(t0 * SR); pad[i:i + n] += (s * env)[: total - i]
put(pad, 0, 0.22, rev=0.25)

# sub drone
d = np.sin(2 * np.pi * 36.71 * tt(total)) * 0.35
put(d * np.clip(tt(total) / 1.5, 0, 1), 0, 0.5)

# pulse: kick + hats from 3.2 to 13.4 (120 bpm), break before logo
beat = 0.5
k = kick()
t = 3.2
while t < 13.3:
    put(k, t, 0.75)
    if t >= 7.0:
        put(hat(), t + beat / 2, 0.35, pan=0.3)
    t += beat
# double-time build into the logo
t = 12.6
while t < 14.25:
    put(kick(0.2), t, 0.45)
    t += 0.125 if t > 13.6 else 0.25
# beat returns softly on the end card
t = 19.0
while t < DURATION - 0.4:
    put(k, t, 0.55); put(hat(), t + 0.25, 0.25, pan=-0.3)
    t += beat

# ---------------- SFX
for t0, s in IMPACTS:
    put(impact(s), t0, 0.55 + 0.35 * min(s, 1.4), rev=0.35 * s)
for t0 in WHOOSHES:
    w = whoosh(0.55)
    put(w, t0 - 0.5, 0.55, pan=-0.4); put(whoosh(0.55), t0 - 0.5, 0.55, pan=0.4)
for a, b in RISERS:
    put(riser(b - a), a, 0.6, rev=0.2)
put(riser(0.6), 0.0, 0.35)                    # opening streak
for t0 in (0.6, 1.35, 3.12, 6.95, 9.93):
    put(glitch_blip(), t0, 0.35, pan=rng.uniform(-0.5, 0.5))
tt0 = 11.55
while tt0 < 12.95:
    put(tick(), tt0, 0.35, pan=rng.uniform(-0.3, 0.3)); tt0 += 0.045 + 0.1 * ((tt0 - 11.55) / 1.4) ** 2
for t0 in (15.05, 16.15, 21.6):
    put(shimmer(), t0, 0.7, pan=0.2, rev=0.4)
put(whoosh(0.8, up=False), 15.0, 0.35)         # wordmark wipe

# ---------------- mix: reverb, bus compression, limiter
ir_n = int(2.4 * SR); ir_t = tt(ir_n)
ir = rng.standard_normal(ir_n) * np.exp(-ir_t * 2.6)
ir = lp(ir, 5000)
wet = fftconvolve(RV, ir)[:N] * 0.02
L += wet; R += wet
mix = np.stack([L, R], 1)
mix = mix[: int(DURATION * SR)]
fade = int(0.35 * SR); mix[-fade:] *= np.linspace(1, 0, fade)[:, None]
mix = np.tanh(mix * 1.2) / np.tanh(1.2)
mix /= np.max(np.abs(mix)) / 0.89
os.makedirs(os.path.dirname(OUT), exist_ok=True)
wavfile.write(OUT, SR, (mix * 32767).astype(np.int16))
print("wrote", OUT)
