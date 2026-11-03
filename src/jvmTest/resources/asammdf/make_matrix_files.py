"""Writes the matrix of files asammdf 8.8.27 makes, with its own reading of each, for the reader's tests (AsammdfMatrixTest).

Usage (in a venv with `pip install asammdf==8.8.27`): python make_matrix_files.py <this folder>

The files are small. Data blocks are cut to 1 KiB (write_fragment_size) so that asammdf writes lists of them (DL, under an HL
where zipped); versions 4.00, 4.10 and 4.20, plain, deflated and transposed-and-deflated data; sorted data groups only (asammdf
writes no other, and no big-endian value: it converts a big-endian array to little-endian, so those are the test writer's). Each case is a file `<case>.mf4` and what asammdf reads from it in `expected_<case>.json`, unless it can't read
its own file: asammdf 8.8.27 writes MDF 4.2's column storage only after three attributes of its Signal are stubbed (its writer
asks for `axes`, `conversions` and `units`, which Signal lacks), and then can't read it (a typo in its LD block reader), so the
column storage files are checked against the signals they were made from.
"""

import json
import os
import sys

import numpy as np
from asammdf import MDF, Signal, set_global_option
from asammdf.blocks import v4_blocks as v4b

out = sys.argv[1]
set_global_option("write_fragment_size", 1024)

T = np.round(np.arange(0, 6, 0.1), 6)
T2 = np.round(np.arange(0, 3, 0.25), 6)
N = len(T)


def jsonable(x):
    if isinstance(x, bytes):
        return x.decode("utf-8")
    if isinstance(x, (np.floating, float)):
        return float(x)
    if isinstance(x, (np.integer, int)):
        return int(x)
    if isinstance(x, (np.bool_, bool)):
        return bool(x)
    return x


def channel_entry(m, gi, ci):
    ch = m.groups[gi].channels[ci]
    # All the samples, the invalid ones among them (asammdf leaves them out unless it is told to ignore the invalidation bits).
    sig = m.get(group=gi, index=ci, ignore_invalidation_bits=True)
    raw = m.get(group=gi, index=ci, raw=True, ignore_invalidation_bits=True)
    kind = sig.samples.dtype.kind
    entry = {
        "name": ch.name,
        "unit": getattr(ch, "unit", "") or "",
        "times": [float(x) for x in sig.timestamps.tolist()],
        "converted": [jsonable(x) for x in sig.samples.tolist()] if kind in "fiuSU" else None,
        "stored": [float(x) for x in raw.samples.tolist()] if raw.samples.dtype.kind in "fiu" else None,
        "invalid": [bool(x) for x in sig.invalidation_bits.tolist()] if sig.invalidation_bits is not None else None,
    }
    if kind == "V":
        # An array (structured samples): one list for each element, as the reader makes channels of them.
        entry["converted"] = None
        entry["array"] = {name: np.asarray(sig.samples[name]).reshape(len(sig.timestamps), -1).astype(float).tolist() for name in sig.samples.dtype.names}
    return entry


def describe(path, extra=None):
    m = MDF(path)
    groups = []
    for gi, g in enumerate(m.groups):
        groups.append({
            "name": g.channel_group.acq_name,
            # The elements of an array that asammdf makes channels of ("arr[1]") are left out: it names those of a map wrongly.
            "channels": [channel_entry(m, gi, ci) for ci, c in enumerate(g.channels) if "[" not in c.name],
        })
    info = {"version": m.version, "groups": groups}
    if extra:
        info.update(extra)
    m.close()
    return info


def write_json(case, info):
    with open(f"{out}/expected_{case}.json", "w") as f:
        json.dump(info, f, separators=(",", ":"))


def common_signals():
    return [
        [
            Signal(samples=np.sin(T), timestamps=T, name="sine", unit="V", comment="a sine"),
            Signal(samples=(T * 10).astype(np.int16), timestamps=T, name="count", unit="n"),
            Signal(samples=(T % 3).astype(np.uint8), timestamps=T, name="enum",
                   conversion={"val_0": 0, "text_0": "off", "val_1": 1, "text_1": "on", "val_2": 2, "text_2": "auto", "default_addr": "?"}),
            Signal(samples=(T * 2).astype(np.float32), timestamps=T, name="lin", unit="x", conversion={"a": 2.0, "b": 1.0}),
        ],
        [Signal(samples=np.cos(T2), timestamps=T2, name="cosine", unit="V")],
    ]


