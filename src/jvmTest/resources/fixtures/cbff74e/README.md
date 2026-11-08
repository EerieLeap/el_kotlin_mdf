# MDF reader fixtures from rt_core cbff74e: what the firmware writes since the log-plots work, with asammdf's values

Made 2026-11-07 for the app's log-plots-firmware plan (G-17) and the firmware's `LOG_PLOTS_FIRMWARE_PLAN.md` (F-2 and F-5 to
F-9). Every `.mf4` here was written by rt_core's `Mdf4LoggerSensorReading` (through its `Mdf4File` writer) on `native_sim`, from
sensors, a logging configuration and readings made for the test; every attached configuration was serialized by rt_core's own
parsers and zcbor serializer. Nothing is stubbed: each byte comes out of the firmware's code. They are not logs of a real unit.
The first fixtures (rt_core `5427774`, one folder up) stay as they are; this folder is a set of its own.

- `*.mf4` (6 files, table below), each with its size and CRC-32 (zlib's) in `fixtures.json`
- `fixtures.json`: per file its size, CRC-32, what it is, an independent walk of its blocks (now with the attachments, the
  events, the channels' limits and comment properties, and what follows the data), and asammdf 8.8.27's reading of it (the
  samples as in the first set, and the header's properties and time class, each channel's source, limits and flags, the attachments
  with what `extract_attachment()` gives back, and the events). `families` holds the configuration of each session once; a file
  names its `family`.
- `manifests/manifest_<family>.json`: the generator's own record of a session: its sensors (with the source and limits the plan
  says each channel gets, worked out from the specification and not from the logger), the header's values, **the attached
  configurations as hex**, every reading fed at every tick and the logger's answer, what `StopLogging()` was told for each file
  (`end`) and the byte span of every record.
- `sources/app/`: the generator (a ztest app, `src/main.cpp`), with `CMakeLists.txt`, `prj.conf`, `Kconfig` and board files;
  `sources/build_and_run.sh`: the commands.
- `tools/`: `extract.py` (builds `fixtures.json` and checks what asammdf reads against the manifest), `mdfwalk.py` (an independent
  MDF 4 block walker), `records.py` (an independent parser of the unsorted records) and `check_attachments.py` (decodes the attached
  CBOR with cbor2 and checks it against the sensors that were logged). `venv/` (asammdf 8.8.27, cbor2) and `work/` are not
  deliverables.

## The files

| file | bytes | CRC-32 | what it is | asammdf |
| --- | ---: | --- | --- | --- |
| `values.mf4` | 18808 | `0xC958E89D` | Ten sensors on a **set clock** (2026-11-07T10:00:00Z, time class 10), 40 ticks of 100 ms, the four configurations attached, closed by `StopLogging()` with the stop event (overruns 3, frames dropped 11). | opens |
| `values_unclosed.mf4` | 18472 | `0xD944A454` | The same session without `StopLogging()`: the stream just before the stop (UnFinMF, flags 0x25, no event); the attachments are in its header. | opens |
| `rotation` files: `log_data_20251022T064059Z_4417.mf4` | 12000 | `0x0AC43FA8` | First file of a session on the **unit's clock** (clock `uptime`, class 0), `file_index` 0, `start_cause` `trigger`, ticks 1 to 22, closed by the rotation: the `Next file` event (`rotated`, overruns 2, frames dropped 5). | opens |
| `log_data_20251022T064101Z_9023.mf4` | 10560 | `0x064741F4` | Second file of the session: same `session_id`, `file_index` 1, opened at the time of tick 22, ticks 23 to 40, stopped (`stop_reason` 0, overruns 1, frames dropped 2). | opens |
| `nospace.mf4` | 7976 | `0x50C74FDE` | Three sensors on a set clock (2026-11-07T12:15:30Z), `start_cause` `command`, 25 ticks, stopped for lack of space: `stop_reason` 2, the event's cause is error (`Logging stopped: no space`). | opens |
| `can.mf4` | 7976 | `0x5D44F399` | One raw CAN frame sensor (CANBUS_RAW, bus 0) on the unit's clock, `start_cause` `shell`, the 38 frames and 30 ticks of the first `can.mf4`, now with the CG comment's properties, the attachments and the stop event (frames dropped 4). | opens |

(`can.mf4` and `nospace.mf4` are the same size by chance; their bytes differ.)

## How they were made (to make them again)

**Where.** The rt_daq container `rt_daq_devcontainer-zephyr-dev-1`, scratch folder
`/home/ubuntu/zephyr/workspace/scratch_mdf_fixtures_cbff74e` (under the west workspace, so `west` finds it; kept, its `build/`
deleted at the end). The user's checkouts were only read: a clone of the user's rt_core was made in the scratch folder
(`git clone` from `/home/ubuntu/zephyr/workspace/project/modules/eerie_leap_rt_core`, `checkout cbff74e`), and its submodules
were cloned from the user's checkout's copies into the clone's `modules/` at the commits rt_core records:
`expression_engine` `17faeaf2`, `zephyr_lua` `c5d257c9` and its `lua` `6e22fedb`.

- `app/` = `sources/app/` here: `src/main.cpp` is the generator; `CMakeLists.txt` includes
  `<scratch>/rt_core/tests/rt_core_test.cmake` as rt_core's own `tests/unit/domain` does, so the clone is linked as a Zephyr
  module; `prj.conf`, `Kconfig` and `boards/` are `tests/unit/domain`'s at cbff74e (identical to the copies used for the first
  fixtures) plus the three lines of the first generator (a 32 MB libc malloc arena and 64 KB stacks);
  `src/memory_stream_buf.h` is a copy of rt_core's `tests/common/memory_stream_buf.h` (unchanged).
