"""Appnova ad — motion graphics renderer.

Renders every frame in Python (numpy + Pillow + scipy) and pipes them to ffmpeg.
Usage:  python render.py            -> build/appnova_video_nosound.mp4
        python render.py --frames 430,520   (export single PNG frames for review)
"""
import os, sys, math, subprocess, functools
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy.ndimage import gaussian_filter, zoom
from multiprocessing import Pool

from timeline import W, H, FPS, DURATION, SCENES, IMPACTS

ROOT = os.path.dirname(os.path.abspath(__file__))
FONTS = os.path.join(ROOT, "assets", "fonts")
F_BLACK = os.path.join(FONTS, "InterDisplay-Black.otf")
F_SEMI = os.path.join(FONTS, "InterDisplay-SemiBold.otf")
F_MED = os.path.join(FONTS, "InterDisplay-Medium.otf")
F_MONO = os.path.join(FONTS, "DejaVuSansMono-Bold.ttf")
BUILD = os.path.join(ROOT, "build")
N_FRAMES = int(round(DURATION * FPS))

# palette (0..1 floats)
def c(r, g, b): return np.array([r, g, b], np.float32) / 255.0
WHITE = c(255, 255, 255)
BLUE = c(24, 128, 255)
CYAN = c(64, 196, 255)
ICE = c(190, 228, 255)
NAVY = c(10, 22, 52)
PURPLE = c(168, 120, 255)
GRAY = c(150, 165, 190)

# ---------------------------------------------------------------- easing
def cl(x): return 0.0 if x < 0 else 1.0 if x > 1 else x
def seg(t, a, b): return cl((t - a) / (b - a))
def eo3(x): x = cl(x); return 1 - (1 - x) ** 3
def eo5(x): x = cl(x); return 1 - (1 - x) ** 5
def eio(x): x = cl(x); return 4 * x ** 3 if x < .5 else 1 - (-2 * x + 2) ** 3 / 2
def eback(x, s=1.9):
    x = cl(x); return 1 + (s + 1) * (x - 1) ** 3 + s * (x - 1) ** 2
def decay(t, t0, rate):
    return 0.0 if t < t0 else math.exp(-(t - t0) * rate)

# ---------------------------------------------------------------- precomputed fields
YY, XX = np.mgrid[0:H, 0:W].astype(np.float32)
DC = np.hypot(XX - W / 2, YY - H / 2)
AC = np.arctan2(YY - H / 2, XX - W / 2)

def _base():
    g = np.clip(1 - DC / (W * 0.85), 0, 1) ** 1.6
    base = np.zeros((H, W, 3), np.float32)
    base += NAVY * (0.25 + 0.75 * g[..., None])
    base += c(2, 3, 9) * (1 - g[..., None])
    return base
BASE = _base()
BLOB = np.exp(-((XX - W / 2) ** 2 + (YY - H / 2) ** 2) / (2 * 260.0 ** 2)).astype(np.float32)
VIGN = (1 - 0.55 * np.clip(DC / (W * 0.78), 0, 1) ** 2.2).astype(np.float32)[..., None]
_rng = np.random.default_rng(7)
GRAIN = [(_rng.standard_normal((H, W, 1)) * 0.009).astype(np.float32) for _ in range(6)]

# ambient particles
P_N = 150
P_X = _rng.uniform(0, W, P_N); P_Y = _rng.uniform(0, H, P_N)
P_Z = _rng.uniform(0.2, 1.0, P_N); P_PH = _rng.uniform(0, 6.28, P_N)
# burst particles (logo hit)
B_N = 110
B_ANG = _rng.uniform(0, 2 * np.pi, B_N); B_SP = _rng.uniform(250, 900, B_N); B_LEN = _rng.uniform(10, 45, B_N)

# network globe (fibonacci sphere)
G_N = 150
_i = np.arange(G_N) + 0.5
_phi = np.arccos(1 - 2 * _i / G_N); _th = np.pi * (1 + 5 ** 0.5) * _i
GP = np.stack([np.cos(_th) * np.sin(_phi), np.cos(_phi), np.sin(_th) * np.sin(_phi)], 1)
_d = np.linalg.norm(GP[:, None] - GP[None], axis=2)
G_EDGES = [(a, b) for a in range(G_N) for b in range(a + 1, G_N) if _d[a, b] < 0.30]

# ---------------------------------------------------------------- logo assets
_logo = Image.open(os.path.join(ROOT, "assets", "logo.png")).convert("RGB")
MARK = _logo.crop((262, 213, 1024, 850))     # the "A" symbol
WORD = _logo.crop((160, 846, 1094, 1046))    # "Appnova" wordmark
# layout anchor: original point (627, 636) is the logo's visual centre
MARK_C = ((262 + 1024) / 2 - 627, (213 + 850) / 2 - 636)
WORD_C = ((160 + 1094) / 2 - 627, (846 + 1046) / 2 - 636)

# ---------------------------------------------------------------- compositing helpers
def _clip_box(x0, y0, w, h):
    X0, Y0 = max(x0, 0), max(y0, 0); X1, Y1 = min(x0 + w, W), min(y0 + h, H)
    if X1 <= X0 or Y1 <= Y0: return None
    return X0, Y0, X1, Y1, X0 - x0, Y0 - y0

def over(F, rgb, a, x0, y0):
    b = _clip_box(x0, y0, a.shape[1], a.shape[0])
    if not b: return
    X0, Y0, X1, Y1, ox, oy = b
    aa = a[oy:oy + Y1 - Y0, ox:ox + X1 - X0][..., None]
    rr = rgb[oy:oy + Y1 - Y0, ox:ox + X1 - X0] if rgb.ndim == 3 else rgb
    F[Y0:Y1, X0:X1] = F[Y0:Y1, X0:X1] * (1 - aa) + rr * aa

