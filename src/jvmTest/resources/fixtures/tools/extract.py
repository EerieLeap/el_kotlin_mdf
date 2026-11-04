"""Builds fixtures.json: for every generated file its size, CRC-32, what it is, the configuration it was
written with, an independent walk of its blocks, and asammdf's reading of it (stored and converted
samples with their invalidation bits, the CAN frames, and exactly what asammdf logged or raised).

    venv\\Scripts\\python.exe tools\\extract.py <generator output folder> .

arguments: the folder the generator wrote its files and manifest_*.json into (copied out of the container),
and the fixtures folder to write into (the .mf4 files, manifests/ and fixtures.json go there). asammdf
8.8.27 is expected (pip install asammdf==8.8.27); tools/mdfwalk.py and tools/records.py are imported.
"""
import json
import logging
import platform
import re
import shutil
import sys
import warnings
import zlib
from collections import defaultdict
from pathlib import Path

import numexpr
import numpy as np

import asammdf
from asammdf import MDF

import mdfwalk
import records

GEN = Path(sys.argv[1])
OUT = Path(sys.argv[2])


# ----------------------------------------------------------------------------------------------------
# helpers

def clean(value):
    """JSON-safe: NaN and infinities become null (listed in the file's notes), numpy scalars plain numbers."""
    if isinstance(value, (list, tuple)):
        return [clean(v) for v in value]
    if isinstance(value, dict):
        return {str(k): clean(v) for k, v in value.items()}
    if isinstance(value, (np.floating, float)):
        value = float(value)
        return None if value != value or value in (float("inf"), float("-inf")) else value
    if isinstance(value, np.integer):
        return int(value)
    if isinstance(value, np.bool_):
        return bool(value)
    if isinstance(value, bytes):
        return value.hex()
    return value


def floats(array):
    return clean(np.asarray(array).astype(np.float64).tolist())


def flags(array, length):
    if array is None:
        return [0] * length
    return np.asarray(array).astype(np.uint8).tolist()


class ListHandler(logging.Handler):
    def __init__(self):
        super().__init__(level=logging.DEBUG)
        self.records = []

    def emit(self, record):
        self.records.append({"level": record.levelname, "message": record.getMessage()})


def normalise_message(message, file_name):
    # asammdf works on a temporary copy of an unfinalized file: <12 hex digits>_<name> in the temp folder
    return re.sub(r"\S*[0-9a-f]{12}_" + re.escape(file_name), file_name, message)


def text_of(value):
    return value if isinstance(value, str) else (value.decode("utf-8", "replace") if isinstance(value, bytes) else value)


def conversion_json(conversion):
    if conversion is None:
        return None
    return {
        "type": int(conversion.conversion_type), "formula": text_of(getattr(conversion, "formula", None)) or None,
        "class": type(conversion).__name__,
    }


# ----------------------------------------------------------------------------------------------------
# asammdf

def read_with_asammdf(path: Path):
    """What asammdf makes of the file. Never raises: an exception is part of the answer."""
    handler = ListHandler()
    logger = logging.getLogger("asammdf")
    previous = (logger.level, logger.propagate, list(logger.handlers))
    logger.setLevel(logging.DEBUG)
    logger.propagate = False
    for existing in previous[2]:  # asammdf's own console handler: keep the output for the file
        logger.removeHandler(existing)
    logger.addHandler(handler)

    result = {"opens": False}
    try:
        with warnings.catch_warnings(record=True) as caught:
            warnings.simplefilter("always")
            try:
                mdf = MDF(str(path))
            except Exception as exc:  # noqa: BLE001
                result["exception"] = {"type": type(exc).__name__, "message": str(exc)}
                mdf = None
            result["python_warnings"] = [f"{w.category.__name__}: {w.message}" for w in caught]

        if mdf is not None:
            result["opens"] = True
            try:
                result.update(read_groups(mdf, path.name))
            except Exception as exc:  # noqa: BLE001
                result["read_exception"] = {"type": type(exc).__name__, "message": str(exc)}
            finally:
                mdf.close()
    finally:
        logger.removeHandler(handler)
        for existing in previous[2]:
            logger.addHandler(existing)
        logger.setLevel(previous[0])
        logger.propagate = previous[1]

    result["log"] = [
        {"level": r["level"], "message": normalise_message(r["message"], path.name)} for r in handler.records
    ]
    return result