- rt_core: `cbff74e0f8ef2029c72c919dd80d02f37d098877` (`cbff74e`, 2026-11-07, "fix: the branch's clang-tidy findings, and the
  end-time tests build on every target (timeutil_timegm)"), `git status` clean. Zephyr `v4.5.0-962-g0534e997eac`, board
  `native_sim` (32-bit, host gcc 13.3.0, `ZEPHYR_TOOLCHAIN_VARIANT=host`), west 1.5.0.

```sh
# in the container, as ubuntu (free -g was checked first: 35 GB available; one build, parallelism 4)
SCR=/home/ubuntu/zephyr/workspace/scratch_mdf_fixtures_cbff74e
PATH=/home/ubuntu/zephyr/.venv/bin:$PATH CMAKE_BUILD_PARALLEL_LEVEL=4 ZEPHYR_TOOLCHAIN_VARIANT=host \
  west build -b native_sim -d $SCR/build $SCR/app          # a few minutes cold, seconds when only main.cpp changes
mkdir -p $SCR/out && MDF_FIXTURE_OUT=$SCR/out $SCR/build/zephyr/zephyr.exe   # writes 10 files in < 1 s

# on Windows, from the fixtures folder (venv: Python 3.12, asammdf==8.8.27, cbor2)
docker cp rt_daq_devcontainer-zephyr-dev-1:$SCR/out/. work\gen
venv\Scripts\python.exe cbff74e\tools\extract.py work\gen cbff74e    # copies the files and manifests, writes fixtures.json
venv\Scripts\python.exe cbff74e\tools\check_attachments.py cbff74e\manifests
```

`sources/build_and_run.sh` has the whole sequence, the clone included. The output is **deterministic**: a second run produced 10 of
10 byte-identical files (the manifests included). Per-file sizes and CRC-32s above belong to `cbff74e`.

**What the generator does.** As the first one did, it builds a `Mdf4LoggerSensorReading` from a `LoggingConfiguration`, a vector
of `Sensor`s and a `LoggerMetadata`, and calls it as `LogWriterService::ProcessTick` does, now with the API of cbff74e:

1. `StartLogging(stream, LogFileStart{ time, wall_clock_offset })`: the start on the unit's clock, and the offset to real time
   when the clock is set (then the header start is `time + offset`, the time class 10 and `clock` `set`; the records stay
   relative to `time`);
2. at every tick `LogReading(tick_time, reading)` for each sensor's latest reading and then for each queued CAN frame;
3. a rotation as `RotateFile()` does it: the next tick first calls `StopLogging(LogFileEnd{ time, no reason, overruns, frames_dropped })`
   and then `StartLogging()` at the last tick's time on the same logger, which numbers the files of its `LogSession`;
4. `StopLogging(LogFileEnd{ last tick, reason, overruns, frames_dropped })` at the end. `values_unclosed.mf4` is the stream
   **just before** that call.

A `LogSession` (an id and the start cause) is shared by the files of a session, as `LogWriterSupervisor` makes it; the
`LoggerMetadata` carries the unit's UID (`2864434397`, 0xAABBCCDD, the plan's example), a boot ID, the CDMP device ID and
the attachments. Ticks are `k * interval + ((37 * k) mod 9) * 100 us` after the start, as before.