def add(F, rgb, x0, y0, k=1.0):
    b = _clip_box(x0, y0, rgb.shape[1], rgb.shape[0])
    if not b: return
    X0, Y0, X1, Y1, ox, oy = b
    F[Y0:Y1, X0:X1] += rgb[oy:oy + Y1 - Y0, ox:ox + X1 - X0] * k

def screen(F, rgb, x0, y0, k=1.0):
    b = _clip_box(x0, y0, rgb.shape[1], rgb.shape[0])
    if not b: return
    X0, Y0, X1, Y1, ox, oy = b
    L = np.clip(rgb[oy:oy + Y1 - Y0, ox:ox + X1 - X0] * k, 0, 1)
    R = F[Y0:Y1, X0:X1]
    F[Y0:Y1, X0:X1] = 1 - (1 - np.clip(R, 0, 1)) * (1 - L) + np.maximum(R - 1, 0)

def add_mask(F, m, color, k=1.0):
    F += m[..., None] * (color * k)

# ---------------------------------------------------------------- text
@functools.lru_cache(maxsize=64)
def font(path, size): return ImageFont.truetype(path, size)

@functools.lru_cache(maxsize=512)
def text_img(txt, size, path=F_BLACK, track=0, top=(1, 1, 1), bot=None):
    f = font(path, size)
    asc, desc = f.getmetrics()
    widths = [f.getlength(ch) for ch in txt]
    tw = int(sum(widths) + track * max(len(txt) - 1, 0)) + 8
    th = asc + desc + 8
    m = Image.new("L", (max(tw, 1), th), 0)
    d = ImageDraw.Draw(m)
    x = 4
    for ch, w in zip(txt, widths):
        d.text((x, 4), ch, font=f, fill=255)
        x += w + track
    a = np.asarray(m, np.float32) / 255.0
    ys = np.where(a.max(1) > 0)[0]
    if len(ys): a = a[max(ys[0] - 2, 0):ys[-1] + 3]
    top = np.array(top, np.float32)
    if bot is None:
        rgb = np.broadcast_to(top, a.shape + (3,)).copy()
    else:
        g = np.linspace(0, 1, a.shape[0], dtype=np.float32)[:, None, None]
        rgb = (top * (1 - g) + np.array(bot, np.float32) * g) * np.ones((1, a.shape[1], 1), np.float32)
    return rgb.astype(np.float32), a

def draw_text(F, txt, cx, cy, size, path=F_BLACK, top=WHITE, bot=None, track=0, scale=1.0,
              opacity=1.0, blur=0.0, glow=0.0, glowcol=CYAN, glow_r=14):
    if opacity <= 0.003: return
    s = max(int(round(size * scale)), 4)
    rgb, a = text_img(txt, s, path, int(round(track * scale)), tuple(map(float, top)),
                      None if bot is None else tuple(map(float, bot)))
    a = a * opacity
    if blur > 0.4:
        p = int(blur * 3)
        a = gaussian_filter(np.pad(a, p), blur)
        rgb = np.pad(rgb, ((p, p), (p, p), (0, 0)), mode="edge")
    h, w = a.shape
    x0, y0 = int(cx - w / 2), int(cy - h / 2)
    if glow > 0:
        p = glow_r * 3
        g = gaussian_filter(np.pad(a, p), glow_r)
        add(F, g[..., None] * glowcol, x0 - p, y0 - p, glow)
    over(F, rgb, a, x0, y0)
    return (x0, y0, w, h)

def text_width(txt, size, path=F_BLACK, track=0):
    return text_img(txt, int(size), path, int(track), (1.0, 1.0, 1.0), None)[1].shape[1]

# ---------------------------------------------------------------- vector-ish helpers
def lines_layer():
    return Image.new("L", (W, H), 0)

def apply_layer(F, L, color, k=1.0, glow=0.0, glow_r=8):
    a = np.asarray(L, np.float32) / 255.0
    if glow > 0:
        small = a[::2, ::2]
        g = zoom(gaussian_filter(small, glow_r / 2), 2, order=1)[:H, :W]
        add_mask(F, g, color, glow)
    add_mask(F, a, color, k)

def rrect_path(x0, y0, x1, y1, r, n_arc=10):
    pts = []
    def arc(cx, cy, a0):
        for i in range(n_arc + 1):
            a = math.radians(a0 + 90 * i / n_arc)
            pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    pts.append((x0 + r, y0)); pts.append((x1 - r, y0)); arc(x1 - r, y0 + r, -90)
    pts.append((x1, y1 - r)); arc(x1 - r, y1 - r, 0)
    pts.append((x0 + r, y1)); arc(x0 + r, y1 - r, 90)
    pts.append((x0, y0 + r)); arc(x0 + r, y0 + r, 180)
    # resample evenly
    P = np.array(pts); segl = np.hypot(*np.diff(P, axis=0).T); cum = np.concatenate([[0], np.cumsum(segl)])
    s = np.linspace(0, cum[-1], 500)
    return np.stack([np.interp(s, cum, P[:, 0]), np.interp(s, cum, P[:, 1])], 1)

def ring(F, cx, cy, r, width, k, color):
    if k <= 0.003 or r <= 0: return
    d = np.hypot(XX - cx, YY - cy)
    add_mask(F, np.exp(-((d - r) / width) ** 2) * k, color)