def read_groups(mdf, file_name):
    info = {"version": mdf.version}
    header = mdf.header
    info["header"] = {
        "start_time": header.start_time.isoformat(), "start_time_ns": int(header.abs_time),
        "comment": header.comment, "common_properties": dict(getattr(header, "_common_properties", {}) or {}),
    }
    info["file_history_comments"] = [fh.comment for fh in mdf.file_history]
    info["identification_after_open"] = {
        "file_identification": mdf.identification.file_identification.decode("latin-1"),
        "unfinalized_standard_flags": int(mdf.identification.unfinalized_standard_flags),
    }

    groups = []
    for gi, group in enumerate(mdf.groups):
        cg = group.channel_group
        source = cg.acq_source
        entry = {
            "index": gi, "name": cg.acq_name or None, "comment": cg.comment or None,
            "source": None if source is None else {
                "name": source.name or None, "path": source.path or None, "source_type": int(source.source_type),
                "bus_type": int(source.bus_type)},
            "record_id": int(cg.record_id), "cycles": int(cg.cycles_nr), "flags": int(cg.flags),
            "is_vlsd": bool(cg.flags & 1), "is_bus_event": bool(cg.flags & 2),
            "data_bytes": int(cg.samples_byte_nr), "invalidation_bytes": int(cg.invalidation_bytes_nr),
        }
        try:
            if entry["is_vlsd"]:
                pass
            elif entry["is_bus_event"]:
                entry.update(read_can_group(mdf, gi))
            else:
                entry.update(read_value_group(mdf, gi))
        except Exception as exc:  # noqa: BLE001
            entry["read_exception"] = {"type": type(exc).__name__, "message": str(exc)}
        groups.append(entry)
    info["groups"] = groups

    try:
        info["bus_logging_map"] = clean({
            bus: {str(k): {str(i): int(n) for i, n in v.items()} for k, v in channels.items()}
            for bus, channels in mdf.bus_logging_map.items()})
    except Exception as exc:  # noqa: BLE001
        info["bus_logging_map_exception"] = f"{type(exc).__name__}: {exc}"
    return info


def read_value_group(mdf, gi):
    """Every record of the group with its invalidation bits.

    asammdf's plain get() DROPS the samples whose invalidation bit is set (and returns no bits), so the
    stored and converted samples here come from get(..., ignore_invalidation_bits=True), which keeps them
    all and returns the bits; `default_get_count` is how many samples the plain call returns.
    """
    group = mdf.groups[gi]
    out = {"master": None, "channels": []}
    for ci, channel in enumerate(group.channels):
        raw = mdf.get(group=gi, index=ci, raw=True, ignore_invalidation_bits=True)
        if channel.channel_type == 2:  # master
            out["master"] = {"name": channel.name, "unit": raw.unit or None,
                             "timestamps": floats(raw.timestamps), "samples": floats(raw.samples)}
            continue
        count = len(raw.samples)
        default_count = len(mdf.get(group=gi, index=ci, raw=True).samples)
        entry = {
            "name": channel.name, "unit": channel.unit or None, "comment": raw.comment or None,
            "data_type": int(channel.data_type), "bit_count": int(channel.bit_count),
            "has_invalidation_bit": bool(channel.flags & 2),
            "invalidation_bit_position": int(channel.pos_invalidation_bit) if channel.flags & 2 else None,
            "conversion": conversion_json(channel.conversion),
            "stored": {"dtype": str(raw.samples.dtype), "samples": floats(raw.samples),
                       "invalid": flags(raw.invalidation_bits, count)},
            "default_get_count": default_count,
        }
        if channel.conversion is not None:
            converted = mdf.get(group=gi, index=ci, ignore_invalidation_bits=True)
            entry["converted"] = {"dtype": str(converted.samples.dtype), "samples": floats(converted.samples),
                                  "invalid": flags(converted.invalidation_bits, len(converted.samples))}
        out["channels"].append(entry)

    # a raw channel's converted samples should be the value channel's samples (the logger writes both)
    by_name = {c["name"]: c for c in out["channels"]}
    for c in out["channels"]:
        if c["name"].endswith(".raw") and "converted" in c and c["name"][:-4] in by_name:
            v = by_name[c["name"][:-4]]
            diffs = [abs(a - b) for a, b, i1, i2 in zip(c["converted"]["samples"], v["stored"]["samples"],
                                                         c["stored"]["invalid"], v["stored"]["invalid"])
                     if a is not None and b is not None and not i1 and not i2]
            c["converted_vs_value_channel"] = {"samples_compared": len(diffs),
                                               "max_abs_diff": max(diffs) if diffs else None}
    return out