**The attachments** are made as `LoggingController`'s `MakeAttachmentsProvider` makes them: for each of ADC (type 2), CAN bus
(3), sensors (4) and logging (5), in that order, `adc_config.cbor` etc., MIME type `application/cbor`, comment
`Eerie Leap <label> configuration` with the properties `configuration_type` and `crc` (zephyr's `crc32_ieee` of the bytes). The
bytes are what rt_core serializes: `AdcConfigurationCborParser`, `CanbusConfigurationCborParser`, `SensorsCborParser`
(validated with the EL PM100's 4 ADC and 8 GPIO channels, no SD card to check script paths against) and
`LoggingConfigurationCborParser::Serialize`, then `CborSerializer<T>::Serialize`. The sensors configuration is the one of the
sensors the logger was given, and `sensors_configuration_hash` in the header is its CRC-32, as the supervisor takes it from the
stored configuration's info. `check_attachments.py` decodes the sensors and logging bytes with cbor2 and finds the sensors that
were logged. The CAN bus configuration is a made-up one (channel 0 CAN FD with ENGINE 0x316 / RPM and BRAKES 0x2A0 / BRAKE,
channel 1 classical with AUX 0x100; `values` and `nospace` also send EL_OUT 0x7E0), the ADC configuration is the default one for
four channels.

## Header (all families)

`device_name` `esp32s3_pm_hw_02`, tool `rt_daq` / vendor `Eerie Leap` / version `1.3.0`, `product_id` 50177, `hw_version`
33619968 (2.1.0), `sw_version` 16973824 (1.3.0), `logging_interval_ms` 100, then F-2's and F-5's, in file order:
`unit_uid`, `boot_id`, `session_id`, `start_cause`, `file_index`, `clock`. The header comment lists
`device_name, product_id, device_id, hw_version, sw_version, sensors_configuration_hash, logging_interval_ms, unit_uid, boot_id,
session_id, start_cause` and then the file's own `file_index, clock`.

| family | `device_id` | `boot_id` | `session_id` | `start_cause` | `clock` | HD start | time class |
| --- | ---: | ---: | ---: | --- | --- | --- | ---: |
| values | 5 | 913024577 | 40211837 | smp | set | 2026-11-07T10:00:00Z | 10 |
| can | 0 | 1170331054 | 61880213 | shell | uptime | 2025-10-22T05:10:59.3175 (the unit clock's fixed date plus uptime) | 0 |
| rotation | 9 | 1170331054 | 77310215 | trigger | uptime | 2025-10-22T06:40:59.3175 | 0 |
| nospace | 5 | 2216008731 | 5310099 | command | set | 2026-11-07T12:15:30Z | 10 |

`sensors_configuration_hash`: values 3715589693, can 1539045204, rotation 915193424, nospace 2927102476; each the CRC-32 of that
family's attached `sensors_config.cbor` (the `crc` property of the attachment).

## The sensors of `values.mf4`

Record IDs 1 to 10 (one byte), a group for each sensor named after its ID, in this order. The readings are fed by hand (the
first three are the first set's, so are their invalid readings); they are not computed from the expressions or tables, which only
describe the sensors. The expected source and limits are in the manifest (`expected_source`, `expected_limits`) and are what
`extract.py` checks asammdf's reading against.

| sensor | type | source (SI name, type) | limits | other |
| --- | --- | --- | --- | --- |
| `oil_pressure` | PHYSICAL_ANALOG, linear table 0.5 V to 4.5 V, `sqrt(x) * 2 + 1`, Raw value | `ADC 1`, I/O device | none (an expression) | `.raw` channel with the algebraic conversion; no source or limits on it |
| `ignition` | PHYSICAL_INDICATOR | `DI 0`, I/O device | 0 and 1 | |
| `engine_power` | VIRTUAL_ANALOG `oil_pressure * rpm / 100`, Only new data | `virtual`, tool | none | 16 records |
| `coolant_temp` | PHYSICAL_ANALOG, linear table (given unsorted), no expression | `ADC 0` | **-20 and 140** (the table's lowest and highest value) | one invalid sample (the sender failed) |
| `fuel_level` | PHYSICAL_ANALOG, cubic spline table | `ADC 2` | none (a spline can overshoot) | |
| `rpm` | CANBUS_ANALOG, bus 0, 0x316, `RPM` | `CAN 1 · 0x316 · RPM` (the middle dot is U+00B7), bus, CAN | none | |
| `brake` | CANBUS_INDICATOR, bus 0, 0x2A0, `BRAKE` | `CAN 1 · 0x2A0 · BRAKE` | 0 and 1 | |
| `boost` | USER_ANALOG, `scripts/sensors/boost.lua` | `script`, tool | none | the logger never runs the script |
| `shift_light` | USER_INDICATOR, `scripts/sensors/shift.lua` | `script`, tool | none (a script produces it) | the same SI block as `boost`'s |
| `launch` | CONTROL_INDICATOR | `control`, user | 0 and 1 | |

Every group keeps its acquisition source `Eerie Leap Sensor` (I/O device). Every value channel's comment is
`<CNcomment><TX>name: description</TX><common_properties>` with `sensor_type`, `sensor_name` and `sensor_description` (left out
when empty), `sampling_rate_ms` (when the sensor has one) and `log_only_new_data`. The raw CAN group's CG comment has the first
three (`CANBUS_RAW`). The other families use sensors from this list: `rotation` has `oil_pressure`, `ignition`, `oil_kpa`
(virtual, `oil_pressure * 100`) and `can_raw` on bus 1 (the first set's rotation sensors, with a table on the oil sensor and an
expression on the virtual one, which the attached configuration needs); `nospace` has `oil_pressure`, `ignition` and `coolant_temp`.

## The end of a file and the attachments, as the bytes show

- **After the data.** A finalized file is: the header blocks (ID, HD, FH, the attachments, the data group's blocks), the DT block
  whose length is final, then zero padding to a multiple of 8 (0 to 7 bytes; 0, 2, 3 and 5 here), then `##EV` with its name (TX) and its comment
  (MD) at an 8-aligned address, and the file ends on a multiple of 8. `HD.hd_ev_first` links the event and `HD.hd_at_first` the
  first attachment; the walk in `fixtures.json` gives the offsets (`tail`). The padding and the event are no records: a reader
  stops at the DT block's end. An unclosed file has neither; its DT length is 24 as before.
- **The event**: type 4 (stop recording trigger), sync type time, range type point, no scope, no attachment, `ev_sync_base_value`
  in microseconds after the file's start (the last tick; the start if there was none), factor 1e-6. Cause: error for `NoSpace`,
  tool for a rotation, user for `Stopped`. Name `Logging stopped`, `Logging stopped: no space` or `Next file`; its comment text
  is the name and its properties `stop_reason` (the `LoggingStatusReason` number, left out for a rotation), `rotated` (`true`,
  for a rotation only), `overruns` and `frames_dropped`, in that order.
- **An attachment**: flags 1 (embedded; no MD5, so `at_md5_checksum` is all zeros), original and embedded size equal, the bytes
  padded to 8; links: next, file name, MIME type, comment. The comment's `crc` is the CRC-32 (zlib's) of the bytes.
- **The channel limits**: `cn_flags` bit 4 (0x10, `LimitValid`) set on the value channel and `cn_limit_min`/`cn_limit_max` set;
  bit 3 (the value range) and bit 5 (the extended limits) are never set. Raw channels have no limits.

## asammdf 8.8.27 (Python 3.12, numpy 2.5.4, numexpr 2.14.2)

How it was read is as in the first set (`MDF(path)`, `get(..., raw=True, ignore_invalidation_bits=True)`, the logger captured
at DEBUG), plus `mdf.header` (`time_quality` is the time class, `_common_properties` the header's properties), `channel.source`,
`channel.lower_limit`/`upper_limit` where `flags & 0x10`, `mdf.attachments` with `extract_attachment(index=i)` (which gives the
embedded bytes, a name and an MD5) and `mdf.events`.

- Every file opens. A finalized file raises or logs nothing, so the padding and the event after the data do not disturb it (the
  first set's closed files had nothing there). The unclosed copy gives the same four INFO messages as before.
- Its samples are the readings that were fed, for every channel of every file (`verification.matches_feed` is true for all six),
  and the reader's scan reads exactly those.
- The sources, limits, attachments, header properties, time class and events asammdf reads are what the manifest expects
  (`extract.py` checks them: `verification.problems` is empty). `extract_attachment()` gives bytes whose size and CRC-32 are the
  manifest's and the comment's `crc`.
- **Nothing on which asammdf and the reader disagree.** A few things to know: asammdf takes the time class as `time_quality`;
  its `Channel.lower_ext_limit`/`upper_ext_limit` are 0.0 when the flag is off (the reader says no extended limits then).
- `fixtures.json` `file.walk` has the full block walk for a file that is not a copy and, for `values_unclosed.mf4`, a summary
  (the same channels; its attachments and events are listed in it too).

## Which file exercises what

- a set clock (time class 10, `clock` `set`, a real-time start), `unit_uid`, `boot_id`, `session_id`, `start_cause`: `values.mf4`,
  `nospace.mf4`; the unit's clock (class 0, `uptime`): `can.mf4` and the rotation files
- value channels' sources of every kind (ADC, GPIO, CAN signal, virtual, script, control), limits for an indicator, a linear table
  and none for an expression, a cubic table, a script or a CAN signal: `values.mf4`
- a CG comment with `sensor_type` `CANBUS_RAW`: `can.mf4` and the rotation files
- four attachments, their comment properties, the `crc` and `configuration_type`: every file; a different sensors configuration
  in each family
- an end event with `stop_reason` 0 (user), 2 (error: no space), and the rotated one (tool) with counts: `values.mf4`,
  `nospace.mf4`, the rotation pair, `can.mf4`
- two files of one session (`file_index` 0 and 1, one `session_id`): the rotation pair
- an unfinalized file with the new header (attachments, properties) and no event: `values_unclosed.mf4`

## Left out

- No `*_cut_*` copies of these (the first set has them; this set's files differ from those only in what comes before and after
  the data) and no fixture with a wide record ID, a conversion matrix or several CAN sensors: the first set has them.
- A **script** sensor is made without a script engine: its configuration names `scripts/sensors/boost.lua`, which exists nowhere
  (the logger never runs it and the sensors configuration was serialized with no card to check paths against).
- The `.raw` channel's comment, conversion and absence of properties are as in the first set.
- No real unit was met: the `clock` of a unit that was never set is `uptime` here by the logger's own rule, and the middle dot in
  a CAN source's name is UTF-8 (`C2 B7`).