def rays(F, cx, cy, n, rot, sharp, k, color, falloff=700):
    if k <= 0.003: return
    A = np.arctan2(YY - cy, XX - cx); D = np.hypot(XX - cx, YY - cy)
    m = (0.5 + 0.5 * np.cos(A * n + rot)) ** sharp * np.clip(D / 120, 0, 1) * np.exp(-D / falloff)
    add_mask(F, m * k, color)

def glitch(img, amt, seed):
    if amt < 0.05: return img
    r = np.random.default_rng(seed)
    out = img.copy()
    for _ in range(int(3 + 8 * amt)):
        y = r.integers(0, H - 40); h = r.integers(6, 50); dx = int(r.integers(-60, 60) * amt)
        out[y:y + h] = np.roll(out[y:y + h], dx, axis=1)
    return out

# ---------------------------------------------------------------- background
def background(t, grid_k, part_k, blob_k):
    F = BASE.copy()
    # drifting light blob
    ox = int(160 * math.sin(t * 0.35)); oy = int(110 * math.cos(t * 0.27))
    b = np.roll(np.roll(BLOB, ox, 1), oy, 0)
    F += b[..., None] * (BLUE * 0.22 * blob_k)
    L = lines_layer(); d = ImageDraw.Draw(L)
    if grid_k > 0.01:
        hz = H * 0.60; vx = W / 2
        for i in range(-14, 15):
            x_far = vx + i * 34; x_near = vx + i * 330
            d.line([(x_far, hz), (x_near, H + 40)], fill=int(70 * grid_k), width=1)
        sc = (t * 0.55) % 1.0
        for j in range(14):
            z = (j + 1 - sc)
            y = hz + 900 / (z * 1.6 + 0.4) - 900 / (14 * 1.6 + 0.4)
            if hz < y < H + 5:
                br = int(min(1, (y - hz) / 260) * 90 * grid_k)
                d.line([(0, y), (W, y)], fill=br, width=1)
    P = lines_layer(); dp = ImageDraw.Draw(P)
    if part_k > 0.01:
        for i in range(P_N):
            z = P_Z[i]
            x = (P_X[i] + 18 * z * math.sin(t * 0.5 + P_PH[i])) % W
            y = (P_Y[i] - t * 26 * z) % H
            r = 0.6 + 2.2 * z * z
            br = int(255 * part_k * (0.25 + 0.75 * z) * (0.6 + 0.4 * math.sin(t * 2 + P_PH[i])))
            dp.ellipse([x - r, y - r, x + r, y + r], fill=br)
    apply_layer(F, L, BLUE, 1.0, glow=0.0)
    apply_layer(F, P, ICE, 0.55, glow=1.2, glow_r=6)
    return F

# ---------------------------------------------------------------- logo
def logo(F, t, cx, cy, s, rev_mark, rev_word, sheen_p, glow_pulse, hit_scale=1.0):
    """Draw mark + wordmark with angular reveal, wipe reveal and a light sheen."""
    # --- mark
    if rev_mark > 0:
        ms = s * hit_scale
        mw, mh = int(MARK.width * ms), int(MARK.height * ms)
        m = np.asarray(MARK.resize((mw, mh), Image.BICUBIC), np.float32) / 255.0
        lum = m.max(2)
        yy, xx = np.mgrid[0:mh, 0:mw].astype(np.float32)
        ang = (np.arctan2(xx - mw / 2, -(yy - mh / 2)) + 2 * np.pi) % (2 * np.pi)
        soft = 0.9
        theta = rev_mark * (2 * np.pi + soft)
        mask = np.clip((theta - ang) / soft, 0, 1)
        front = np.exp(-((ang - theta + soft * 0.55) / 0.16) ** 2) * (rev_mark < 1) * lum
        layer = m * mask[..., None] + front[..., None] * (ICE * 1.4)
        mx = cx + MARK_C[0] * s - mw / 2; my = cy + MARK_C[1] * s - mh / 2
        if sheen_p is not None:
            u = (xx + mx) + (yy + my) * 0.55
            band = np.exp(-((u - sheen_p) / 55) ** 2) * (lum ** 1.5) * mask
            layer = layer + band[..., None] * 0.95
        # glow
        gl = gaussian_filter(lum * mask, 18 * s)
        add(F, gl[..., None] * BLUE, int(mx), int(my), 0.38 * glow_pulse)
        screen(F, layer, int(mx), int(my))
    # --- wordmark
    if rev_word > 0:
        ww, wh = int(WORD.width * s), int(WORD.height * s)
        wimg = np.asarray(WORD.resize((ww, wh), Image.BICUBIC), np.float32) / 255.0
        lum = wimg.max(2)
        xx = np.arange(ww, dtype=np.float32)[None, :]
        p = -60 + rev_word * (ww + 120)
        mask = np.clip((p - xx) / 45, 0, 1) * np.ones((wh, 1), np.float32)
        edge = np.exp(-((xx - p + 22) / 10) ** 2) * (rev_word < 1)
        vy = np.exp(-((np.arange(wh, dtype=np.float32)[:, None] - wh / 2) / (wh * 0.42)) ** 2)
        lift = (1 - eo3(rev_word)) * 18
        wx = cx + WORD_C[0] * s - ww / 2; wy = cy + WORD_C[1] * s - wh / 2 + lift
        layer = wimg * mask[..., None] + (edge * vy)[..., None] * (CYAN * 1.2)
        if sheen_p is not None:
            yy2 = np.arange(wh, dtype=np.float32)[:, None]
            u = (xx + wx) + (yy2 + wy) * 0.55
            band = np.exp(-((u - sheen_p) / 55) ** 2) * (lum ** 1.5) * mask
            layer = layer + band[..., None] * 0.95
        gl = gaussian_filter(lum * mask, 12 * s)
        add(F, gl[..., None] * BLUE, int(wx), int(wy), 0.25 * glow_pulse)
        screen(F, layer, int(wx), int(wy))