# Lists of data blocks, plain and zipped, in every version.
lists_expected = None
for version in ("4.00", "4.10", "4.20"):
    for compression, label in ((0, "none"), (1, "deflate"), (2, "transposed")):
        if version == "4.00" and compression:
            continue
        mdf = MDF(version=version)
        for signals in common_signals():
            mdf.append(signals)
        case = f"lists_{version}_{label}"
        mdf.save(f"{out}/{case}.mf4", overwrite=True, compression=compression)
        mdf.close()
        if lists_expected is None:
            lists_expected = describe(f"{out}/{case}.mf4")

write_json("lists", lists_expected)

# More kinds of conversion.
mdf = MDF(version="4.10")
mdf.append([
    Signal(samples=(T * 3).astype(np.float64), timestamps=T, name="alg", conversion={"formula": "(X + 3) * 2 / 5"}),
    Signal(samples=(T % 4).astype(np.uint8), timestamps=T, name="interp",
           conversion={"raw_0": 0, "phys_0": 0.5, "raw_1": 2, "phys_1": 1.5, "raw_2": 3, "phys_2": 10.0, "interpolation": True}),
    Signal(samples=(T * 7).astype(np.uint16) % 12, timestamps=T, name="ranges",
           conversion={"lower_0": 0, "upper_0": 3, "text_0": "low", "lower_1": 4, "upper_1": 7, "text_1": "mid", "default_addr": "high"}),
    Signal(samples=(T % 5).astype(np.uint8), timestamps=T, name="states",
           conversion={"val_0": 0, "text_0": "idle", "val_1": 1, "text_1": "run", "val_2": 4, "text_2": "stop", "default_addr": "unknown"}),
    Signal(samples=(T * 5).astype(np.float64), timestamps=T, name="rational", conversion={"P1": 0, "P2": 1, "P3": 0, "P4": 0, "P5": 1, "P6": 4}),
])
mdf.save(f"{out}/conversions_more.mf4", overwrite=True)
mdf.close()
write_json("conversions_more", describe(f"{out}/conversions_more.mf4"))

# Strings in signal data blocks: plain, and zipped.
words = np.array([("word %d" % (i % 9) + "x" * (i % 5) + "ä").encode("utf-8") for i in range(N)])
for case, kwargs in (("strings", {}), ("strings_zipped", {"compression": 1})):
    mdf = MDF(version="4.10")
    mdf.append([
        Signal(samples=np.sin(T), timestamps=T, name="v"),
        Signal(samples=words, timestamps=T, name="text", encoding="utf-8"),
    ])
    mdf.save(f"{out}/{case}.mf4", overwrite=True, **kwargs)
    mdf.close()
    write_json(case, describe(f"{out}/{case}.mf4"))

# Masters that aren't time.
for label, sync_type in (("angle", 2), ("distance", 3), ("index", 4)):
    mdf = MDF(version="4.10")
    mdf.append([Signal(samples=np.sin(T), timestamps=T * 10, name="v", master_metadata=(f"{label}_axis", sync_type))])
    case = f"master_{label}"
    mdf.save(f"{out}/{case}.mf4", overwrite=True)
    mdf.close()
    write_json(case, describe(f"{out}/{case}.mf4", {"sync_type": sync_type}))

# Invalid samples (invalidation bits in the records).
mdf = MDF(version="4.10")
mdf.append([Signal(samples=np.arange(N, dtype=np.float32), timestamps=T, name="w", invalidation_bits=(np.arange(N) % 4 == 0))])
mdf.save(f"{out}/invalid.mf4", overwrite=True)
mdf.close()
write_json("invalid", describe(f"{out}/invalid.mf4"))

