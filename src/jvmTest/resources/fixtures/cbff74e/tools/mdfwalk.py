"""A small, independent MDF 4 block walker, written for these fixtures (the cbff74e copy: it also walks the
attachments (AT), the events (EV), the channel limits and what follows the data block).

It follows the links of a file's blocks (HD, FH, DG, CG, CN, CC, SI, TX/MD) straight from the bytes and
reports what is in them, with the raw text of every comment, so that the fixtures can be described
without asammdf's reading of them (asammdf pretty-prints comments, folds source blocks, recounts the
cycles of unfinalized files). It tolerates a file that is cut short: whatever it can't reach is
reported, not raised.
"""
import re
import struct

HEADER = struct.Struct("<4sIQQ")  # id, reserved, length, link count

CC_TYPES = {
    0: "identity", 1: "linear", 2: "rational", 3: "algebraic", 4: "value_to_value_interpolated",
    5: "value_to_value", 6: "value_range_to_value", 7: "value_to_text", 8: "value_range_to_text",
    9: "text_to_value", 10: "text_to_text", 11: "bit_field_to_text",
}
CN_TYPES = {0: "fixed_length", 1: "variable_length", 2: "master", 3: "virtual_master", 4: "sync", 5: "max_length", 6: "virtual_data"}
SI_TYPES = {0: "other", 1: "ecu", 2: "bus", 3: "io_device", 4: "tool", 5: "user"}
BUS_TYPES = {0: "none", 1: "other", 2: "can", 3: "lin", 4: "most", 5: "flexray", 6: "kline", 7: "ethernet", 8: "usb"}
DATA_TYPES = {
    0: "uint_le", 1: "uint_be", 2: "int_le", 3: "int_be", 4: "float_le", 5: "float_be", 6: "string_latin1",
    7: "string_utf8", 8: "string_utf16_le", 9: "string_utf16_be", 10: "byte_array", 11: "mime_sample",
    12: "mime_stream", 13: "canopen_date", 14: "canopen_time", 15: "complex_le", 16: "complex_be",
}


def properties_of(xml):
    """The <common_properties> of a comment, as [[name, value], ...] in file order (None without a comment)."""
    if xml is None:
        return None
    return [[name, value] for name, value in re.findall(r'<e name="([^"]*)">(.*?)</e>', xml)]