# ---------------------------------------------------------------- scenes
def sc_idea(F, t):
    a, b = SCENES["idea"]
    # opening anamorphic streak
    st = seg(t, 0.0, 0.6)
    if t < 1.2:
        w = eo3(st) * 900; k = (1 - seg(t, 0.6, 1.2)) * 1.0
        L = lines_layer(); d = ImageDraw.Draw(L)
        d.line([(W / 2 - w / 2, 470), (W / 2 + w / 2, 470)], fill=255, width=3)
        apply_layer(F, L, CYAN, 0.9 * k, glow=2.5 * k, glow_r=10)
    out = seg(t, 2.85, 3.2)
    o = 1 - out
    # YOUR IDEA — slam
    p = seg(t, 0.6, 0.95)
    if t >= 0.6:
        draw_text(F, "YOUR IDEA", W / 2, 470, 150, top=WHITE, bot=ICE, track=4,
                  scale=(1.0 + 0.6 * (1 - eback(p))) * (1 + 0.04 * seg(t, 0.95, 3.2)) * (1 + 0.10 * eo3(out)),
                  opacity=eo3(p * 1.5) * o, blur=10 * (1 - eo3(p)) + 8 * out, glow=0.55, glow_r=16)
    # DESERVES MORE / THAN A DREAM
    p2 = seg(t, 1.35, 1.85)
    draw_text(F, "DESERVES MORE", W / 2, 600, 64, path=F_SEMI, top=WHITE, track=26 - 16 * eo3(p2),
              opacity=eo3(p2) * o, blur=6 * (1 - eo3(p2)))
    p3 = seg(t, 1.85, 2.35)
    draw_text(F, "THAN A DREAM.", W / 2, 680, 64, path=F_SEMI, top=CYAN, bot=BLUE, track=26 - 16 * eo3(p3),
              opacity=eo3(p3) * o, blur=6 * (1 - eo3(p3)), glow=0.5, glow_r=12)
    # thin underline sweeping
    if t > 2.0:
        L = lines_layer(); d = ImageDraw.Draw(L)
        u = eio(seg(t, 2.0, 2.6)) * 420
        d.line([(W / 2 - u, 735), (W / 2 + u, 735)], fill=int(255 * o), width=2)
        apply_layer(F, L, CYAN, 0.8, glow=1.5)

