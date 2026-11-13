"""Muarakas sound set, synthesised physically (modal synthesis), rendered to WAV.

Metal objects ring at several inharmonic frequencies (modes) at once, each decaying at its own
rate; what makes a coin sound like a coin is that set of modes, a very short, sharp contact
transient, and random timing between collisions. A little room reverb glues it together.
"""
import numpy as np, wave, sys

SR = 44100
rng = np.random.default_rng(7)


def buf(sec):
    return np.zeros(int(SR * sec))


def add(b, x, t0):
    s = int(t0 * SR)
    e = min(len(b), s + len(x))
    if e > s:
        b[s:e] += x[: e - s]


def modal(freqs, amps, t60s, dur, attack=0.0006, detune=0.0):
    t = np.arange(int(dur * SR)) / SR
    out = np.zeros_like(t)
    for f, a, t60 in zip(freqs, amps, t60s):
        if f * (1 + detune) >= SR / 2 - 500:
            continue
        ph = rng.uniform(0, 2 * np.pi)
        f2 = f * (1 + detune * rng.uniform(-1, 1))
        # two slightly detuned copies give the slow shimmer real coins have (split degenerate modes)
        beat = 1 + rng.uniform(0.0006, 0.002)
        env = np.exp(-6.91 * t / t60)
        out += a * env * (np.sin(2 * np.pi * f2 * t + ph) + 0.6 * np.sin(2 * np.pi * f2 * beat * t + ph * 1.3)) / 1.6
    att = np.clip(t / attack, 0, 1)
    return out * att


def onepole_lp(x, fc):
    a = np.exp(-2 * np.pi * fc / SR)
    y = np.zeros_like(x)
    acc = 0.0
    for i, v in enumerate(x):
        acc = (1 - a) * v + a * acc
        y[i] = acc
    return y


def click(dur=0.0025, bright=6000, vol=1.0):
    n = int(dur * SR)
    x = rng.uniform(-1, 1, n) * np.exp(-np.arange(n) / (n / 4))
    hp = x - onepole_lp(x, bright)
    return hp * vol


def room(x, mix=0.14, t=0.07):
    n = int(SR * t * 4)
    ir = rng.normal(0, 1, n) * np.exp(-np.arange(n) / (SR * t))
    ir = onepole_lp(ir, 5000)
    ir /= np.sqrt(np.sum(ir ** 2))
    wet = np.convolve(x, ir)[: len(x)]
    return x * (1 - mix) + wet * mix


# Coin models: base mode and ratios of a thin disc with a free edge.
RATIOS = [1.0, 1.73, 2.33, 3.12, 3.91]


def coin(f1, size, strength, dur=0.8):
    amps = np.array([1.0, 0.8, 0.55, 0.35, 0.22]) * rng.uniform(0.35, 1.0, 5)
    t60 = np.array([0.55, 0.38, 0.28, 0.18, 0.12]) * size
    x = modal([f1 * r for r in RATIOS], amps, t60, dur, detune=0.012)
    x[: int(0.003 * SR)] += click(0.003, 5000, 0.9)[: int(0.003 * SR)]
    return x * strength


COINS = [(3150, 1.25), (3720, 1.1), (4380, 1.0), (5240, 0.9), (6050, 0.8)]


def normalize(b, peak):
    m = np.max(np.abs(b))
    return b / m * peak if m > 0 else b


def koin():
    """A small handful of coins dropped into a palm / onto a pile."""
    b = buf(0.95)
    t = 0.0
    events = []
    for i in range(13):
        t += rng.uniform(0.012, 0.05) if i < 8 else rng.uniform(0.04, 0.09)
        events.append(t)
    for i, t in enumerate(events):
        f1, size = COINS[rng.integers(len(COINS))]
        strength = (1.0 if i < 3 else 0.85 ** (i - 2)) * rng.uniform(0.55, 1.0)
        add(b, coin(f1 * rng.uniform(0.985, 1.015), size, strength), t - 0.01)
    # coins sliding against each other: a soft metallic hiss under the clinks
    n = int(0.35 * SR)
    hiss = rng.uniform(-1, 1, n)
    hiss = hiss - onepole_lp(hiss, 7000)
    hiss *= np.exp(-np.arange(n) / (0.12 * SR)) * 0.05
    add(b, hiss, 0.02)
    return room(b, 0.16)


