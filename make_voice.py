"""Appnova ad — voice-over with edge-tts (deep American trailer voice) + final mix.

Requirements (run once):
    pip install edge-tts
    ffmpeg must be installed and on PATH

Run:
    python make_voice.py
Output:
    build/appnova_ad_final.mp4   (video + music/SFX + voice, music ducks under the voice)

Options:
    python make_voice.py --voice en-US-GuyNeural      try another voice
    python make_voice.py --pitch -18Hz --rate -10%    make it deeper / slower
    python make_voice.py --deepen 0.92                extra pitch drop in ffmpeg (1.0 = off)
"""
import argparse, asyncio, json, os, subprocess, sys

from timeline import VO, DURATION

ROOT = os.path.dirname(os.path.abspath(__file__))
BUILD = os.path.join(ROOT, "build")
VOICE_DIR = os.path.join(BUILD, "voice")
VIDEO = os.path.join(BUILD, "appnova_video_nosound.mp4")
MUSIC = os.path.join(BUILD, "music_sfx.wav")
FINAL = os.path.join(BUILD, "appnova_ad_final.mp4")

# Deep, authoritative US male voices available in edge-tts:
#   en-US-ChristopherNeural  (deepest, "authority")  <- default
#   en-US-GuyNeural          (energetic, "passion")
#   en-US-EricNeural         (calm, rational)
DEFAULT_VOICE = "en-US-ChristopherNeural"


async def synth(voice, rate, pitch):
    import edge_tts
    os.makedirs(VOICE_DIR, exist_ok=True)
    for line in VO:
        out = os.path.join(VOICE_DIR, f"vo_{line['id']}.mp3")
        comm = edge_tts.Communicate(line["text"], voice, rate=rate, pitch=pitch)
        await comm.save(out)
        print(f"  [{line['id']}] {line['text']}")


def duration(path):
    r = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "json", path],
                       capture_output=True, text=True, check=True)
    return float(json.loads(r.stdout)["format"]["duration"])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--voice", default=DEFAULT_VOICE)
    ap.add_argument("--rate", default="-8%")
    ap.add_argument("--pitch", default="-14Hz")
    ap.add_argument("--deepen", type=float, default=0.94,
                    help="extra pitch-down factor applied in ffmpeg (keeps timing). 1.0 = off")
    ap.add_argument("--skip-tts", action="store_true", help="reuse the clips already in build/voice")
    a = ap.parse_args()

    for f in (VIDEO, MUSIC):
        if not os.path.exists(f):
            sys.exit(f"Missing {f}. Run render.py and sound_design.py first.")

    if not a.skip_tts:
        print(f"Generating voice with {a.voice} (rate {a.rate}, pitch {a.pitch}) ...")
        asyncio.run(synth(a.voice, a.rate, a.pitch))

    # timing check: warn if a line runs into the next one
    clips = []
    for i, line in enumerate(VO):
        p = os.path.join(VOICE_DIR, f"vo_{line['id']}.mp3")
        d = duration(p)
        nxt = VO[i + 1]["start"] if i + 1 < len(VO) else DURATION
        flag = "  <-- overlaps next line, use a faster --rate" if line["start"] + d > nxt + 0.05 else ""
        print(f"  {line['id']}: {line['start']:5.2f}s + {d:4.2f}s{flag}")
        clips.append((p, line["start"]))

    # ffmpeg graph: each clip -> deepen + warm EQ + grit + compression -> placed at its time
    inputs = ["-i", VIDEO, "-i", MUSIC]
    chains, labels = [], []
    sr = 44100
    dp = a.deepen
    for i, (p, start) in enumerate(clips):
        inputs += ["-i", p]
        idx = i + 2
        ms = int(start * 1000)
        deep = (f"asetrate={int(sr * dp)},aresample={sr},atempo={1 / dp:.4f}," if abs(dp - 1) > 1e-3 else "")
        chains.append(
            f"[{idx}:a]aresample={sr},aformat=channel_layouts=stereo,{deep}"
            "highpass=f=70,"
            "equalizer=f=110:t=q:w=1.1:g=5,"        # chest / weight
            "equalizer=f=320:t=q:w=1.4:g=-3,"       # remove boxiness
            "equalizer=f=2800:t=q:w=1.2:g=3,"       # presence / grit
            "asoftclip=type=tanh:threshold=0.85,"   # a little harmonic roughness
            "acompressor=threshold=-20dB:ratio=5:attack=4:release=90:makeup=5,"
            f"adelay={ms}|{ms}[v{i}]")
        labels.append(f"[v{i}]")
    graph = ";".join(chains)
    graph += f";{''.join(labels)}amix=inputs={len(labels)}:normalize=0,aecho=0.8:0.35:45|90:0.18|0.08[vo]"
    graph += f";[vo]apad=whole_dur={DURATION},asplit=2[vo1][vosc]"
    graph += ";[1:a][vosc]sidechaincompress=threshold=0.04:ratio=7:attack=15:release=350[mus]"
    graph += ";[mus][vo1]amix=inputs=2:normalize=0:weights=0.85 1.25,alimiter=limit=0.93[out]"

    cmd = ["ffmpeg", "-y", "-loglevel", "error", *inputs, "-filter_complex", graph,
           "-map", "0:v", "-map", "[out]", "-c:v", "copy", "-c:a", "aac", "-b:a", "256k",
           "-t", str(DURATION), "-movflags", "+faststart", FINAL]
    print("Mixing ...")
    subprocess.run(cmd, check=True)
    print("Done ->", FINAL)


if __name__ == "__main__":
    main()