# Several groups of their own times.
mdf = MDF(version="4.10")
for g in range(6):
    t = np.round(np.arange(0, 2 + g, 0.5 / (g + 1)), 6)
    mdf.append(
        [Signal(samples=np.sin(t * (g + 1)), timestamps=t, name=f"s{g}", unit="V"),
         Signal(samples=(t * 3).astype(np.int32), timestamps=t, name=f"c{g}")],
        acq_name=f"group {g}",
    )
mdf.save(f"{out}/many_groups.mf4", overwrite=True)
mdf.close()
write_json("many_groups", describe(f"{out}/many_groups.mf4"))

# Arrays: one and two dimensions, and the kinds of map asammdf writes.
dt1 = np.dtype([("arr", "<f4", (3,))])
a1 = np.zeros(N, dtype=dt1)
a1["arr"] = np.arange(N * 3, dtype=np.float32).reshape(N, 3) * 0.5
dt2 = np.dtype([("grid", "<i2", (2, 3))])
a2 = np.zeros(N, dtype=dt2)
a2["grid"] = np.arange(N * 6, dtype=np.int16).reshape(N, 2, 3)
mdf = MDF(version="4.10")
mdf.append([Signal(samples=a1, timestamps=T, name="arr")])
mdf.append([Signal(samples=a2, timestamps=T, name="grid")])
mdf.save(f"{out}/arrays.mf4", overwrite=True)
mdf.close()
write_json("arrays", describe(f"{out}/arrays.mf4"))

# Events.
mdf = MDF(version="4.10")
mdf.append([Signal(samples=np.sin(T), timestamps=T, name="v")])
events = []
for name, comment, kind, rng, cause, base, factor in (
    ("start", "begins", 3, 1, 4, 0, 1.0),
    ("mark", "a bookmark", 6, 0, 2, 1000000000, 1e-9 * 2.5),
    ("end", "ends", 4, 2, 4, 5, 1.0),
):
    ev = v4b.EventBlock(event_type=kind, sync_type=1, range_type=rng, cause=cause, flags=0, sync_base=base, sync_factor=factor)
    ev.name = name
    ev.comment = comment
    mdf.events.append(ev)
    events.append({"name": name, "comment": comment, "type": kind, "range": rng, "cause": cause, "base": base, "factor": factor})
mdf.save(f"{out}/events.mf4", overwrite=True)
mdf.close()
write_json("events", describe(f"{out}/events.mf4", {"events": events}))

# Attachments, embedded plain and deflated.
mdf = MDF(version="4.10")
mdf.append([Signal(samples=np.sin(T), timestamps=T, name="v")])
attachments = []
for name, data, comment, compressed, mime in (
    ("note.txt", b"hello attachment " * 20, "a note", False, "text/plain"),
    ("packed.bin", bytes(range(256)) * 4, "packed", True, "application/octet-stream"),
):
    mdf.attach(data, file_name=name, comment=comment, compression=compressed, mime=mime, embedded=True)
    attachments.append({"name": name, "comment": comment, "compressed": compressed, "mime": mime, "bytes": list(data)})
mdf.save(f"{out}/attachments.mf4", overwrite=True)
mdf.close()
write_json("attachments", describe(f"{out}/attachments.mf4", {"attachments": attachments}))

# MDF 4.2's column storage, which asammdf's writer reaches only with its Signal given three attributes it lacks.
Signal.axes = None
Signal.conversions = None
Signal.units = None
for case, kwargs in (("columns", {}), ("columns_zipped", {"compression": 1})):
    mdf = MDF(version="4.20")
    mdf._mdf._column_storage = True
    mdf._mdf._use_ld_blocks = True
    s = np.sin(T)
    c = np.arange(N, dtype=np.int16)
    mdf.append([Signal(samples=s, timestamps=T, name="s", unit="V"), Signal(samples=c, timestamps=T, name="c")])
    mdf.save(f"{out}/{case}.mf4", overwrite=True, **kwargs)
    mdf.close()
    write_json(case, {
        "version": "4.20",
        "groups": [{
            "name": None,
            "channels": [
                {"name": "s", "times": T.tolist(), "converted": s.tolist(), "stored": s.tolist(), "invalid": None},
                {"name": "c", "times": T.tolist(), "converted": c.astype(float).tolist(), "stored": c.astype(float).tolist(), "invalid": None},
            ],
        }],
    })

print("ok")