def read_can_group(mdf, gi):
    frames_signal = mdf.get("CAN_DataFrame", group=gi)
    samples = frames_signal.samples
    stamps = frames_signal.timestamps
    names = samples.dtype.names
    frames = []
    for i in range(len(samples)):
        row = samples[i]
        length = int(row["CAN_DataFrame.DataLength"])
        frames.append({
            "t": float(stamps[i]), "bus_channel": int(row["CAN_DataFrame.BusChannel"]),
            "id": int(row["CAN_DataFrame.ID"]), "extended": bool(row["CAN_DataFrame.IDE"]),
            "dlc": int(row["CAN_DataFrame.DLC"]), "transmit": bool(row["CAN_DataFrame.Dir"]),
            "fd": bool(row["CAN_DataFrame.EDL"]), "brs": bool(row["CAN_DataFrame.BRS"]),
            "esi": bool(row["CAN_DataFrame.ESI"]), "data_length": length,
            "data": bytes(np.asarray(row["CAN_DataFrame.DataBytes"], dtype=np.uint8)[:length]).hex(),
        })
    return {"fields": list(names), "frames": frames,
            "data_bytes_array_length": int(samples.dtype["CAN_DataFrame.DataBytes"].shape[0])}


# ----------------------------------------------------------------------------------------------------
# verification against what the generator fed

