#!/usr/bin/env python3
"""Episode stills for the series screen, built into the APK.

The frames come from the episodes' own streams (the player's playlist), so they are fetched on the
build machine rather than kept in the repository: app/src/main/assets/stills/ is git-ignored.
Needs ffmpeg on PATH. Episodes without a still in the APK are made on the TV at runtime instead.

    tools/fetch_stills.py candidates OUT_DIR   contact sheets of 6 frames per episode, to choose from
    tools/fetch_stills.py fetch                the chosen frames (tools/stills.json) into the assets

tools/stills.json maps "season:episode" to the second of the chosen frame.
"""
import concurrent.futures
import json
import os
import re
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
import urllib.request

PLAYLIST = "https://api.namy.ws/embed/movie/354"
AGENT = "Mozilla/5.0 (Linux; Android 11; MiTV-MOOQ1) AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CHOICES = os.path.join(ROOT, "tools", "stills.json")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets", "stills")
# Candidates come from the first half only, so no still gives away how an episode ends.
CANDIDATES = [0.12, 0.20, 0.28, 0.36, 0.44, 0.52]
WIDTH = 1280


def get(url):
    request = urllib.request.Request(url, headers={"User-Agent": AGENT})
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read()


def episodes():
    """{(season, episode): (duration, hls url)} from a fresh copy of the playlist."""
    html = get(PLAYLIST).decode("utf-8", "replace")
    start = html.index("[", html.index("seasons:", html.index("playlist:")))
    seasons, _ = json.JSONDecoder().raw_decode(html[start:])
    return {
        (season["season"], int(episode["episode"])): (episode.get("duration", 0), episode["hls"])
        for season in seasons
        for episode in season["episodes"]
        if str(episode.get("episode", "")).isdigit() and episode.get("hls")
    }


_playlist_lock = threading.Lock()
_playlist = {"at": 0.0, "episodes": {}}


def hls(season, number, fresh=False):
    """The episode's stream link. Links expire within minutes, so the playlist is read again after a
    minute, or at once when a link has failed."""
    with _playlist_lock:
        if fresh or time.time() - _playlist["at"] > 60:
            _playlist["episodes"] = episodes()
            _playlist["at"] = time.time()
        return _playlist["episodes"][(season, number)][1]


def segment_at(master, seconds):
    """(segment url, offset into it) of the highest variant up to 1080p at the given second."""
    lines = get(master).decode().splitlines()
    variants = []
    for i, line in enumerate(lines):
        match = re.search(r"RESOLUTION=\d+x(\d+)", line)
        if line.startswith("#EXT-X-STREAM-INF") and match and i + 1 < len(lines):
            variants.append((int(match.group(1)), urllib.parse.urljoin(master, lines[i + 1].strip())))
    height, variant = max((v for v in variants if v[0] <= 1080), default=max(variants))
    lines = get(variant).decode().splitlines()
    time = 0.0
    for i, line in enumerate(lines):
        if line.startswith("#EXTINF:"):
            length = float(line[8:].split(",")[0])
            if time + length >= seconds:
                return urllib.parse.urljoin(variant, lines[i + 1].strip()), seconds - time
            time += length
    raise ValueError(f"{seconds}s is past the end")


def frame(season, number, seconds, out):
    """Writes the frame at the given second, WIDTH pixels wide. The series is framed 2:1 inside the
    16:9 stream, so cropping to 2:1 removes the letterbox bars exactly."""
    for attempt in range(3):
        try:
            url, offset = segment_at(hls(season, number, fresh=attempt > 0), seconds)
            video_data = get(url)
            break
        except Exception:
            if attempt == 2:
                raise
    with tempfile.NamedTemporaryFile(suffix=".ts") as video:
        video.write(video_data)
        video.flush()
        # Full-range output: the JPEG encoder refuses the limited-range video some segments carry.
        filters = f"crop=iw:min(ih\\,iw/2),scale={WIDTH}:-2:out_range=full,format=yuvj420p"
        subprocess.run(
            # Seeking after decoding: the segment is only a few seconds long, and seeking the input
            # misses frames in some of them.
            ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", video.name, "-ss", f"{offset:.2f}",
             "-frames:v", "1", "-vf", filters, "-q:v", "3", out],
            check=True,
        )


def candidates(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    playlist = episodes()
    jobs = []
    for (season, number), (duration, _) in sorted(playlist.items()):
        for k, share in enumerate(CANDIDATES):
            seconds = int(duration * share)
            jobs.append((season, number, seconds, os.path.join(out_dir, f"s{season}e{number:02d}_{k}_{seconds}.jpg")))
    with concurrent.futures.ThreadPoolExecutor(6) as pool:
        for job, error in zip(jobs, pool.map(lambda j: _try(frame, *j), jobs)):
            if error:
                print(f"{os.path.basename(job[3])}: {error}", file=sys.stderr)
    for season in sorted({s for s, _ in playlist}):
        subprocess.run(
            ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-pattern_type", "glob",
             "-i", os.path.join(out_dir, f"s{season}e*_*.jpg"),
             "-vf", f"scale=320:160,tile={len(CANDIDATES)}x"
                    f"{sum(1 for s, _ in playlist if s == season)}",
             "-frames:v", "1", os.path.join(out_dir, f"sheet_s{season}.jpg")],
            check=True,
        )
    print(f"sheets in {out_dir}: one row per episode, columns at {CANDIDATES} of its length")


def fetch():
    with open(CHOICES) as f:
        choices = json.load(f)
    os.makedirs(ASSETS, exist_ok=True)
    playlist = episodes()
    jobs = []
    for key, seconds in sorted(choices.items()):
        season, number = map(int, key.split(":"))
        if (season, number) in playlist:
            jobs.append((season, number, seconds, os.path.join(ASSETS, f"s{season}e{number}.jpg")))
        else:
            print(f"{key}: not in the playlist", file=sys.stderr)
    with concurrent.futures.ThreadPoolExecutor(6) as pool:
        errors = [(job, e) for job, e in zip(jobs, pool.map(lambda j: _try(frame, *j), jobs)) if e]
    for job, error in errors:
        print(f"{os.path.basename(job[3])}: {error}", file=sys.stderr)
    print(f"{len(jobs) - len(errors)} stills in {ASSETS}")
    return 1 if errors else 0


def _try(function, *args):
    try:
        function(*args)
        return None
    except Exception as e:  # reported per episode, the rest carry on
        return str(e)


if __name__ == "__main__":
    if sys.argv[1:2] == ["candidates"] and len(sys.argv) == 3:
        candidates(sys.argv[2])
    elif sys.argv[1:] == ["fetch"]:
        sys.exit(fetch())
    else:
        print(__doc__.strip())
        sys.exit(2)