def sc_devices(F, t):
    a, b = SCENES["devices"]
    o = 1 - seg(t, 6.75, 7.0)
    # browser window
    bx0, by0, bx1, by1 = 90, 300, 640, 700
    px0, py0, px1, py1 = 720, 240, 980, 760
    L = lines_layer(); d = ImageDraw.Draw(L)
    pb = eio(seg(t, 3.3, 4.15)); pp = eio(seg(t, 4.35, 5.15))
    heads = []
    for (x0, y0, x1, y1, r, pr) in [(bx0, by0, bx1, by1, 22, pb), (px0, py0, px1, py1, 38, pp)]:
        if pr <= 0: continue
        path = rrect_path(x0, y0, x1, y1, r)
        k = max(2, int(len(path) * pr))
        # panel fill fades in once outline is complete
        fill = seg(pr, 0.6, 1.0)
        if fill > 0:
            m = np.zeros((H, W), np.float32)
            Im = Image.new("L", (W, H), 0); ImageDraw.Draw(Im).rounded_rectangle([x0, y0, x1, y1], r, fill=255)
            m = np.asarray(Im, np.float32) / 255.0
            F[:] = F * (1 - m[..., None] * 0.55 * fill * o) + m[..., None] * (c(12, 26, 60) * 0.55 * fill * o)
        d.line([tuple(p) for p in path[:k]], fill=int(255 * o), width=3, joint="curve")
        if pr < 1: heads.append(path[k - 1])
    for hx, hy in heads:
        d.ellipse([hx - 5, hy - 5, hx + 5, hy + 5], fill=255)
    # browser chrome: header line + dots
    hb = seg(t, 4.0, 4.3)
    if hb > 0:
        d.line([(bx0, by0 + 52), (bx0 + (bx1 - bx0) * eo3(hb), by0 + 52)], fill=int(180 * o), width=2)
        for i in range(3):
            q = seg(t, 4.05 + i * 0.06, 4.25 + i * 0.06)
            r = 7 * eback(q)
            if r > 0: d.ellipse([bx0 + 30 + i * 26 - r, by0 + 26 - r, bx0 + 30 + i * 26 + r, by0 + 26 + r], fill=int(220 * o))
    # phone notch
    if pp > 0.9:
        d.rounded_rectangle([(px0 + px1) / 2 - 34, py0 + 16, (px0 + px1) / 2 + 34, py0 + 26], 5, fill=int(200 * o))
    apply_layer(F, L, CYAN, 1.0, glow=1.6, glow_r=9)

    # code bars in browser
    rows = [(0.55, PURPLE, 0), (0.75, CYAN, 1), (0.40, WHITE, 1), (0.62, BLUE, 2), (0.30, CYAN, 2),
            (0.70, PURPLE, 1), (0.48, WHITE, 0), (0.58, CYAN, 1)]
    for i, (wfrac, col, ind) in enumerate(rows):
        q = eo3(seg(t, 4.25 + i * 0.09, 4.6 + i * 0.09))
        if q <= 0: continue
        x0 = bx0 + 40 + ind * 36; y = by0 + 90 + i * 36
        wmax = (bx1 - bx0 - 90 - ind * 36) * wfrac
        Im = Image.new("L", (W, H), 0)
        ImageDraw.Draw(Im).rounded_rectangle([x0, y, x0 + max(wmax * q, 12), y + 14], 7, fill=255)
        apply_layer(F, Im, col, 0.85 * o, glow=0.6, glow_r=6)
    # phone UI cards
    for i in range(3):
        q = eback(seg(t, 5.0 + i * 0.12, 5.4 + i * 0.12))
        if q <= 0: continue
        y = py0 + 80 + i * 120
        Im = Image.new("L", (W, H), 0); dd = ImageDraw.Draw(Im)
        cx0, cx1 = px0 + 26, px1 - 26
        mid = (cx0 + cx1) / 2; half = (cx1 - cx0) / 2 * q
        dd.rounded_rectangle([mid - half, y, mid + half, y + 96], 14, outline=200, width=2)
        if q > 0.5:
            dd.rounded_rectangle([mid - half + 14, y + 16, mid - half + 70, y + 80], 10, fill=110)
            dd.rounded_rectangle([mid - half + 84, y + 26, mid - half + 84 + 100 * q, y + 38], 6, fill=230)
            dd.rounded_rectangle([mid - half + 84, y + 54, mid - half + 84 + 70 * q, y + 64], 5, fill=120)
        apply_layer(F, Im, CYAN if i != 1 else PURPLE, 0.85 * o, glow=0.6, glow_r=6)
    q = eback(seg(t, 5.45, 5.75))
    if q > 0:
        Im = Image.new("L", (W, H), 0)
        r = 30 * q; cxp, cyp = (px0 + px1) / 2, py1 - 62
        ImageDraw.Draw(Im).ellipse([cxp - r, cyp - r, cxp + r, cyp + r], fill=255)
        apply_layer(F, Im, BLUE, 1.0 * o, glow=1.5, glow_r=10)
    # labels
    for (txt, cx, cy, t0) in [("WEB APPS", (bx0 + bx1) / 2, by1 + 58, 3.9), ("ANDROID APPS", (px0 + px1) / 2, py1 + 50, 4.75)]:
        q = eo3(seg(t, t0, t0 + 0.45))
        draw_text(F, txt, cx, cy + 22 * (1 - q), 38, path=F_SEMI, top=WHITE, track=8,
                  opacity=q * o, glow=0.35, glow_r=10)
    # BUILT TO PERFORM — slam
    q = seg(t, 5.8, 6.1)
    if t >= 5.8:
        draw_text(F, "BUILT TO PERFORM", W / 2, 150, 84, top=WHITE, bot=CYAN, track=3,
                  scale=1 + 0.5 * (1 - eback(q)) + 0.03 * seg(t, 6.1, 7.0), opacity=eo3(q * 1.6) * o,
                  blur=8 * (1 - eo3(q)), glow=0.6, glow_r=16)

def sc_punch(F, t):
    words = [("FAST", 7.2, "OPTIMIZED PERFORMANCE"), ("SECURE", 7.9, "PROTECTED BY DESIGN"),
             ("POWERFUL", 8.6, "BUILT TO SCALE")]
    o = 1 - seg(t, 9.8, 10.0)
    cur = None
    for wd in words:
        if t >= wd[1]: cur = wd
    # background energy
    hit = max([decay(t, w[1], 4) for w in words] + [0])
    rays(F, W / 2, H / 2, 18, t * 0.8, 6, (0.10 + 0.25 * hit) * o * seg(t, 7.0, 7.3), BLUE, falloff=650)
    if cur is None: return
    word, t0, sub = cur
    q = seg(t, t0, t0 + 0.2)
    if word == "FAST":
        # horizontal speed streaks
        L = lines_layer(); d = ImageDraw.Draw(L); r = np.random.default_rng(3)
        for i in range(26):
            y = r.uniform(150, 930); ln = r.uniform(120, 420); sp = r.uniform(1800, 3200)
            x = (r.uniform(0, W) + (t - t0) * sp) % (W + ln) - ln
            d.line([(x, y), (x + ln, y)], fill=int(r.uniform(80, 220)), width=int(r.uniform(1, 4)))
        apply_layer(F, L, CYAN, 0.7 * o, glow=1.0, glow_r=6)
    elif word == "SECURE":
        # rotating hexagon shield
        L = lines_layer(); d = ImageDraw.Draw(L)
        for k_, (R, rot) in enumerate([(330, t * 0.6), (380, -t * 0.4)]):
            R = R * (0.85 + 0.15 * eo3(seg(t, t0, t0 + 0.4)))
            pts = [(W / 2 + R * math.cos(rot + i * math.pi / 3), H / 2 - 10 + R * math.sin(rot + i * math.pi / 3)) for i in range(7)]
            d.line(pts, fill=int(200 - k_ * 90), width=3 - k_, joint="curve")
        apply_layer(F, L, BLUE, 0.9 * o, glow=1.4, glow_r=10)
    else:
        ring(F, W / 2, H / 2 - 10, eo3(seg(t, t0, t0 + 0.9)) * 640, 10, 0.9 * (1 - seg(t, t0, t0 + 0.9)), CYAN)
        ring(F, W / 2, H / 2 - 10, eo3(seg(t, t0 + 0.08, t0 + 1.1)) * 520, 5, 0.6 * (1 - seg(t, t0 + 0.08, t0 + 1.1)), WHITE)
    grow = 1 + 0.06 * seg(t, t0 + 0.2, t0 + 1.4)
    draw_text(F, word, W / 2, H / 2 - 20, 210 if word != "POWERFUL" else 150, top=WHITE,
              bot=ICE if word != "POWERFUL" else CYAN, track=6,
              scale=(1 + 0.45 * (1 - eback(q))) * grow, opacity=eo3(q * 2) * o, blur=7 * (1 - eo3(q)),
              glow=0.7, glow_r=18)
    q2 = eo3(seg(t, t0 + 0.12, t0 + 0.45))
    draw_text(F, sub, W / 2, H / 2 + 120, 30, path=F_SEMI, top=CYAN, track=10 + 10 * (1 - q2),
              opacity=q2 * o)