def celengan():
    """One coin slid into a ceramic piggy bank: slot scrape, drop onto coins inside, small bounce."""
    b = buf(1.0)
    n = int(0.07 * SR)
    scr = rng.uniform(-1, 1, n)
    scr = onepole_lp(scr - onepole_lp(scr, 2500), 6000) * np.hanning(n) * 0.22
    add(b, scr, 0.0)
    body = lambda s: modal([720, 1130, 1690], [1.0, 0.55, 0.3], [0.12, 0.09, 0.06], 0.4) * s  # hollow ceramic
    for t, s in [(0.17, 1.0), (0.27, 0.42), (0.33, 0.2)]:
        f1, size = COINS[2]
        add(b, coin(f1 * rng.uniform(0.99, 1.01), 0.75, s), t)
        add(b, body(0.55 * s), t)
        add(b, coin(COINS[0][0], 0.6, 0.3 * s), t + 0.004)  # touching the coins already inside
    return room(b, 0.22, 0.05)


def kasir():
    """Cash register: a bright bell 'ting', then the drawer's coins: cha-ching."""
    b = buf(1.1)
    bell = modal([2093 * r for r in [1.0, 2.0, 2.42, 2.98, 4.17]], [1.0, 0.45, 0.35, 0.2, 0.12], [0.9, 0.6, 0.45, 0.3, 0.2], 1.1, attack=0.001)
    add(b, bell * 0.55, 0.0)
    add(b, click(0.006, 1800, 0.35), 0.17)  # drawer latch
    for i, t in enumerate([0.19, 0.215, 0.24, 0.29, 0.34]):
        f1, size = COINS[rng.integers(len(COINS))]
        add(b, coin(f1, size * 0.8, 0.7 * 0.8 ** i), t)
    return room(b, 0.15)


def lonceng():
    """Two soft bells, like the Muarakas chime but rounder."""
    b = buf(1.2)
    for f, t, a in [(1318.5, 0.0, 1.0), (1975.5, 0.11, 0.85)]:
        add(b, modal([f * r for r in [1.0, 2.76, 5.40]], [1.0, 0.22, 0.06], [1.0, 0.45, 0.2], 1.1, attack=0.004) * a, t)
    return room(b, 0.2, 0.09)


def klik():
    """A small, dry tap: confirmation without any jingle."""
    b = buf(0.18)
    add(b, modal([2650, 4210], [1.0, 0.35], [0.05, 0.03], 0.15, attack=0.0004), 0.0)
    add(b, click(0.002, 4000, 0.5), 0.0)
    return b


def muara():
    """Muarakas signature (catat cepat opens): a water drop, then two soft bells."""
    b = buf(0.9)
    t = np.arange(int(0.11 * SR)) / SR
    f = 900 * (1500 / 900) ** (t / 0.11)
    drop = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 0.03) * np.clip(t / 0.002, 0, 1)
    add(b, drop * 0.9, 0.0)
    for fr, tt, a in [(1318.5, 0.07, 0.75), (1975.5, 0.16, 0.65)]:
        add(b, modal([fr * r for r in [1.0, 2.0, 3.01]], [1.0, 0.25, 0.06], [0.55, 0.3, 0.15], 0.75, attack=0.003) * a, tt)
    return room(b, 0.18, 0.08)


def gelembung():
    """A soft bubble pop (alternative opening sound)."""
    b = buf(0.3)
    t = np.arange(int(0.09 * SR)) / SR
    f = 320 * (980 / 320) ** (t / 0.09)
    x = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 0.025) * np.clip(t / 0.002, 0, 1)
    add(b, x, 0.0)
    return room(b, 0.12, 0.04)


SOUNDS = {
    "save-koin": (koin, 0.42),
    "save-celengan": (celengan, 0.42),
    "save-kasir": (kasir, 0.36),
    "save-lonceng": (lonceng, 0.3),
    "save-klik": (klik, 0.34),
    "open-muara": (muara, 0.3),
    "open-gelembung": (gelembung, 0.32),
    "open-klik": (klik, 0.3),
}

if __name__ == "__main__":
    out = sys.argv[1]
    for name, (fn, peak) in SOUNDS.items():
        x = normalize(fn(), peak)
        # trim trailing silence below -60 dB, keep a short fade
        thr = peak * 10 ** (-60 / 20)
        idx = np.where(np.abs(x) > thr)[0]
        x = x[: idx[-1] + int(0.02 * SR)] if len(idx) else x
        fade = int(0.015 * SR)
        x[-fade:] *= np.linspace(1, 0, fade)
        d = (x * 32767).astype(np.int16)
        with wave.open(f"{out}/{name}.wav", "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(SR)
            w.writeframes(d.tobytes())
        print(name, round(len(x) / SR, 2), "s")
