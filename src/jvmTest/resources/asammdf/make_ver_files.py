import json
import sys

import numpy as np
from asammdf import MDF, Signal

out = sys.argv[1]
t = np.arange(0, 10, 0.1)
expected = {}
for ver in ("4.00", "4.10", "4.20"):
    mdf = MDF(version=ver)
    sigs = [
        Signal(samples=np.sin(t), timestamps=t, name="sine", unit="V", comment="a sine"),
        Signal(samples=(t * 10).astype(np.int16), timestamps=t, name="count", unit="n"),
        Signal(samples=(t % 3).astype(np.uint8), timestamps=t, name="enum",
               conversion={"val_0": 0, "text_0": "off", "val_1": 1, "text_1": "on", "val_2": 2, "text_2": "auto", "default": "?"}),
        Signal(samples=(t * 2).astype(np.float32), timestamps=t, name="lin", unit="x", conversion={"a": 2.0, "b": 1.0}),
        Signal(samples=(t * 3).astype(np.float64), timestamps=t, name="rat", conversion={"P1": 0, "P2": 1, "P3": 0, "P4": 0, "P5": 0, "P6": 2}),
        Signal(samples=(t % 5).astype(np.uint16), timestamps=t, name="rtab",
               conversion={"lower_0": 0, "upper_0": 1, "phys_0": 10.0, "lower_1": 2, "upper_1": 3, "phys_1": 20.0, "default": -1.0}),
        Signal(samples=(t % 4).astype(np.uint8), timestamps=t, name="tab",
               conversion={"raw_0": 0, "phys_0": 0.5, "raw_1": 2, "phys_1": 1.5, "raw_2": 3, "phys_2": 10.0}),
    ]
    mdf.append(sigs)
    # a second group with other timestamps
    t2 = np.arange(0, 5, 0.25)
    mdf.append([Signal(samples=np.cos(t2), timestamps=t2, name="cosine", unit="V")])
    path = f"{out}/ver_{ver}.mf4"
    mdf.save(path, overwrite=True)
    mdf.close()

    m = MDF(path)
    info = {"groups": []}
    for gi, g in enumerate(m.groups):
        entry = {"name": g.channel_group.acq_name, "channels": []}
        for ci, ch in enumerate(g.channels):
            sig = m.get(group=gi, index=ci)
            raw = m.get(group=gi, index=ci, raw=True)
            entry["channels"].append({
                "name": ch.name,
                "samples": [float(x) if not isinstance(x, bytes) else x.decode() for x in sig.samples.tolist()],
                "raw": [float(x) for x in raw.samples.tolist()] if raw.samples.dtype.kind in "fiu" else None,
                "timestamps": [float(x) for x in sig.timestamps.tolist()],
                "unit": ch.unit if hasattr(ch, "unit") else None,
            })
        info["groups"].append(entry)
    expected[ver] = info
    m.close()
json.dump(expected, open(f"{out}/expected.json", "w"))
print("ok")
