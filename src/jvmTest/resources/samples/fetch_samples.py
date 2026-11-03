"""Fetches a few public MDF 4 files of another logger (CSS Electronics' CANedge) for the reader's PublicSamplesTest.

Usage: python fetch_samples.py [folder]      (default folder: build/samples, from the library's root)

The files come from CSS Electronics' `api-examples` repository on GitHub, at a fixed commit, and are checked against the sizes
and CRC-32s below. That repository states no license for its sample data, so they are fetched for local testing and never kept in
this one; PublicSamplesTest runs only when they are in the folder. If asammdf is installed (`pip install asammdf==8.8.27`), the
first frames of the files with a CAN_DataFrame group are also written as `<name>.frames.json`, as asammdf reads them, and the
test compares the reader's frames with them.
"""

import json
import os
import sys
import urllib.request
import zlib

COMMIT = "f7932bacb2969dec591508d15f89e8a898ca99c6"
BASE = f"https://raw.githubusercontent.com/CSS-Electronics/api-examples/{COMMIT}/"

# name -> (path in the repository, size, CRC-32)
FILES = {
    "a170.mf4": ("examples/data-processing/LOG/17BD1DB7/00000006/00000170.MF4", 79840, 2294495711),
    "b1.mf4": ("examples/data-processing/LOG/2F6913DB/00000004/00000001.MF4", 214832, 3649137072),
    "c2.mf4": ("examples/data-processing/LOG/94C49784/00000005/00000002.MF4", 360062, 1428889579),
    "fcbf1.mf4": ("examples/data-processing/LOG/FCBF0606/00003482/00000001-60B621D9.MF4", 72820, 2403339614),
    "s3.mf4": ("examples/other/s3-upload-log-files/LOG/7512BE4D/00000427/00000001.MF4", 1330953, 4153689598),
}

FRAMES = 300


def fetch(folder):
    os.makedirs(folder, exist_ok=True)
    for name, (path, size, crc) in FILES.items():
        target = os.path.join(folder, name)
        if os.path.exists(target) and os.path.getsize(target) == size:
            continue
        with urllib.request.urlopen(BASE + path) as response:
            data = response.read()
        if len(data) != size or zlib.crc32(data) != crc:
            raise SystemExit(f"{name}: not the file this script knows ({len(data)} bytes, CRC-32 {zlib.crc32(data)})")
        with open(target, "wb") as f:
            f.write(data)
        print("fetched", name, size)


def frames(folder):
    try:
        from asammdf import MDF
    except ImportError:
        print("asammdf is not installed: no frames.json files")
        return
    for name in FILES:
        m = MDF(os.path.join(folder, name))
        if not any(g.channel_group.acq_name == "CAN_DataFrame" for g in m.groups):
            m.close()
            continue
        ident = m.get("CAN_DataFrame.ID")
        data = m.get("CAN_DataFrame.DataBytes").samples
        length = m.get("CAN_DataFrame.DataLength").samples
        bus = m.get("CAN_DataFrame.BusChannel").samples
        extended = m.get("CAN_DataFrame.IDE").samples
        rows = []
        for i in range(min(FRAMES, len(ident.samples))):
            rows.append([float(ident.timestamps[i]), int(bus[i]), int(ident.samples[i]), int(extended[i]), int(length[i]),
                         bytes(int(b) for b in data[i][: int(length[i])]).hex()])
        with open(os.path.join(folder, name.replace(".mf4", ".frames.json")), "w") as f:
            json.dump({"total": len(ident.samples), "frames": rows}, f)
        m.close()
        print("frames of", name, len(rows))


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join("build", "samples")
    fetch(out)
    frames(out)