def sc_scale(F, t):
    o = 1 - seg(t, 13.35, 13.6)
    # network globe
    gk = eo3(seg(t, 10.0, 10.8)) * o
    ry = t * 0.45; rx = 0.35
    P = GP.copy()
    cy_, sy_ = math.cos(ry), math.sin(ry)
    x = P[:, 0] * cy_ + P[:, 2] * sy_; z = -P[:, 0] * sy_ + P[:, 2] * cy_; y = P[:, 1]
    y2 = y * math.cos(rx) - z * math.sin(rx); z2 = y * math.sin(rx) + z * math.cos(rx)
    R = 330 * (0.9 + 0.1 * eo3(seg(t, 10.0, 11.0)))
    persp = 1 / (1.6 - z2 * 0.45)
    sx = W / 2 + x * R * persp * 1.3; sy = 640 + y2 * R * persp * 1.3
    depth = (z2 + 1) / 2
    L = lines_layer(); d = ImageDraw.Draw(L)
    for a_, b_ in G_EDGES:
        br = int(25 + 90 * (depth[a_] + depth[b_]) / 2)
        d.line([(sx[a_], sy[a_]), (sx[b_], sy[b_])], fill=br, width=1)
    Dt = lines_layer(); dd = ImageDraw.Draw(Dt)
    lit = int(seg(t, 11.6, 13.0) * G_N)
    for i in range(G_N):
        r = 1.5 + 3 * depth[i]
        br = int(60 + 195 * depth[i]) if i < lit or (i * 7919) % 5 == 0 else int(40 + 60 * depth[i])
        dd.ellipse([sx[i] - r, sy[i] - r, sx[i] + r, sy[i] + r], fill=br)
    apply_layer(F, L, BLUE, 0.85 * gk)
    apply_layer(F, Dt, ICE, 0.9 * gk, glow=1.2, glow_r=6)
    # code chip with typing
    q = eo3(seg(t, 10.1, 10.45))
    lbl = eo3(seg(t, 10.15, 10.55))
    draw_text(F, "FROM THE FIRST LINE OF CODE", W / 2, 165, 30, path=F_SEMI, top=GRAY, track=10,
              opacity=lbl * o)
    if q > 0:
        Im = Image.new("L", (W, H), 0)
        half = 330 * q
        ImageDraw.Draw(Im).rounded_rectangle([W / 2 - half, 215, W / 2 + half, 300], 18, fill=255)
        m = np.asarray(Im, np.float32)[..., None] / 255.0
        F[:] = F * (1 - m * 0.75 * o) + m * (c(8, 16, 38) * 0.75 * o)
        Im2 = Image.new("L", (W, H), 0)
        ImageDraw.Draw(Im2).rounded_rectangle([W / 2 - half, 215, W / 2 + half, 300], 18, outline=255, width=2)
        apply_layer(F, Im2, BLUE, 0.9 * o, glow=1.0)
    segs = [("const ", PURPLE), ("app", WHITE), (" = ", GRAY), ("new ", PURPLE), ("Appnova", CYAN), ("();", GRAY)]
    full = "".join(s for s, _ in segs)
    n = int(seg(t, 10.5, 11.5) * len(full))
    fs = 40
    total_w = font(F_MONO, fs).getlength(full)
    x = W / 2 - total_w / 2; shown = 0
    for s, col in segs:
        part = s[:max(0, n - shown)]; shown += len(s)
        if part:
            rgb, a = text_img(part, fs, F_MONO, 0, tuple(map(float, col)), None)
            asc = font(F_MONO, fs).getmetrics()[0]
            over(F, rgb, a * o, int(x), int(258 - a.shape[0] / 2))
        x += font(F_MONO, fs).getlength(s) if len(part) == len(s) else font(F_MONO, fs).getlength(part)
        if len(part) < len(s): break
    if q > 0.9 and (int(t * 3.5) % 2 == 0 or n < len(full)):
        Im = Image.new("L", (W, H), 0)
        ImageDraw.Draw(Im).rectangle([x + 4, 238, x + 8, 280], fill=255)
        apply_layer(F, Im, CYAN, o)
    # counter
    p = seg(t, 11.55, 12.95)
    if t >= 11.45:
        val = int(round((eo5(p)) * 1_000_000))
        txt = f"{val:,}" + ("+" if p >= 1 else "")
        hitk = decay(t, 12.95, 6)
        draw_text(F, txt, W / 2, 610, 150, top=WHITE, bot=CYAN, track=2,
                  scale=1 + 0.12 * hitk, opacity=eo3(seg(t, 11.45, 11.7)) * o, glow=0.55 + 0.6 * hitk, glow_r=18)
        if t >= 12.95:
            ring(F, W / 2, 610, 300 + 400 * eo3(seg(t, 12.95, 13.6)), 8, 0.7 * (1 - seg(t, 12.95, 13.6)), CYAN)
    q3 = eo3(seg(t, 11.9, 12.35))
    draw_text(F, "TO MILLIONS OF USERS", W / 2, 740 + 16 * (1 - q3), 44, path=F_SEMI, top=WHITE, track=12,
              opacity=q3 * o, glow=0.3)

