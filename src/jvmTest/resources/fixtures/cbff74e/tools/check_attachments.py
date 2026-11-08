"""Decodes the configurations attached to each family's files (the bytes are in the manifests) with cbor2, an
implementation independent of rt_core's zcbor, and checks that the attached sensors configuration describes the very sensors
the generator logged, and that the attached logging configuration has an entry for each sensor that is logged.

    venv\\Scripts\\python.exe -m pip install cbor2
    venv\\Scripts\\python.exe cbff74e\\tools\\check_attachments.py cbff74e\\manifests
"""
import json
import sys
from pathlib import Path

import cbor2


def main(folder):
    for path in sorted(Path(folder).glob("manifest_*.json")):
        manifest = json.loads(path.read_text(encoding="utf-8"))
        attachments = {a["file_name"]: bytes.fromhex(a["bytes_hex"]) for a in manifest["attachments"]}
        sensors = cbor2.loads(attachments["sensors_config.cbor"])[0]
        logging = cbor2.loads(attachments["logging_config.cbor"])
        logged = [s for s in manifest["sensors"]]

        assert [s[0] for s in sensors] == [s["id"] for s in logged], f"{path.name}: sensor IDs differ"
        for stored, fed in zip(sensors, logged):
            assert stored[1] is fed["sensor_enabled"], f"{path.name}: {fed['id']} enabled"
            assert stored[2][0] == fed["name"] and stored[2][1] == fed["unit"] and stored[2][2] == fed["description"], \
                f"{path.name}: {fed['id']} metadata"
            assert stored[3][0] == {"PHYSICAL_ANALOG": 1, "VIRTUAL_ANALOG": 2, "PHYSICAL_INDICATOR": 3, "VIRTUAL_INDICATOR": 4,
                                    "CANBUS_RAW": 5, "CANBUS_ANALOG": 6, "CANBUS_INDICATOR": 7, "USER_ANALOG": 8,
                                    "USER_INDICATOR": 9, "CONTROL_INDICATOR": 10, "CONTROL_ANALOG": 11}[fed["type"]], \
                f"{path.name}: {fed['id']} type"
        assert len(logging[2]) == sum(1 for s in logged if s["logging_enabled"]), f"{path.name}: logging entries"
        print(f"{path.name}: {len(sensors)} sensors and {len(logging[2])} logging entries as logged")


if __name__ == "__main__":
    main(sys.argv[1])
