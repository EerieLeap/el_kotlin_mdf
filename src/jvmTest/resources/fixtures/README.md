# MDF reader fixtures: logs from the firmware's own logger, with asammdf's values

Made 2026-11-03 for the log-plots plan (`docs/plans/todo/log-plots.md`, Appendix B and §5.4). Every `.mf4`
here was written by rt_core's `Mdf4LoggerSensorReading` (through its `Mdf4File` writer) on `native_sim`,
with a sensors and a logging configuration made for the test and every reading fed by hand. Nothing is
stubbed: each byte comes out of the firmware's code. They are not logs of a real unit.

- `*.mf4` (31 files, table below), each with its size and CRC-32 (zlib's) in `fixtures.json`
- `fixtures.json`: per file its size, CRC-32, what it is, the configuration it was written with, an
  independent walk of its blocks, and asammdf 8.8.27's reading of it (stored and converted samples with
  their invalidation bits, the CAN frames, what asammdf logged or raised). `families` holds the
  configuration of each session once; a file names its `family`.
- `manifests/manifest_<family>.json`: the generator's own record of a session: the configuration, every
  reading fed to the logger at every tick and the logger's answer (`logged`), the file size after each call,
  and for every file the byte span of every record (`record_spans`: `[begin, end, sensor index, CAN payload length]`).