LOGO_HIT = 14.3

def sc_logo(F, t):
    a, b = SCENES["logo"]
    # converging light rush before the hit
    if t < LOGO_HIT + 0.05:
        p = seg(t, 13.6, LOGO_HIT)
        Rr = 760 * (1 - eio(p))
        A = AC; D = DC
        m = (0.5 + 0.5 * np.cos(A * 24 + t * 2.0)) ** 14 * np.exp(-((D - Rr) / 110) ** 2) * (0.75 + 0.7 * p)
        add_mask(F, m, CYAN, 1.0)
        add_mask(F, np.exp(-(D / (40 + 160 * p)) ** 2), ICE, 0.9 * p ** 2)
    # hit FX
    if t >= LOGO_HIT:
        dt = t - LOGO_HIT
        ring(F, W / 2, 520, eo3(seg(dt, 0, 1.1)) * 760, 14, 1.0 * (1 - seg(dt, 0, 1.1)), CYAN)
        ring(F, W / 2, 520, eo3(seg(dt, 0.1, 1.4)) * 620, 6, 0.7 * (1 - seg(dt, 0.1, 1.4)), WHITE)
        rays(F, W / 2, 520, 32, t * 0.3, 10, 0.55 * decay(t, LOGO_HIT, 2.2), BLUE, falloff=900)
        # burst
        if dt < 1.6:
            L = lines_layer(); d = ImageDraw.Draw(L)
            for i in range(B_N):
                e = eo3(seg(dt, 0, 1.4)); rr = 60 + B_SP[i] * e
                ca, sa = math.cos(B_ANG[i]), math.sin(B_ANG[i])
                x0, y0 = W / 2 + ca * rr, 520 + sa * rr
                ln = B_LEN[i] * (1 - e * 0.7)
                d.line([(x0, y0), (x0 + ca * ln, y0 + sa * ln)], fill=int(255 * (1 - seg(dt, 0.2, 1.6))), width=2)
            apply_layer(F, L, ICE, 0.9, glow=1.4, glow_r=6)
    # logo itself (shared with CTA scene)
    draw_logo_state(F, t)
    # tagline
    q = eo3(seg(t, 16.75, 17.4))
    o = 1 - seg(t, 18.8, 19.05)
    if q > 0:
        tr = 14 + 22 * (1 - q)
        bx = draw_text(F, "WE BUILD WHAT'S NEXT", W / 2, 925, 36, path=F_SEMI, top=WHITE, track=tr,
                       opacity=q * o, glow=0.3)
        if bx:
            x0, y0, w, h = bx
            L = lines_layer(); d = ImageDraw.Draw(L); ln = 90 * q
            d.line([(x0 - 24 - ln, 925), (x0 - 24, 925)], fill=int(220 * o), width=2)
            d.line([(x0 + w + 24, 925), (x0 + w + 24 + ln, 925)], fill=int(220 * o), width=2)
            apply_layer(F, L, CYAN, 1.0, glow=1.0)

def draw_logo_state(F, t):
    if t < LOGO_HIT - 0.06: return
    # move logo up + shrink for the CTA end card
    mv = eio(seg(t, 19.0, 19.7))
    s = 0.74 * (1 + 0.035 * seg(t, LOGO_HIT, 19.0)) * (1 - mv) + 0.46 * mv
    cy = 560 * (1 - mv) + 300 * mv
    hs = 1 + 0.55 * (1 - eback(seg(t, LOGO_HIT - 0.06, LOGO_HIT + 0.55), 1.5))
    rev_m = eo3(seg(t, LOGO_HIT - 0.06, LOGO_HIT + 0.75))
    rev_w = eio(seg(t, 15.05, 15.85))
    sp = None
    for t0 in (16.15, 21.6):
        if t0 <= t <= t0 + 0.9:
            sp = -300 + eio(seg(t, t0, t0 + 0.9)) * (W + 900)
    pulse = 1 + 0.25 * math.sin(t * 3) + 1.2 * decay(t, LOGO_HIT, 3)
    logo(F, t, W / 2, cy, s, rev_m, rev_w, sp, pulse, hs)

def sc_cta(F, t):
    draw_logo_state(F, t)
    q1 = eo3(seg(t, 19.55, 20.0))
    draw_text(F, "START YOUR PROJECT", W / 2, 615 + 26 * (1 - q1), 74, top=WHITE, bot=ICE, track=3,
              opacity=q1, blur=6 * (1 - q1), glow=0.35)
    q = seg(t, 20.2, 20.6)
    if q > 0:
        s = eback(q)
        bw, bh = 320 * s, 92 * s
        cx, cy = W / 2, 742
        Im = Image.new("L", (W, H), 0)
        ImageDraw.Draw(Im).rounded_rectangle([cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2], int(46 * s), fill=255)
        m = np.asarray(Im, np.float32) / 255.0
        grad = (np.clip((XX - (cx - bw / 2)) / max(bw, 1), 0, 1))[..., None]
        col = BLUE * (1 - grad) + CYAN * grad
        F[:] = F * (1 - m[..., None]) + col * m[..., None]
        g = zoom(gaussian_filter(m[::2, ::2], 14), 2, order=1)[:H, :W]
        add_mask(F, g, BLUE, 0.9 + 0.3 * math.sin(t * 4))
        # shimmer across the button
        sx = cx - bw / 2 - 120 + ((t - 20.6) % 1.6) / 1.6 * (bw + 240)
        sh = np.exp(-(((XX - sx) + (YY - cy) * 0.4) / 30) ** 2) * m
        add_mask(F, sh, WHITE, 0.55 * seg(t, 20.6, 20.8))
        draw_text(F, "TODAY", cx, cy, 50 * s, top=WHITE, track=10 * s)
    q4 = eo3(seg(t, 20.7, 21.2))
    draw_text(F, "WEB  •  ANDROID  •  CUSTOM SOFTWARE", W / 2, 865, 28, path=F_SEMI, top=GRAY,
              track=7, opacity=q4)

