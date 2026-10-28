"""Exports the library for the gate C listening check (design `songLicense`): per song a .mid (track 1 right hand,
track 2 left hand) and a 30 s preview .wav, plus songs.md with sources. Input: app/build/songs.json (written by
SongExportTest). The preview is a plain additive piano-like tone, only for hearing wrong notes, not the app's samples.

    ./gradlew.bat testDebugUnitTest && python tools/export_songs.py <out dir>
"""
import json
import math
import os
import struct
import sys
import wave

import numpy as np

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
TPQ = 480


def vlq(n):
    out = [n & 0x7F]
    n >>= 7
    while n:
        out.append((n & 0x7F) | 0x80)
        n >>= 7
    return bytes(reversed(out))


def track(events):
    events.sort(key=lambda e: (e[0], e[1]))
    data = b""
    last = 0
    for t, kind, payload in events:
        data += vlq(t - last) + payload
        last = t
    data += b"\x00\xff\x2f\x00"
    return b"MTrk" + struct.pack(">I", len(data)) + data


def write_mid(song, path):
    tempo = int(60_000_000 / song["bpm"])
    meta = [(0, 0, b"\xff\x51\x03" + tempo.to_bytes(3, "big")), (0, 0, b"\xff\x58\x04" + bytes([song["beatsPerBar"], 2, 24, 8]))]
    tracks = [track(meta)]
    for hand, ch in (("R", 0), ("L", 1)):
        ev = []
        for n in song["notes"]:
            if n["hand"] != hand:
                continue
            on = int(round(n["start"] * TPQ))
            off = int(round((n["start"] + n["length"]) * TPQ))
            ev.append((on, 1, bytes([0x90 | ch, n["midi"], 80])))
            ev.append((off, 0, bytes([0x80 | ch, n["midi"], 0])))
        if ev:
            tracks.append(track(ev))
    with open(path, "wb") as f:
        f.write(b"MThd" + struct.pack(">IHHH", 6, 1, len(tracks), TPQ) + b"".join(tracks))


def write_wav(song, path, seconds=30.0, rate=22050):
    spb = 60.0 / song["bpm"]
    buf = np.zeros(int(rate * seconds) + rate * 2)
    for n in song["notes"]:
        t0 = n["start"] * spb
        if t0 >= seconds:
            continue
        dur = max(0.15, n["length"] * spb) + 0.25
        f = 440.0 * 2 ** ((n["midi"] - 69) / 12)
        t = np.arange(int(dur * rate)) / rate
        env = np.exp(-t * 3.0) * np.minimum(1, t * 200)
        tone = sum(a * np.sin(2 * math.pi * f * k * t) for k, a in ((1, 1.0), (2, 0.4), (3, 0.2), (4, 0.1)))
        i = int(t0 * rate)
        buf[i:i + len(t)] += 0.18 * env * tone
    buf = buf[: int(rate * seconds)]
    buf = np.clip(buf, -1, 1)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes((buf * 32000).astype("<i2").tobytes())


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, ".scratch", "qa")
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(ROOT, "app", "build", "songs.json"), encoding="utf-8") as f:
        songs = json.load(f)
    lines = ["# Piano Fall songs (gate C listening check)", "", "| # | id | 曲名 | level | bpm | notes | source | 左手自编 | 简化编配 |", "|---|---|---|---|---|---|---|---|---|"]
    for i, s in enumerate(songs, 1):
        base = f"{i:02d}-{s['id']}"
        write_mid(s, os.path.join(out, base + ".mid"))
        write_wav(s, os.path.join(out, base + "-30s.wav"))
        lines.append(f"| {i} | {s['id']} | {s['zh']} / {s['en']} | {s['level']} | {s['bpm']:.0f} | {len(s['notes'])} | {s['source']} | {'是' if s['leftByUs'] else ''} | {'是' if s['arrangedByUs'] else ''} |")
    lines += ["", "试听 wav 是加法合成的简单音色，只用来听错音；应用内是 Salamander 钢琴采样。"]
    with open(os.path.join(out, "songs.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print(f"{len(songs)} songs -> {out}")


if __name__ == "__main__":
    main()