def verify(file_entry, manifest, file_index, asm):
    """Compares asammdf's reading with the readings the logger was given (its own answer included)."""
    if not asm.get("opens") or "groups" not in asm:
        return {"checked": False, "reason": "asammdf did not open the file"}

    sensors = manifest["sensors"]
    index_of = {s["id"]: i for i, s in enumerate(sensors)}
    spans_per_sensor = defaultdict(int)
    for begin, end, sensor, payload in file_entry["record_spans"]:
        spans_per_sensor[sensor] += 1

    logged = defaultdict(list)
    for call in manifest["calls"]:
        if call["file"] == file_index and call["logged"]:
            logged[index_of[call["sensor"]]].append(call)

    # map the sensors with a channel group onto asammdf's groups, in order: a CAN sensor has the VLSD group first
    groups = asm["groups"]
    cursor = 0
    problems = []
    checked = []
    for i, sensor in enumerate(sensors):
        if not sensor["has_channel_group"]:
            continue
        expected = logged[i][:spans_per_sensor[i]]
        if sensor["type"] == "CANBUS_RAW":
            vlsd, group = groups[cursor], groups[cursor + 1]
            cursor += 2
            if "frames" not in group:
                problems.append(f"{sensor['id']}: asammdf read no frames ({group.get('read_exception')})")
                continue
            got = group["frames"]
            if len(got) != len(expected):
                problems.append(f"{sensor['id']}: {len(got)} frames read, {len(expected)} whole frames fed")
            for k, (frame, call) in enumerate(zip(got, expected)):
                want_bus = (sensor["bus_channel"] + 1) if sensor["bus_channel"] is not None else 0
                length = call["length"]
                dlc = length if length <= 8 else next(9 + j for j, n in enumerate([12, 16, 20, 24, 32, 48, 64]) if length <= n)
                if (frame["id"], frame["extended"], frame["transmit"], frame["fd"], frame["brs"], frame["data"], frame["bus_channel"], frame["dlc"], frame["data_length"]) != (
                        call["id"], call["extended"], call["transmit"], call["fd"], call["brs"], call["data"], want_bus, dlc, length):
                    problems.append(f"{sensor['id']}: frame {k} differs from the one fed: {frame} / {call}")
            checked.append(sensor["id"])
        else:
            group = groups[cursor]
            cursor += 1
            if "channels" not in group:
                problems.append(f"{sensor['id']}: asammdf read no channels ({group.get('read_exception')})")
                continue
            value = next((c for c in group["channels"] if c["name"] == sensor["id"]), None)
            raw = next((c for c in group["channels"] if c["name"] == sensor["id"] + ".raw"), None)
            if sensor["has_raw_channel"] != (raw is not None):
                problems.append(f"{sensor['id']}: raw channel presence differs from the logger's rule")
            n = len(value["stored"]["samples"])
            if n != len(expected):
                problems.append(f"{sensor['id']}: {n} records read, {len(expected)} whole records fed")
            for k, call in enumerate(expected[:n]):
                value_ok = call["value"] is not None and call["error"] == "NONE"
                want_value = call["value"] if value_ok else 0.0
                want_invalid = 0 if value_ok else 1
                got_value = value["stored"]["samples"][k]
                got_invalid = value["stored"]["invalid"][k]
                if np.float32(got_value) != np.float32(want_value) or got_invalid != want_invalid:
                    problems.append(f"{sensor['id']}: record {k} value {got_value}/{got_invalid} vs fed {want_value}/{want_invalid}")
                if raw is not None:
                    want_raw = call["raw"] if call["raw"] is not None else 0.0
                    want_raw_invalid = 0 if call["raw"] is not None else 1
                    if np.float32(raw["stored"]["samples"][k]) != np.float32(want_raw) or raw["stored"]["invalid"][k] != want_raw_invalid:
                        problems.append(f"{sensor['id']}: record {k} raw {raw['stored']['samples'][k]}/{raw['stored']['invalid'][k]} vs fed {want_raw}/{want_raw_invalid}")
            checked.append(sensor["id"])
    if cursor != len(groups):
        problems.append(f"{len(groups)} groups read, {cursor} expected from the sensors")
    return {"checked": True, "sensors_checked": checked, "matches_feed": not problems, "problems": problems}


# ----------------------------------------------------------------------------------------------------

def walk_summary(walked):
    if "data_groups" not in walked:
        return walked
    return {
        "note": "summary; the full walk is that of the file this one derives from (or, for wide_ids.mf4, left out)",
        "identification": walked["identification"], "header": walked["header"],
        "file_history": walked["file_history"],
        "data_groups": [{
            "record_id_size": dg["record_id_size"], "data_block": dg.get("data_block"),
            "channel_groups": [{
                "record_id": cg["record_id"], "acq_name": cg["acq_name"], "cycle_count": cg["cycle_count"],
                "flags": cg["flags"], "vlsd_data_bytes": cg.get("vlsd_data_bytes"),
                "channels": [c["name"] for c in cg["channels"]]} for cg in dg["channel_groups"]],
        } for dg in walked["data_groups"]],
        "problems": walked.get("problems"),
    }