# ---------------------------------------------------------------- global FX
def impacts(t):
    fl = sh = ch = zp = 0.0
    for t0, k in IMPACTS:
        if t >= t0:
            dt = t - t0
            fl += k * (0.32 if k < 1.2 else 0.75) * math.exp(-dt * 13)
            sh += k * 13 * math.exp(-dt * 9)
            ch += k * 9 * math.exp(-dt * 11)
            zp += k * 0.05 * math.exp(-dt * 8)
    return fl, sh, ch, zp

def scene_bg_params(t):
    grid = 0.0; part = 1.0; blob = 1.0
    if 3.2 <= t < 7.0: grid = seg(t, 3.2, 3.6)
    if 10.0 <= t < 13.6: grid = 0.6
    if 19.0 <= t: grid = 0.8 * seg(t, 19.2, 20.0)
    if 13.6 <= t < LOGO_HIT: blob = 1 - 0.8 * seg(t, 13.6, 13.9); part = 0.5
    if t < 0.6: part = seg(t, 0.0, 0.6); blob = seg(t, 0, 0.8)
    return grid, part, blob

def render_frame(fi):
    t = fi / FPS
    grid, part, blob = scene_bg_params(t)
    F = background(t, grid, part, blob)
    if t < 3.2: sc_idea(F, t)
    elif t < 7.0: sc_devices(F, t)
    elif t < 10.0: sc_punch(F, t)
    elif t < 13.6: sc_scale(F, t)
    elif t < 19.0: sc_logo(F, t)
    else: sc_cta(F, t)
    fl, sh, ch, zp = impacts(t)
    # bloom
    small = np.clip(F, 0, 4).reshape(H // 4, 4, W // 4, 4, 3).mean((1, 3))
    bp = np.maximum(small - 0.58, 0)
    bl = gaussian_filter(bp, (3, 3, 0)) * 0.5 + gaussian_filter(bp, (14, 14, 0)) * 0.85
    F += zoom(bl, (4, 4, 1), order=1)
    F += fl
    F *= VIGN
    F += GRAIN[fi % len(GRAIN)]
    if t > DURATION - 0.35: F *= 1 - seg(t, DURATION - 0.35, DURATION)
    img = (np.clip(F, 0, 1) * 255 + 0.5).astype(np.uint8)
    # chromatic aberration
    ci = int(round(ch))
    if ci >= 1:
        img[..., 0] = np.roll(img[..., 0], ci, 1); img[..., 2] = np.roll(img[..., 2], -ci, 1)
    # glitch moments
    g = 0.0
    for t0, dur in [(0.6, 0.12), (1.35, 0.08), (3.12, 0.1), (6.95, 0.08), (9.93, 0.08), (LOGO_HIT, 0.1)]:
        if t0 <= t < t0 + dur: g = 1.0
    img = glitch(img, g, fi)
    # camera: slow push + zoom punch + shake
    r = np.random.default_rng(fi * 31 + 5)
    z = 1.0 + 0.025 * ((t * 0.37) % 1.0) * 0 + zp
    for nm, (a_, b_) in SCENES.items():
        if a_ <= t < b_: z += 0.03 * eio(seg(t, a_, b_))
    dx, dy = r.uniform(-1, 1) * sh, r.uniform(-1, 1) * sh
    cw = W / z
    x0 = (W - cw) / 2 + dx; y0 = (H - cw) / 2 + dy
    x0 = min(max(x0, 0), W - cw); y0 = min(max(y0, 0), H - cw)
    im = Image.fromarray(img)
    if z > 1.0005 or abs(dx) > 0.5:
        im = im.transform((W, H), Image.EXTENT, (x0, y0, x0 + cw, y0 + cw), Image.BICUBIC)
    return im.tobytes()

def main():
    os.makedirs(BUILD, exist_ok=True)
    if len(sys.argv) > 2 and sys.argv[1] == "--frames":
        for fi in map(int, sys.argv[2].split(",")):
            Image.frombytes("RGB", (W, H), render_frame(fi)).save(os.path.join(BUILD, f"frame_{fi:04d}.png"))
        return
    out = os.path.join(BUILD, "appnova_video_nosound.mp4")
    ff = subprocess.Popen(["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24",
                           "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-", "-c:v", "libx264", "-preset", "medium", "-tune", "film",
                           "-crf", "20", "-pix_fmt", "yuv420p", "-movflags", "+faststart", out], stdin=subprocess.PIPE)
    with Pool(os.cpu_count()) as pool:
        for i, fr in enumerate(pool.imap(render_frame, range(N_FRAMES), chunksize=4)):
            ff.stdin.write(fr)
            if i % 60 == 0: print(f"frame {i}/{N_FRAMES}", flush=True)
    ff.stdin.close(); ff.wait()
    print("wrote", out)

if __name__ == "__main__":
    main()
