"""An independent parser of the records of an unsorted data group, from the group layouts mdfwalk finds.

It is written for the Eerie Leap logs: one DT block, a record ID of 1 or 2 bytes, groups that are either
plain (fixed length: the data bytes plus the invalidation bytes), VLSD (a 4-byte length then the bytes)
or the CAN_DataFrame group. It reads to the end of the data block (to the end of the file for an unfinalized one) and stops at the last
whole record, as the reader has to.
"""
import struct

import mdfwalk


def parse(data: bytes):
    walked = mdfwalk.walk(data)
    if "data_groups" not in walked or not walked["data_groups"]:
        return {"walked": walked, "records": [], "stop": "no data group"}
    dg = walked["data_groups"][0]
    id_size = dg["record_id_size"]
    block = dg.get("data_block")
    if block is None:
        return {"walked": walked, "records": [], "stop": "no data block"}
    position = block["offset"] + 24
    end = len(data)
    if block["length_field"] > 24:  # a finalized file: the data block ends where its length says; an end event may follow
        end = min(end, block["offset"] + block["length_field"])

    layouts = {}
    for cg in dg["channel_groups"]:
        layouts[cg["record_id"]] = cg

    records = []
    stop = "end of file"
    while position < end:
        if position + id_size > end:
            stop = f"cut inside a record ID at {position}"
            break
        record_id = int.from_bytes(data[position:position + id_size], "little")
        cg = layouts.get(record_id)
        if cg is None:
            stop = f"unknown record ID {record_id} at {position}"
            break
        if cg["is_vlsd"]:
            if position + id_size + 4 > end:
                stop = f"cut inside a VLSD length at {position}"
                break
            length = struct.unpack_from("<I", data, position + id_size)[0]
            size = id_size + 4 + length
            if position + size > end:
                stop = f"cut inside a VLSD payload at {position}"
                break
            records.append({"offset": position, "size": size, "record_id": record_id, "kind": "vlsd", "length": length,
                            "payload": data[position + id_size + 4:position + size].hex()})
        else:
            size = id_size + cg["data_bytes_field"] + cg["invalidation_bytes"]
            if position + size > end:
                stop = f"cut inside a record at {position}"
                break
            body = data[position + id_size:position + size]
            record = {"offset": position, "size": size, "record_id": record_id, "kind": "plain", "group": cg["acq_name"]}
            record["time"] = struct.unpack_from("<d", body, 0)[0]
            if cg["is_bus_event"]:
                record["kind"] = "can"
                frame = body[8:]
                (bus, ident, flags, length) = struct.unpack_from("<BIBB", frame, 0)
                offset = struct.unpack_from("<Q", frame, 7)[0]
                record.update({"bus_channel": bus, "id": ident & 0x1FFFFFFF, "extended": bool(ident >> 31),
                               "dlc": flags & 0xF, "transmit": bool(flags & 0x10), "fd": bool(flags & 0x20),
                               "brs": bool(flags & 0x40), "esi": bool(flags & 0x80), "length": length,
                               "vlsd_offset": offset})
            else:
                values = []
                for channel in cg["channels"]:
                    if channel["type"] == "master":
                        continue
                    if channel["data_type"] == "float_le" and channel["bit_count"] == 32:
                        values.append(struct.unpack_from("<f", body, channel["byte_offset"])[0])
                    else:
                        values.append(None)
                inval = body[cg["data_bytes_field"]] if cg["invalidation_bytes"] else 0
                record["values"] = values
                record["invalidation_byte"] = inval
            records.append(record)
        position += size
    return {"walked": walked, "records": records, "stop": stop, "end": position}


if __name__ == "__main__":
    import sys
    from collections import Counter
    d = open(sys.argv[1], "rb").read()
    r = parse(d)
    print("stop:", r["stop"], "records:", len(r["records"]))
    print(Counter((x["record_id"], x["kind"]) for x in r["records"]))