def main():
    manifests = {}
    for path in sorted(GEN.glob("manifest_*.json")):
        manifest = json.loads(path.read_text(encoding="utf-8"))
        manifests[manifest["family"]] = manifest

    (OUT / "manifests").mkdir(exist_ok=True)
    families = {}
    files = []

    for family, manifest in manifests.items():
        shutil.copyfile(GEN / f"manifest_{family}.json", OUT / "manifests" / f"manifest_{family}.json")
        families[family] = {
            "description": manifest["description"], "logging_interval_ms": manifest["logging_interval_ms"],
            "origin_us": manifest["origin_us"], "origin_note": manifest["origin_note"],
            "metadata": manifest["metadata"], "sensors": manifest["sensors"],
            "tick_times_rel_us": manifest["tick_times_rel_us"], "rotations": manifest["rotations"],
            "rotation": manifest.get("rotation"), "manifest": f"manifests/manifest_{family}.json",
        }

        rotation = manifest.get("rotation")
        for entry in manifest["files"]:
            name = entry["name"]
            data = (GEN / name).read_bytes()
            shutil.copyfile(GEN / name, OUT / name)

            file_index = 0
            if rotation and (name == rotation["second_file"] or entry.get("derived_from") == rotation["second_file"]):
                file_index = 1

            walked = mdfwalk.walk(data)
            parsed = records.parse(data)
            by_id = defaultdict(int)
            for record in parsed["records"]:
                by_id[record["record_id"]] += 1
            independent = {
                "note": "records.py: every whole record of the unsorted DT block, parsed straight from the bytes",
                "whole_records": len(parsed["records"]), "records_per_record_id": dict(sorted(by_id.items())),
                "stopped_because": parsed["stop"],
                "bytes_after_the_last_whole_record": len(data) - parsed.get("end", len(data)),
            }
            asm = read_with_asammdf(OUT / name)
            if asm.get("opens") and "groups" in asm:
                independent["asammdf_cycle_counts_match"] = all(
                    by_id.get(g["record_id"], 0) == g["cycles"] for g in asm["groups"] if not g["is_vlsd"])

            result = {
                "name": name, "family": family, "size": len(data),
                "crc32": f"0x{zlib.crc32(data) & 0xFFFFFFFF:08X}", "crc32_decimal": zlib.crc32(data) & 0xFFFFFFFF,
                "role": entry["role"], "description": entry["description"], "derived_from": entry["derived_from"],
                "cut": entry["cut"], "file_index_in_session": file_index,
                "identifier": data[:8].decode("latin-1"),
                "unfinalized_standard_flags": int.from_bytes(data[60:62], "little") if len(data) >= 62 else None,
                "header_bytes": entry["header_bytes"], "start_rel_us": entry["start_rel_us"],
                "end_rel_us": entry["end_rel_us"],
                "sensors": [s["id"] for s in manifest["sensors"] if s["has_channel_group"]],
                "record_spans_note": "[begin, end, sensor index in the family's sensors, CAN payload length] of every record call whose bytes lie wholly inside this file",
                "record_spans": entry["record_spans"],
                "walk": walked if (entry["derived_from"] is None and len(manifest["sensors"]) < 20) else walk_summary(walked),
                "independent_parse": independent,
                "asammdf": asm,
                "verification": verify(entry, manifest, file_index, asm),
            }
            files.append(result)
            status = "opens" if asm["opens"] else "REFUSED"
            check = result["verification"]
            print(f"{name:55s} {len(data):7d} {result['crc32']} {status:8s} "
                  f"{'matches feed' if check.get('matches_feed') else ('NOT CHECKED' if not check['checked'] else 'MISMATCH: ' + '; '.join(check['problems'][:2]))}")

    out = {
        "about": "Fixtures for an MDF 4 reader: logs written by the Eerie Leap firmware's own logger (Mdf4LoggerSensorReading), "
                 "with asammdf's values. See README.md. Times are seconds (float64) as stored in the master channel; "
                 "NaN would be null (none occurs).",
        "generator": {
            "program": "sources/main.cpp (a ztest app on native_sim, linked with rt_core read-only)",
            "rt_core_commit": "542777406e075b1138f6c3d68bfc6229983e5486",
            "rt_core_subject": "tests: the publisher test's two-tick bound is computed in int64_t (2026-10-29)",
            "board": "native_sim/native (32-bit)",
        },
        "asammdf": {
            "version": asammdf.__version__, "python": platform.python_version(), "numpy": np.__version__,
            "numexpr": numexpr.__version__,
        },
        "families": families,
        "files": files,
    }
    text = json.dumps(out, indent=1, ensure_ascii=False)
    # arrays of scalars on one line
    text = re.sub(r"\[\s*([^\[\]{}]*?)\s*\]", lambda m: "[" + re.sub(r"\s*\n\s*", " ", m.group(1)) + "]", text)
    (OUT / "fixtures.json").write_text(text, encoding="utf-8", newline="\n")
    print("wrote", OUT / "fixtures.json", len(text), "bytes")


if __name__ == "__main__":
    main()