- `sources/app/`: the generator (a ztest app, `src/main.cpp`), with the `CMakeLists.txt`, `prj.conf`, `Kconfig` and board files it builds with.
- `tools/`: `extract.py` (builds `fixtures.json`), `mdfwalk.py` (an independent MDF 4 block walker) and `records.py` (an independent parser of the unsorted records).
- `venv/` (asammdf 8.8.27, Python 3.12) and `work/` (scratch, the generator's raw output in `work/gen/`) are not deliverables.

## The files

| file | bytes | CRC-32 | what it is | asammdf |
| --- | ---: | --- | --- | --- |
| `can.mf4` | 5381 | `0xAFC4EB0D` | One raw CAN frame sensor (bus 0, BusChannel 1): standard, extended, CAN FD, FD without BRS, transmitted frames, a 64 byte payload, a 10 byte FD payload (DLC 9), a shared timestamp, 30 ticks of 100 ms. | opens |
| `can_unclosed.mf4` | 5381 | `0x4D871880` | The same session as can.mf4 without StopLogging() (power loss): UnFinMF, flags 0x01 0x04 0x20, the VLSD group's byte count not patched. | opens |
| `can_unclosed_cut_boundary.mf4` | 5332 | `0xCB0C6AEA` | Cut at the boundary before the last frame's payload record: whole frames only. | opens |
| `can_unclosed_cut_vlsd_id.mf4` | 5333 | `0xE511D817` | The last frame's payload record has only its record ID. | opens |
| `can_unclosed_cut_vlsd_mid_length.mf4` | 5335 | `0xE2FCFECB` | Cut inside the payload record's 4-byte length (ID and 2 length bytes). | opens |
| `can_unclosed_cut_vlsd_no_payload.mf4` | 5337 | `0x33D2219D` | The payload record's length (20) is complete and no payload byte follows. | opens |
| `can_unclosed_cut_mid_payload.mf4` | 5347 | `0xD1CFF5E2` | Cut inside the payload (10 of its 20 bytes): mid-payload. | opens |
| `can_unclosed_cut_orphan_payload.mf4` | 5357 | `0xDF3E1BF2` | The payload record is complete and the frame record it belongs to is absent: an orphan payload, which readers ignore. | opens |
| `can_unclosed_cut_frame_id_only.mf4` | 5358 | `0x6F60238A` | Orphan payload plus the frame record's record ID only. | opens |
| `can_unclosed_cut_mid_frame.mf4` | 5372 | `0x8CFA3A9C` | Orphan payload plus 15 bytes of the 24 byte frame record (inside its CAN_DataFrame). | opens |
| `can_unclosed_cut_frame_missing_last_byte.mf4` | 5380 | `0x3A13A846` | The last frame's records are complete but for the last byte of the frame record (48 of 49 bytes). | opens |
| `can_mixed.mf4` | 13553 | `0xBACE4AF8` | Values and three raw CAN sensors in one file (record IDs 1; 2,3; 4; 5,6; 7,8; 9): an analog input, a CAN sensor on bus 0, an indicator, one on bus 1 (BusChannel 2), one with no bus (BusChannel 0), a virtual sensor; 30 ticks of 100 ms. Records of all groups interleave in time order. | opens |
| `can_mixed_unclosed.mf4` | 13553 | `0x063141AA` | The same session without StopLogging(). | opens |
| `conversions.mf4` | 8656 | `0x4FA6704D` | Ten sensors for the conversion and comment paths: linear, power (x ^ 2), functions, a not convertible expression (comment 'expression: ...' instead of a conversion), an indicator with a comparison, an expression with the raw value off, a sensor with no name, unit or description, a UTF-8 and XML-special name and description, a sensor disabled and one not logged (no channel group). 12 ticks of 50 ms. | opens |
| `empty.mf4` | 3112 | `0x265DC479` | A session started and stopped before its first tick: the three sensors of values.mf4, closed, no record, every count 0. | opens |
| `log_data_20251022T061059Z_3071.mf4` | 8256 | `0x0D5A5DD0` | First file of a rotated session: ticks 1 to 22 of 100 ms, closed by the rotation (StopLogging()) when it reached the size limit. | opens |
| `log_data_20251022T061101Z_5128.mf4` | 6811 | `0xE3D9ED1D` | Second file of the session: opened at the time of tick 22 (the last tick of the first file), ticks 23 to 40, closed by the end of the session. | opens |
| `rotation_second_unclosed.mf4` | 6811 | `0x6D41DE3D` | The second file as a power loss leaves it (the session's last file never closed). | opens |
| `values.mf4` | 4624 | `0x1D40D9C8` | Three sensors (an analog input with sqrt(x) * 2 + 1 and its raw value, an indicator, a virtual sensor with Only new data), 40 ticks of 100 ms, one session closed by StopLogging(). | opens |
| `values_unclosed.mf4` | 4624 | `0x49D09FE6` | The same session as values.mf4 without StopLogging(): the file as it is after a power loss (UnFinMF, flags 0x01 0x04 0x20, DT length and cycle counts not patched). | opens |
| `values_unclosed_cut_boundary.mf4` | 4610 | `0x24E1F701` | Cut exactly at a record boundary: the last record is gone whole, nothing is partial. | opens |
| `values_unclosed_cut_id_only.mf4` | 4611 | `0x4B2F5FC0` | The last record has only its record ID (1 byte of 14). | opens |
| `values_unclosed_cut_mid_time.mf4` | 4617 | `0xFDFE9CF3` | The last record is cut inside its Float64 time (ID and 6 of the 8 time bytes). | opens |
| `values_unclosed_cut_mid_value.mf4` | 4621 | `0x282EBF0C` | The last record is cut inside its Float32 value (ID, time and 2 of the 4 value bytes). | opens |
| `values_unclosed_cut_no_invalidation.mf4` | 4623 | `0xB6B2DBC0` | The last record lacks only its invalidation byte (13 of 14 bytes). | opens |
| `values_unclosed_cut_mid_raw.mf4` | 4607 | `0x030D6B3F` | The record before the last (the analog sensor's, 18 bytes with a raw value) is cut inside its raw value (15 of 18 bytes). | opens |
| `values_unclosed_header_only.mf4` | 3120 | `0x2510F7B0` | Only the header: a log that was started and stopped before its first record. | opens |
| `values_cut_in_header.mf4` | 1560 | `0x3B8A4604` | Cut inside the header (half of it): not a readable MDF file, the reader must say it is cut short. | **refuses** |
| `values_closed_cut_mid_record.mf4` | 4617 | `0xA65B0966` | The closed file cut inside its last record, as a download in progress leaves it (identifier MDF, DT length and cycle counts final). | **refuses** |
| `values_closed_cut_header_only.mf4` | 3120 | `0xD567AF55` | The closed file cut right after the header (all counts final, no record yet). | **refuses** |
| `wide_ids.mf4` | 78735 | `0x16536E74` | 127 value sensors and a raw CAN sensor: 128 enabled sensors, so the data group has a record ID of 2 bytes (value groups 1..127, the VLSD group 128, the CAN group 129). Synthetic: a unit logs at most 64 sensors. | opens |

Families (one session each, `fixtures.json` `families`): `values` (fixture 1 and its unclosed and cut copies),
`can` (fixture 2 and its copies), `rotation` (fixture 4), and the extras `conversions`, `can_mixed`,
`wide_ids` and `empty`. The roles: `closed` (StopLogging() ran), `unclosed` (the stream just before
StopLogging(): a file after a power loss), `unclosed_cut` / `closed_cut` (a prefix of one of those), `damaged`.

## How they were made (to make them again)

**Where.** The rt_daq container `rt_daq_devcontainer-zephyr-dev-1`, scratch folder
`/home/ubuntu/zephyr/workspace/scratch_mdf_fixtures` (kept; its `build/` was deleted at the end):

- `app/` = `sources/app/` here: `src/main.cpp` is the generator; `CMakeLists.txt` includes
  `/home/ubuntu/zephyr/workspace/project/modules/eerie_leap_rt_core/tests/rt_core_test.cmake` (as rt_core's own
  `tests/unit/domain` does) so rt_core is linked **read-only from the user's checkout** as a Zephyr module;
  `prj.conf`, `Kconfig` and `boards/` are copies of `tests/unit/domain`'s, plus three lines in `prj.conf`
  (a 32 MB libc malloc arena and 64 KB stacks, because the generator keeps copies of whole files in memory);
  `src/memory_stream_buf.h` is a copy of rt_core's `tests/common/memory_stream_buf.h`.
- rt_core: `542777406e075b1138f6c3d68bfc6229983e5486` (`5427774`, 2026-10-29, "tests: the publisher test's two-tick
  bound is computed in int64_t"), `git status` clean before and after. Zephyr `v4.5.0-962-g0534e997eac`,
  board `native_sim` (32-bit, host gcc 13.3.0, `ZEPHYR_TOOLCHAIN_VARIANT=host`, host libc), west 1.5.0.
  The rt_daq checkout (`50ebb7b` plus the user's uncommitted work) was not used beyond reading the product ID and board name.

```sh
# in the container, as ubuntu (free -g was checked first: > 6 GB available; one build, parallelism 6)
SCR=/home/ubuntu/zephyr/workspace/scratch_mdf_fixtures
cd $SCR
PATH=/home/ubuntu/zephyr/.venv/bin:$PATH CMAKE_BUILD_PARALLEL_LEVEL=6 \
  west build -b native_sim -d $SCR/build $SCR/app          # ~1 min cold, seconds when only main.cpp changes
mkdir -p $SCR/out && MDF_FIXTURE_OUT=$SCR/out $SCR/build/zephyr/zephyr.exe   # writes 38 files in < 1 s

# on Windows, from the fixtures folder
docker cp rt_daq_devcontainer-zephyr-dev-1:/home/ubuntu/zephyr/workspace/scratch_mdf_fixtures/out/. work\gen
venv\Scripts\python.exe tools\extract.py work\gen .      # copies the .mf4 files and manifests, writes fixtures.json
```

The output is **deterministic**: a second run produced 38 of 38 byte-identical files (the manifests included).
(Zephyr/`west` finds its workspace from the scratch folder because it is under `/home/ubuntu/zephyr/workspace`.)

**What the generator does.** It builds a `Mdf4LoggerSensorReading` from a `LoggingConfiguration`, a vector of
`Sensor`s and a `LoggerMetadata`, and calls it exactly as `LogWriterService::ProcessTick` does:

1. `StartLogging(stream, start_time)` on a `MemoryStreamBuf` (a seekable in-memory `std::streambuf`, as rt_core's unit tests use);
2. at every tick, `LogReading(tick_time, reading)` once for each value sensor's latest reading (in the sensors' order, skipping a sensor with no reading yet) and then once for each CAN frame queued since the last tick (in arrival order), each frame as a reading with its own timestamp and `can_frame` set;
3. a rotation as `RotateFile()` does it: after the tick that leaves the open file at or above the size limit, the next tick first calls `StopLogging()` (finalizes) and then `StartLogging(new stream, last_tick_time)` on the same logger, then logs its readings into the new file;
4. `StopLogging()` at the end. `values_unclosed.mf4` and the other `unclosed` files are the stream **just before** that call.

Ticks are `k * interval + ((37 * k) mod 9) * 100 us` after the start (the unit clock ticks every 100 us), k = 1, 2, ...
Times in the manifests are microseconds after the session's first file start (`origin_us`, the unit clock:
`RtcProvider`'s fixed date 1761106217000 ms plus the uptime; 42.3175 s for `values`, one more hour for each next family).
The only thing not run through the writer is the byte limit of the rotation (see below).

**Header metadata (all families).** `device_name` `esp32s3_pm_hw_02` (the EL PM100's board), tool `rt_daq` / vendor `Eerie Leap` /
version `1.3.0`, `product_id` 50177, `hw_version` 33619968 (2.1.0), `sw_version` 16973824 (1.3.0), `logging_interval_ms` = the interval.
`device_id` and `sensors_configuration_hash` differ by family (a made-up constant, **not** the CRC of a real configuration; 0 in `conversions`):
values 5 / 1581945283 (0x5E4A91C3), can 0 / 0x77C0FFEE, rotation 9 / 0x1B2C3D4E, can_mixed 11 / 0xA5A5A5A5, conversions 3 / 0, wide_ids 1 / 0x0BADF00D, empty 2 / 0.

### Fixture 1: `values.mf4` (three sensors, 40 ticks of 100 ms, 4.0 s)

| sensor | type | name / unit / description | flags | channels |
| --- | --- | --- | --- | --- |
| `oil_pressure` | PHYSICAL_ANALOG | Oil pressure / bar / sump, before the filter | expression `sqrt(x) * 2 + 1`, Raw value on | `oil_pressure`, `oil_pressure.raw` (CC type 3 algebraic `sqrt(X) * 2 + 1`) |
| `ignition` | PHYSICAL_INDICATOR | Ignition / (none) / key switch | no expression (Raw value is on, so no raw channel) | `ignition` |
| `engine_power` | VIRTUAL_ANALOG | Engine power / kW / torque times speed | Only new data on | `engine_power` |

Record IDs 1, 2, 3 (1 byte); records of 18, 14 and 14 bytes. Readings fed (`manifests/manifest_values.json`):

- `oil_pressure`: a new sample every 40 ms (13 ms + 40 ms * j; raw volts `0.75 + 0.0625 * triangle(j) + noise / 512`, exact in float; value = float(`sqrt(raw) * 2 + 1`)). Only the latest sample at each tick is logged, so 40 records. A stall from 1.55 s to 1.9 s: the tick at 1.7008 s holds the sample of 1.533 s and **rewrites it at the tick time** (record at 1.7008); one sample taken at 1.653 s is committed only at 1.745 s, and the logger moves it to **1 us after the repeat** before it (record at 1.700801). Invalid readings: 0.573 s an expression failure (`EXPRESSION_FAILED`, the earlier value still attached: value 0 and its bit set, raw valid), 2.493 s no raw value (raw 0 and its bit set), 3.493 s neither (both bits).
- `ignition`: a reading only when it changes: 0 at 5 ms, 1 at 352 ms, 0 at 1.208 s, 1 at 1.633 s, a `READER_FAILED` error without a value at 2.410 s, 1 at 2.950 s, 0 at 3.290 s. Held between: every tick writes a record (40), a new reading at its own time, a held one at the tick time; the error is **held invalid** at 2.41, 2.6008, 2.7, 2.8001, 2.9002 s.
- `engine_power`: a value every 250 ms (17 ms + 250 ms * j, 40 + 2.5 * triangle(3j)); the one at 1.517 s fails (`EXPRESSION_FAILED`, value retained, written 0 and invalid). Only new data: 16 records, none for the ticks that hold a sample.

### Fixture 2: `can.mf4` (a raw CAN frame sensor, 30 ticks of 100 ms)

One sensor `can_raw` (CANBUS_RAW, bus channel 0 so `BusChannel` is 1; name "Raw frames", description "all traffic on CAN 0"). Record IDs: VLSD group 1, `CAN_DataFrame` group 2.
38 frames: standard IDs (0x123 8 bytes, 0x7FF with no payload, 0x100 transmitted, 0x001 one byte, 0x080), extended IDs (0x18FEF100, 0x1FFFFFFF, 0x18DAF110 as the 64-byte FD frame, 0x1ABCDEF0 as an FD frame of 7 bytes, 0x18DB33F1),
CAN FD frames of 10, 12, 16, 20, 24, 32, 48 and **64** bytes (with and without BRS, one transmitted), two frames with the **same timestamp** (34 ms), a 10-byte FD frame whose **DLC is 9 but DataLength 10**
(`DLC` is the rounded-up code, `DataLength` the real length; the payload record is 1 + 4 + 10 bytes), a periodic 0x180 counter frame every 50 ms from 1.3 s to 2.3 s, and a last frame of 20 bytes at 2.94 s.
Per frame the file holds a payload record (`[id][u32 length][bytes]`) then the frame record (24 bytes: id, time, 15-byte `CAN_DataFrame`: BusChannel @0, ID(29)+IDE(bit 31) @1, flags @5 (DLC bits 0-3, Dir 4, EDL 5, BRS 6, ESI 7), DataLength @6, u64 VLSD offset @7). `ESI` is always 0.
The VLSD offsets are byte offsets into the group's payload records (4 + length each); the group's byte count is 447.

### Fixture 3: unclosed and cut copies

`values_unclosed.mf4` and `can_unclosed.mf4` are the streams of fixtures 1 and 2 before `StopLogging()`: identifier `UnFinMF ` with flags **0x25**
(0x01 cycle counts, 0x04 last DT length, 0x20 VLSD byte count), the DT block's length field **24** (its header only), every cycle count 0, the VLSD
byte count 0; the records are all there (the best case of a power loss: nothing lost). The `*_cut_*` files are prefixes of them, cut as `fixtures.json` `cut` says
(`length`, which record counted from the end, how many of its bytes are kept):

- `values_unclosed_cut_*`: on a record boundary; the last record (14 bytes) with 1 byte (its ID), 7 (inside the time), 11 (inside the value), 13 (all but the invalidation byte); the record before it (18 bytes, raw channel) with 15; `values_unclosed_header_only.mf4` (3120 bytes: the header, no record); `values_cut_in_header.mf4` (half of the header: not a readable file).
- `can_unclosed_cut_*` (the last pair is a 25-byte payload record and a 24-byte frame record): before the pair; 1 byte; 3 (inside the length); 5 (length 20 and no payload); 15 (**mid-payload**); 25 (**orphan payload**: the payload record whole, its frame record absent); 26 (plus the frame record's ID); 40 (inside the frame record); 48 (all but its last byte).
- `values_closed_cut_*` are prefixes of the **closed** `values.mf4` (identifier `MDF`, the DT length and cycle counts final and longer than the file): what a download in progress of a finished log looks like.
- `independent_parse` in `fixtures.json` says, for every file, how many whole records it holds per record ID, why a reader stops and how many bytes follow the last whole record.

### Fixture 4: rotation, `log_data_20251022T061059Z_3071.mf4` + `log_data_20251022T061101Z_5128.mf4`

Names as the writer makes them (`LogFileNaming`, start on the unit clock, a made-up 4-digit suffix). The session logs the three sensors of fixture 1 and a raw CAN sensor `can_raw` on **bus 1** (`BusChannel` 2; record IDs 1, 2, 3 and VLSD 4 / CAN 5),
`engine_power` sampling at 150 ms + 250 ms * j. Ticks 1 to 40; the size limit is **8256 bytes** (the file's size after tick 22, found by a first pass without a limit; the rotation is a byte limit as `SetSizeLimitsForTesting()` gives a test, `max_log_size_mb` itself is whole megabytes). How they join (all in `manifests/manifest_rotation.json` `rotation` and the files):

- File 1 holds ticks 1 to 22 (88 records, ends at the tick of 2.2004 s: `first_end_rel_us` 2200400, `end_ms` 1761113461517 as `LogEndTimes` would keep it). File 2's header start is **exactly that tick**: `hd_start_time_ns` 1761113461517900000 = file 1's start + 2.2004 s; so there is neither a gap nor an overlap on the unit clock. Its file name's second is 01, file 1's 59.
- File 2's times restart at 0 (relative to its own start): the records follow on from file 1's last tick. File 1's last records: ignition 2.2004 s (held, at the tick), `can_raw` 2.2 s, `oil_pressure` 2.173 s, `engine_power` 2.15 s.
- File 2's **first records**: `ignition` at **t = 0.0** (the sample held since 1.633 s, older than the file, is clamped to the file start; it is **not** a new sample and equals file 1's last ignition record), `engine_power` at **t = 0.0** with 60.0 (the 2.15 s sample, **written again although Only new data is on**, because the new file forgets what it wrote), `oil_pressure` at 0.0926 s, `can_raw` at 0.0496 s (the frame of 2.25 s) with VLSD offset **0** (offsets, cycle counts and byte counts restart in each file; each frame is in exactly one file).
- Record IDs, channel groups and metadata are the same in both files (the layout is rewritten at each start, with the configuration in effect then).
- `rotation_second_unclosed.mf4` is file 2 as a power loss leaves it.

### Extras

- `conversions.mf4`: ten sensors for the conversion and comment paths, 12 ticks of 50 ms. `lin` (`x * 2 + 1` becomes `X * 2 + 1`), `pow2` (`x ^ 2 + 3` becomes `(X * X) + 3`), `funcs` (`abs(X) - log(X) + sin(X) * cos(X) + tan(X) / 10 + sqrt(X)`), `noconv` (`x + other_sensor`: no conversion, the raw channel's comment is `expression: x + other_sensor`), `ind_expr` (an indicator with `x > 0.5`: the same comment, XML-escaped as `x &gt; 0.5`), `noraw` (an expression with Raw value off: no raw channel), `bare` (no name, unit or description: no comment, no unit), `utf8` (`Température d'huile`, unit `°C`, description with `& < > " —`: UTF-8 and the five XML entities), and two that get no channel group (`off_sensor` disabled in the sensors configuration, `off_logging` disabled in the logging configuration).
- `can_mixed.mf4` / `can_mixed_unclosed.mf4`: values and three raw CAN sensors in one file, 30 ticks: record IDs `oil_pressure` 1; `can_a` VLSD 2 + frames 3 (bus 0); `ignition` 4; `can_b` 5 + 6 (bus 1); `can_unknown` 7 + 8 (**no CAN source: `BusChannel` 0**); `engine_power` 9. All groups' records interleave in time order, each frame preceded by its payload.
- `wide_ids.mf4`: 127 value sensors and a CAN sensor = 128 enabled sensors, so the data group's **record ID is 2 bytes** (`GetRecordIdSizeBytes`: groups 1 to 127, VLSD 128, CAN 129). Synthetic: `SensorLimits::MAX_COUNT` is 64 by default.
- `empty.mf4`: started and stopped before the first tick: closed, every count 0, the DT length 24.

## What the bytes show (checked against the plan's §1.1, which they bear out)

Identification `MDF     ` / `4.10    ` / `EL      ` / version number 410; flags at bytes 60-61. HD: start in ns, time zone, DST and `time_flags`/`time_class`/`flags` all 0; one FH with `<?xml ...?><FHcomment><TX>created</TX><tool_id>rt_daq</tool_id>...`;
HD comment `<HDcomment><TX>Eerie Leap sensor log</TX><common_properties><e name="device_name">...` (no XML declaration), seven properties in that order. One DG, one DT as the header's last block, records appended after it.
Each sensor: a CG named after the id, an SI (I/O device, "Eerie Leap Sensor"), master `Timestamp` (float64, unit `s`, sync type time), value channel float32 (`flags` 2: invalidation bit 0), raw channel (invalidation bit 1);
channel comments are MD blocks `<CNcomment><TX>name: description</TX></CNcomment>`, XML-escaped. The raw bytes of every comment and the block tree are in each closed file's `walk` in `fixtures.json`.
`independent_parse` and `verification` say that the records read by `tools/records.py`, the readings fed (`manifests`) and asammdf agree for every file asammdf opens (values, invalid flags, frames, record counts).
Converted raw values equal the value channel to float32 rounding (`converted_vs_value_channel.max_abs_diff` <= 2.4e-7).

## asammdf 8.8.27 (Python 3.12, numpy 2.5, numexpr 2.14)

How it was read: `MDF(path)`, then per channel `mdf.get(group=i, index=j, raw=True, ignore_invalidation_bits=True)` for the stored samples with their bits and `mdf.get(group=i, index=j, ignore_invalidation_bits=True)` for the converted ones; for CAN `mdf.get("CAN_DataFrame", group=i)`. Its `asammdf` logger was captured at DEBUG (only INFO and WARNING messages occur).

- **Gotcha: a plain `get()` drops the invalid samples** and returns `invalidation_bits = None` (`oil_pressure`: 38 of 40 samples, `ignition`: 35 of 40). The invalid ones, with their bits, only come with `ignore_invalidation_bits=True`; `default_get_count` in `fixtures.json` is what the plain call returns. Each channel is filtered by its own bit (`oil_pressure` and `oil_pressure.raw` drop different samples).
- **An unclosed file (`UnFinMF`, flags 0x25) opens.** `MDF()` copies it to a temporary file and logs four INFO messages, then reads it as finalized:
  `Unfinalised file <name>: Update of cycle counters for CG/CA blocks required`, `Unfinalised file <name>: Update of length for last DT block required`,
  `Unfinalised file <name>: Update of cg_data_bytes and cg_inval_bytes in VLSD CG block required`, `Attempting finalization of <name>`. It extends the last DT block to the end of the file and counts the records itself
  (`cycles_nr` becomes the number of whole records, e.g. 40/40/16 for `values_unclosed.mf4`), so a **cut inside a record loses just that record** and a record cut anywhere is never half-read. It does **not** recount the VLSD group: its `cycles` stay 0 (the frames are read all the same, by their offsets). An **orphan payload** is ignored (`can_unclosed_cut_orphan_payload.mf4`: 37 frames). The original file is not modified.
- **A header-only unclosed file opens** with every group at 0 cycles.
- **A cut inside the header is refused:** `MdfException: File is a corrupted MDF file - Invalid CG block address` (`values_cut_in_header.mf4`).
- **A cut *closed* file is refused,** although everything in it before the cut is whole: `MdfException: Incomplete block at 0xc18 exceeds the file size 0x1209. values_closed_cut_mid_record.mf4 might be corrupted or partially written.` (also logged as WARNING; `values_closed_cut_header_only.mf4`: `... exceeds the file size 0xc30 ...`). It trusts the DT block's length. A reader that must show a download in progress has to read to the end of the file in that case too, not only when flag 0x04 is set.
- Converted samples are `float32` (numexpr on a float32 array); the formulas `X * 2 + 1`, `(X * X) + 3`, `sqrt(X) * 2 + 1` and the function formula evaluate without complaint. Non-convertible expressions are only comments (asammdf shows `<CNcomment><TX>expression: x + other_sensor</TX></CNcomment>` as the comment string).
- The comment strings asammdf returns are the MD blocks' XML as stored (channels, groups); its `header.comment` and file-history comments are re-serialized with newlines (the raw text is in `walk`). `header.start_time` is `2025-10-22 04:10:59.317500+00:00` (the unit clock's fixed date, not a real date).
- CAN: `mdf.get("CAN_DataFrame", group=i)` returns a record array with `BusChannel, ID, IDE, DLC, Dir, EDL, BRS, ESI, DataLength, DataBytes`; `DataBytes` is padded to 64 bytes with zeros, `DataLength` is the real length (`fixtures.json` `frames[].data` is cut to it, hex). The VLSD group shows as an unnamed group with `flags` 1.

## Which file exercises what

- finalized layout, algebraic conversion, comments and units, invalid value / raw / both, held sample rewritten at the tick time, the 1 us move: `values.mf4`; more conversions, an unconvertible expression, escaping, UTF-8, absent channel groups: `conversions.mf4`
- VLSD + `CAN_DataFrame`, IDE / FD / BRS / Dir, DLC versus DataLength, a 64-byte payload, a shared timestamp: `can.mf4`; several CAN groups, `BusChannel` 0, interleaved groups: `can_mixed.mf4`
- unfinalized identifier and flags, DT length 24, counts 0, reading to the end of the file: `*_unclosed.mf4`; stopping at the last whole record, an orphan payload: `*_cut_*`
- a finished file that is cut short (DT length and counts past the end of the file): `values_closed_cut_*`; a file that is not readable (cut in the header): `values_cut_in_header.mf4`
- a session in two files, the join, the restart of times, VLSD offsets and counts: the `log_data_*` pair
- a 2-byte record ID: `wide_ids.mf4`; no record at all: `empty.mf4`

## Caveats and things that did not go as first expected

- The reading schedule only logs a tick's **latest** sample, so my first placement of the invalid readings put them where no tick saw them; they sit on ticks now (documented above). Everything asammdf shows matches what was fed (`verification.matches_feed` is true for all 28 files it opens).
- The rotation is the service's call sequence (`RotateFile`) driven on the logger, not the `LogWriterService` itself (a threaded service over a FAT volume); the byte limit is applied by the generator the way `SetSizeLimitsForTesting()` would.
- `sensors_configuration_hash` values are placeholders, hw/sw versions and the board are the PM100's naming with made-up versions.
- The clock is the unit's (`RtcProvider`: a fixed date plus uptime), so every start time is `2025-10-22` plus the uptime I chose; the times in the master channel are exact decimal multiples of 100 us only up to float64 rounding (as the unit's).
- A file written by a different rt_core commit may differ in bytes; the CRC-32s here belong to `5427774`.
