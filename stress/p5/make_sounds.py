#!/usr/bin/env python3
"""Synthesizes the app's three sound cues into android/app/src/main/res/raw (no samples, no licences).
  received: a bright two-note bell "zing" rising a fourth (B5 -> E6)
  sent:     a quick upward glide ending on a short high blip (G6): money leaving
  prepared: a soft major arpeggio (C6 E6 G6): ready, settled
"""
import math, os, struct, wave

RATE = 44100
OUT = os.path.join(os.path.dirname(__file__), '../../android/app/src/main/res/raw')


def bell(f, t, tau, partials=((1, 1.0), (2.76, 0.28), (5.4, 0.08))):
    if t < 0:
        return 0.0
    attack = min(1.0, t / 0.003)
    return attack * sum(a * math.sin(2 * math.pi * f * m * t) * math.exp(-t * m / tau) for m, a in partials)


def received(t):
    return bell(987.77, t, 0.30) + 0.9 * bell(1318.51, t - 0.085, 0.38)


def sent(t):
    g = 0.0
    if t < 0.16:  # glide 520 -> 1500 Hz, phase-integrated
        f0, f1, d = 520.0, 1500.0, 0.16
        phase = 2 * math.pi * (f0 * t + (f1 - f0) * t * t / (2 * d))
        g = 0.45 * math.sin(phase) * math.sin(math.pi * t / d)
    return g + 0.8 * bell(1567.98, t - 0.15, 0.16, ((1, 1.0), (3.0, 0.12)))


def prepared(t):
    marimba = ((1, 1.0), (4.0, 0.1))
    return sum(bell(f, t - 0.09 * i, 0.22, marimba) for i, f in enumerate((1046.5, 1318.51, 1567.98)))


def write(name, fn, seconds):
    n = int(RATE * seconds)
    xs = [fn(i / RATE) for i in range(n)]
    peak = max(abs(x) for x in xs) or 1.0
    gain = 0.7 / peak  # about -3 dBFS
    fade = int(RATE * 0.02)
    with wave.open(os.path.join(OUT, name), 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes(b''.join(
            struct.pack('<h', int(32767 * gain * x * (min(1.0, (n - i) / fade)))) for i, x in enumerate(xs)))
    print(name, n / RATE, 's')


write('cue_received.wav', received, 0.9)
write('cue_sent.wav', sent, 0.55)
write('cue_prepared.wav', prepared, 0.9)
