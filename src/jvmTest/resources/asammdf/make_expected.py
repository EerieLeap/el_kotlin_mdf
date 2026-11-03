import json
import sys

import numpy as np
from asammdf import MDF

out = sys.argv[1]
result = {}
for name in ("ver_4.10", "zipped"):
    m = MDF(f"{out}/{name}.mf4")
    groups = []
    for gi, g in enumerate(m.groups):
        entry = {"channels": []}
        for ci, ch in enumerate(g.channels):
            sig = m.get(group=gi, index=ci)
            raw = m.get(group=gi, index=ci, raw=True)
            is_text = sig.samples.dtype.kind in "SUO"
            entry["channels"].append({
                "name": ch.name,
                "unit": getattr(ch, "unit", "") or "",
                "converted": [x.decode() if isinstance(x, bytes) else float(x) for x in sig.samples.tolist()],
                "stored": [float(x) for x in raw.samples.tolist()] if raw.samples.dtype.kind in "fiu" else None,
                "times": [float(x) for x in sig.timestamps.tolist()],
            })
        groups.append(entry)
    result[name] = {"version": m.version, "groups": groups}
    m.close()
json.dump(result, open(f"{out}/asammdf_expected.json", "w"), separators=(",", ":"))
print("ok")