class Walker:
    def __init__(self, data: bytes):
        self.data = data
        self.problems = []

    def block(self, offset):
        """(id, length, links, body_offset) of the block at offset, or None when it can't be read."""
        if not offset:
            return None
        if offset + HEADER.size > len(self.data):
            self.problems.append(f"block at {offset} starts past the end of the file ({len(self.data)} bytes)")
            return None
        block_id, _, length, link_count = HEADER.unpack_from(self.data, offset)
        try:
            block_id = block_id.decode("ascii")
        except UnicodeDecodeError:
            self.problems.append(f"no block at {offset}")
            return None
        links_end = offset + HEADER.size + 8 * link_count
        if links_end > len(self.data):
            self.problems.append(f"{block_id} at {offset}: links run past the end of the file")
            return None
        links = list(struct.unpack_from("<" + "Q" * link_count, self.data, offset + HEADER.size))
        if offset + length > len(self.data) and block_id != "##DT":
            self.problems.append(f"{block_id} at {offset}: length {length} runs past the end of the file")
        return block_id, length, links, links_end

    def text(self, offset):
        """The text of a TX or MD block (up to the first NUL), or None."""
        info = self.block(offset)
        if info is None:
            return None
        block_id, length, _, body = info
        if block_id not in ("##TX", "##MD"):
            self.problems.append(f"{block_id} at {offset} where a text was expected")
            return None
        raw = self.data[body:offset + length]
        return raw.split(b"\0", 1)[0].decode("utf-8", errors="replace")

    def read(self, fmt, offset):
        size = struct.calcsize(fmt)
        if offset + size > len(self.data):
            self.problems.append(f"data at {offset} runs past the end of the file")
            return None
        return struct.unpack_from(fmt, self.data, offset)

    # ------------------------------------------------------------------------------------------

    def identification(self):
        d = self.data
        if len(d) < 64:
            return None
        return {
            "file_identifier": d[0:8].decode("latin-1"),
            "format_identifier": d[8:16].decode("latin-1").strip(),
            "program_identifier": d[16:24].decode("latin-1").strip(),
            "version_number": struct.unpack_from("<H", d, 28)[0],
            "unfinalized_standard_flags": struct.unpack_from("<H", d, 60)[0],
            "unfinalized_custom_flags": struct.unpack_from("<H", d, 62)[0],
        }

    def conversion(self, offset):
        info = self.block(offset)
        if info is None:
            return None
        block_id, length, links, body = info
        if block_id != "##CC":
            return {"error": f"{block_id} where a conversion was expected"}
        cc_type, precision, flags, ref_count, val_count, phy_min, phy_max = struct.unpack_from("<BBHHHdd", self.data, body)
        values = list(struct.unpack_from("<" + "d" * val_count, self.data, body + 24))
        result = {
            "type": CC_TYPES.get(cc_type, cc_type), "type_number": cc_type, "flags": flags,
            "name": self.text(links[0]) if links[0] else None, "unit": self.text(links[1]) if links[1] else None,
            "comment_raw": self.text(links[2]) if links[2] else None,
            "ref_count": ref_count, "values": values,
        }
        if cc_type == 3 and len(links) > 4 and links[4]:
            result["formula"] = self.text(links[4])
        return result

    def source(self, offset):
        info = self.block(offset)
        if info is None:
            return None
        _, _, links, body = info
        si_type, bus_type, flags = struct.unpack_from("<BBB", self.data, body)
        return {
            "name": self.text(links[0]) if links[0] else None, "path": self.text(links[1]) if links[1] else None,
            "comment_raw": self.text(links[2]) if links[2] else None,
            "type": SI_TYPES.get(si_type, si_type), "bus_type": BUS_TYPES.get(bus_type, bus_type),
        }

    def channel(self, offset, depth=0):
        info = self.block(offset)
        if info is None:
            return None
        block_id, length, links, body = info
        if block_id != "##CN":
            return {"error": f"{block_id} at {offset} where a channel was expected"}
        (cn_type, sync_type, data_type, bit_offset, byte_offset, bit_count, flags, inval_bit,
         precision, _, attachment_count, rmin, rmax, lmin, lmax, elmin, elmax) = struct.unpack_from(
            "<BBBBIIIIBBHdddddd", self.data, body)
        channel = {
            "offset": offset,
            "name": self.text(links[2]) if links[2] else None,
            "unit": self.text(links[6]) if links[6] else None,
            "comment_raw": self.text(links[7]) if links[7] else None,
            "type": CN_TYPES.get(cn_type, cn_type), "sync_type": sync_type,
            "data_type": DATA_TYPES.get(data_type, data_type), "bit_offset": bit_offset,
            "byte_offset": byte_offset, "bit_count": bit_count, "flags": flags,
            "invalidation_bit_position": inval_bit if flags & 2 else None,
            "value_range": [rmin, rmax] if flags & 8 else None,
            "limits": [lmin, lmax] if flags & 0x10 else None,
            "extended_limits": [elmin, elmax] if flags & 0x20 else None,
            "comment_properties": properties_of(self.text(links[7]) if links[7] else None),
            "conversion": self.conversion(links[4]) if links[4] else None,
            "source": self.source(links[3]) if links[3] else None,
            "data_link": links[5] or None,
        }
        if links[1] and depth < 3:  # composition: the members of a structure
            members = []
            member = links[1]
            guard = 0
            while member and guard < 64:
                m = self.channel(member, depth + 1)
                if m is None:
                    break
                members.append(m)
                member = self.block(member)[2][0] if self.block(member) else 0
                guard += 1
            channel["members"] = members
        return channel

    def channel_group(self, offset):
        info = self.block(offset)
        if info is None:
            return None
        block_id, length, links, body = info
        record_id, cycles, flags, separator, _, data_bytes, inval_bytes = struct.unpack_from("<QQHHIII", self.data, body)
        group = {
            "offset": offset, "record_id": record_id, "cycle_count": cycles, "flags": flags,
            "is_vlsd": bool(flags & 1), "is_bus_event": bool(flags & 2), "is_plain_bus_event": bool(flags & 4),
            "data_bytes_field": data_bytes, "invalidation_bytes": inval_bytes,
            "acq_name": self.text(links[2]) if links[2] else None,
            "source": self.source(links[3]) if links[3] else None,
            "comment_raw": self.text(links[5]) if len(links) > 5 and links[5] else None,
            "comment_properties": properties_of(self.text(links[5]) if len(links) > 5 and links[5] else None),
            "channels": [],
        }
        if group["is_vlsd"]:
            # for a VLSD group the byte count of the payload records is split across both fields
            group["vlsd_data_bytes"] = data_bytes | (inval_bytes << 32)
        channel = links[1]
        guard = 0
        while channel and guard < 4096:
            c = self.channel(channel)
            if c is None:
                break
            group["channels"].append(c)
            nxt = self.block(channel)
            channel = nxt[2][0] if nxt else 0
            guard += 1
        return group, links[0]

    def attachment(self, offset):
        info = self.block(offset)
        if info is None:
            return None
        block_id, length, links, body = info
        if block_id != "##AT":
            return {"error": f"{block_id} where an attachment was expected"}
        flags, creator, _, md5, original, embedded = struct.unpack_from("<HHI16sQQ", self.data, body)
        data_start = body + 40
        content = self.data[data_start:data_start + embedded]
        import zlib
        return {
            "offset": offset, "length": length, "flags": flags, "creator_index": creator, "md5_hex": md5.hex(),
            "original_size": original, "embedded_size": embedded,
            "file_name": self.text(links[1]) if links[1] else None,
            "mime_type": self.text(links[2]) if links[2] else None,
            "comment_raw": self.text(links[3]) if links[3] else None,
            "comment_properties": properties_of(self.text(links[3]) if links[3] else None),
            "bytes_crc32": zlib.crc32(content) & 0xFFFFFFFF, "bytes_hex": content.hex(),
            "padding_after_data": length - 24 - 8 * len(links) - 40 - embedded,
        }, links[0]

    def event(self, offset):
        info = self.block(offset)
        if info is None:
            return None
        block_id, length, links, body = info
        if block_id != "##EV":
            return {"error": f"{block_id} where an event was expected"}
        (ev_type, sync_type, range_type, cause, flags, _, scope_count, attachment_count, creator, base, factor) =             struct.unpack_from("<BBBBB3sIHHqd", self.data, body)
        return {
            "offset": offset, "length": length, "link_count": len(links), "type": ev_type, "sync_type": sync_type,
            "range_type": range_type, "cause": cause, "flags": flags, "scope_count": scope_count,
            "attachment_count": attachment_count, "creator_index": creator, "sync_base_value": base,
            "sync_factor": factor, "next": links[0], "parent": links[1], "range": links[2],
            "name": self.text(links[3]) if links[3] else None,
            "comment_raw": self.text(links[4]) if links[4] else None,
            "comment_properties": properties_of(self.text(links[4]) if links[4] else None),
        }, links[0]

    def data_group(self, offset):
        info = self.block(offset)
        if info is None:
            return None
        _, _, links, body = info
        rec_id_size = struct.unpack_from("<B", self.data, body)[0]
        group = {"offset": offset, "record_id_size": rec_id_size, "data_link": links[2] or None, "channel_groups": []}
        cg = links[1]
        guard = 0
        while cg and guard < 4096:
            result = self.channel_group(cg)
            if result is None:
                break
            channel_group, cg = result
            group["channel_groups"].append(channel_group)
            guard += 1
        data = links[2]
        if data:
            dt = self.block(data)
            if dt is not None:
                block_id, length, _, body_offset = dt
                group["data_block"] = {
                    "type": block_id, "offset": data, "length_field": length,
                    "bytes_after_header": max(0, min(len(self.data), data + length) - (data + 24)),
                    "bytes_to_end_of_file": len(self.data) - (data + 24),
                }
        return group, links[0]

    def walk(self):
        result = {"size": len(self.data), "identification": self.identification()}
        info = self.block(64)
        if info is None or info[0] != "##HD":
            result["problems"] = self.problems
            return result
        _, _, links, body = info
        start_ns, tz, dst, time_flags, time_class, flags = struct.unpack_from("<QhhBBB", self.data, body)
        result["header"] = {
            "start_time_ns": start_ns, "tz_offset_min": tz, "dst_offset_min": dst, "time_flags": time_flags,
            "time_class": time_class, "flags": flags,
            "comment_raw": self.text(links[5]) if links[5] else None,
        }
        fh_list = []
        fh = links[1]
        guard = 0
        while fh and guard < 64:
            f = self.block(fh)
            if f is None:
                break
            time_ns, ftz, fdst, ftflags = struct.unpack_from("<QhhB", self.data, f[3])
            fh_list.append({"time_ns": time_ns, "time_flags": ftflags, "comment_raw": self.text(f[2][1]) if f[2][1] else None})
            fh = f[2][0]
            guard += 1
        result["file_history"] = fh_list
        groups = []
        dg = links[0]
        guard = 0
        while dg and guard < 64:
            r = self.data_group(dg)
            if r is None:
                break
            g, dg = r
            groups.append(g)
            guard += 1
        result["data_groups"] = groups
        result["header"]["links"] = {"dg_first": links[0], "fh_first": links[1], "ch_first": links[2],
                                     "at_first": links[3], "ev_first": links[4], "md_comment": links[5]}
        attachments = []
        at = links[3]
        guard = 0
        while at and guard < 64:
            r = self.attachment(at)
            if r is None or isinstance(r, dict):
                break
            a, at = r
            attachments.append(a)
            guard += 1
        result["attachments"] = attachments
        events = []
        ev = links[4]
        guard = 0
        while ev and guard < 64:
            r = self.event(ev)
            if r is None or isinstance(r, dict):
                break
            e, ev = r
            events.append(e)
            guard += 1
        result["events"] = events
        result["tail"] = self.tail(groups)
        result["problems"] = self.problems
        return result

    def tail(self, groups):
        """What follows the data block: the zero padding to 8 bytes and the blocks after it (an end event)."""
        if not groups or "data_block" not in groups[0]:
            return None
        block = groups[0]["data_block"]
        end = block["offset"] + block["length_field"]
        if block["length_field"] <= 24 or end >= len(self.data):
            return {"data_block_end": end, "bytes_after": max(0, len(self.data) - end)}
        padding = 0
        while end + padding < len(self.data) and self.data[end + padding] == 0:
            padding += 1
        return {"data_block_end": end, "bytes_after": len(self.data) - end, "zero_padding_bytes": padding,
                "first_block_after_padding": self.data[end + padding:end + padding + 4].decode("latin-1")
                if end + padding < len(self.data) else None,
                "first_block_offset": end + padding, "file_size_multiple_of_8": len(self.data) % 8 == 0}


def walk(data: bytes):
    try:
        return Walker(data).walk()
    except Exception as exc:  # noqa: BLE001 - a damaged file is a result here, not an error
        return {"size": len(data), "walker_exception": f"{type(exc).__name__}: {exc}"}
