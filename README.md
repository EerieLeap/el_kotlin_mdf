# el_kotlin_mdf

A reader of ASAM MDF 4 measurement files (`.mf4`), and a writer of MDF 4 CAN bus logs, in Kotlin Multiplatform common code,
for the Eerie Leap units' logs and the Gefera app that plots them and records CAN traffic. It runs wherever the app does: the
JVM (the desktop, and Android, which takes the JVM variant) and iOS. It reads any writer's MDF 4; it writes one kind of file,
a CAN bus log (see Writing).

**Status: it reads any writer's MDF 4 file** that the standard describes, bar what is listed under "What it leaves out"
(each says so where it is: an `MdfFailure.Unsupported`, a note in `MdfFile.skipped`, or a reason on the thing it leaves
out). The units' own logs are read byte for byte (the fixtures under `src/jvmTest/resources/fixtures` are written by the
firmware's own logger, with asammdf's values for each); other writers' files are checked against asammdf's reading of the
files it writes (a matrix of versions, ways of storing the data and kinds of channel) and against a few public logs of
another logger (see Tests).

## What it reads

- MDF 4.00 to 4.2x; MDF 3 is refused (`MdfFailure.UnsupportedVersion`).
- The blocks ID, HD, FH, MD, TX, DG, CG, CN, CC, SI, CA, CH, EV and AT: sorted and unsorted data groups (record IDs of 1,
  2, 4 or 8 bytes), variable-length (VLSD) channels, invalidation bits, structures of channels (CN compositions).
- Where the data is: DT; DZ (inflated whole, at most 64 MiB, transposed back where its zip type says so); lists of
  blocks, DL, of equal or unequal lengths and chained, and an HL over zipped ones (the length of each block is read from the
  block itself, since writers get a list's own lengths wrong; a block named twice, or a list of more than two million, is
  refused); SD signal data blocks, alone, in lists and zipped, for the strings and byte arrays of sorted VLSD channels; and
  MDF 4.2's column storage (an LD list of DV value blocks and DI invalidation blocks, a channel group for each signal, joined
  to its master with `cg_cg_master`; the invalidation block of a pair is the one at the same place as its values block, an empty
  link making the records valid).
- Files their writer didn't finish (`UnFinMF`, as a unit leaves a log at a power loss, or CANedge does) and files cut
  short (a download in progress): every flag of `id_unfin_flags` is taken for what it says (stale cycle counts, last data
  block, last list not filled in, VLSD byte counts and offsets), and the data is read to the end of the file, up to the last
  whole record; the cycle counts are the scan's own. The last block of such a file is open to the next block or the end of
  the file only if no block begins where its length says it ends, and is closed again when one does; a list that is not
  filled in takes the blocks after it only in a file of one data group, and only blocks of its data. A file cut in its lists
  is scanned as far as they go (`MdfScanProgress.isCut`) and goes on from the list it was cut at when it grows.
- Masters: in the group's records, in another channel group (a remote master, `MdfChannelGroup.remoteMaster`), or virtual
  (computed from the record index); the axis they make is `MdfChannelGroup.masterAxis` (`MdfSyncType`: time, angle, distance,
  index), and a sample's time is that axis, in its unit.
- Arrays (CA) of a fixed size: each element is a channel of its own (`oil_temp[3]`, `m[1][2]`, the last index running
  fastest, or the first in an array of inverse layout, at `ca_byte_offset_base` apart), the container keeps `MdfChannel.array`
  and lists them in `members`.
- The channel hierarchy (`MdfFile.hierarchy`), the events (`MdfFile.events`: type, range, cause, sync type and value, name,
  comment, parent and range links, the channels and groups it is about) and the attachments (`MdfFile.attachments`: file name,
  MIME type, embedded or not, compressed or not, size, MD5; `MdfFile.readAttachment` gives an embedded file's bytes, inflated).
- Conversions 0 to 11: identity, linear, rational, algebraic (the library's own formula parser: `X`, numbers, `+ - * /`,
  unary minus, parentheses, `sin cos tan sqrt abs log`; anything else leaves the values as stored), value-to-value tables
  with and without interpolation, value ranges to values, values and value ranges to texts, text to value, text to text,
  bit fields to texts. A type it doesn't take is `MdfConversion.Unsupported`, which says why.
- Channel ranges: a channel's value range (`MdfChannel.valueRange`, `cn_val_range_*`, flag bit 3), its limits
  (`MdfChannel.limits`, `cn_limit_min` and `cn_limit_max`, flag bit 4: the physical values it is expected to stay in) and its
  extended limits (`MdfChannel.extendedLimits`, `cn_limit_ext_*`, flag bit 5), each only where its flag says it is valid and
  its minimum does not exceed its maximum.
- Values: integers of any bit count to 64 at any bit offset, both byte orders; floats of 16, 32 and 64 bits; strings
  (Latin-1, UTF-8, UTF-16) and byte arrays (for a readout).
- ASAM bus logging: `CAN_DataFrame` groups as `MdfCanFrame`s, joined to their payloads by offset (or read from the record
  where the payload is a byte array in it), and `CAN_RemoteFrame` groups as frames with `isRemote` set (no payload).
- Random access through a source the caller gives (`MdfSource`), so a file of gigabytes is never held in memory: opening a
  file of 200 groups and 20,000 channels takes a fraction of a second, and a scan reads the data of the groups it is asked
  for and no other.

## What it leaves out

- Sample reductions (SR) and their data (RD, RV, RI), which only repeat the data in a condensed form: listed in
  `MdfFile.skipped` and `MdfChannelGroup.hasSampleReductions`, never read.
- Arrays it can't lay out: of dynamic size, with axes taken from other channels, of arrays or structures, with their
  elements stored in other groups, of more than 4096 elements (or 200,000 in the file), whose elements' invalidation bits lie
  beyond any record. They are in the model with the reason (`MdfChannelArray.unreadReason`), their elements aren't made into
  channels, and the container is never scanned as a scalar; the fixed axes of an array that is read are kept (`fixedAxes`).
- Column storage in an unsorted data group, or with more than one channel group in a data group (`Unsupported`).
- Zip types other than deflate and transposed deflate, attachments compressed any other way, and attachments that name a
  file instead of holding it.
- A zipped block of more than 64 MiB (`TooLarge`), a master that leads through more than three masters, and bus events
  other than CAN data and remote frames (listed by `CanFrames.otherBusEventGroups`, not read).

## Using it

```kotlin
val file = Mdf4Reader.open(source)               // structure only; fails with an MdfException (MdfFailure says why)
file.header.startNs; file.history; file.comment  // the header, the file history, the header's properties
file.hierarchy; file.events; file.attachments    // the hierarchy, the events, the attachments
val group = file.groups.single()                 // data groups, their channel groups, channels, conversions, sources

val scanner = MdfScanner.create(file, group, requests, sink)  // requests: MdfChannelRequest(channel, stored = false)
val progress = scanner.advance()                 // reads what the source holds now; call again as it grows
```

`MdfSampleSink.onSample(request, time, value, isInvalid)` gets each sample with the group's master converted (seconds for a
time master); `stored = true` asks for the values as stored. `MdfScanProgress` says how many bytes were read, how many there
are, whether the file is complete, whether its lists are cut (`isCut`: a file cut short or still arriving, so that
`bytesDeclared` is what the lists named so far hold) and, if the records can't be read on, why. A record may wait for data that
isn't in the file yet (a text in signal data, a time of a master in another group) and the scan waits with it; `advance(maxBytes,
sourceIsFinal = true)` says no more bytes will come, and the record goes without, and the scan goes on, and a file whose last
block has no end is complete once all of it is read. `MdfScanner.rejected` lists the requests it couldn't take (an array, a
channel whose signal data is missing) while it scans the rest. `CanFrames` lists a file's CAN groups and reads their frames.

Every failure is an `MdfException` whose `MdfFailure` is for the caller to word: not MDF, a version it doesn't take, cut
short (`Truncated`: a block, or a link to one, past the end of the file), damaged at a byte (`Damaged`), something it doesn't
take (`Unsupported`), too large (`TooLarge`), or not read by its source (`NotRead`: whatever the `MdfSource` throws but
cancellation, which passes). A damaged file fails this way, never with a crash or a hang, and no read is ever asked of the
source at a negative position or of a negative length.
`Mdf4Reader.open(source, lenient = true)` leaves out what it can't read of a file's structure instead (`MdfFile.issues`) and
reads the rest.

## Writing

`Mdf4BusLogWriter` (package `com.eerie_leap.mdf.writer`) writes an MDF 4.10 log of CAN bus traffic as the frames come, in the
layout of the Eerie Leap firmware's logger and the ASAM bus logging standard: a `CAN_DataFrame` group (`BusChannel`, `ID` with
the IDE flag, `DLC`, `Dir`, `EDL`, `BRS`, `ESI`, `DataLength`, and `DataBytes` as variable-length data in a group of its own)
and a `CAN_RemoteFrame` group, with float64 seconds as the master. The log reads back through this reader and through asammdf.

```kotlin
val writer = Mdf4BusLogWriter.begin(sink, Mdf4BusLogOptions(startNs = nowNs, properties = listOf("device_name" to "el_pm100")))
writer.addFrame(timeMicros = 1_250, bus = 0, id = 0x316, data = bytes, isExtended = false, isFd = false)
writer.addRemoteFrame(timeMicros = 1_900, bus = 0, id = 0x7DF, dlc = 8)
writer.addEvent(timeMicros = 2_000_000, name = "gap", comment = "no frames for 2 s")
writer.flush()    // everything added so far is in the file and readable
writer.finish()   // the events, the final counts, and the identifier MDF
```

- **The sink** (`MdfSink`: `position`, `append`, `overwrite`, `flush`) is the caller's, as the reader's `MdfSource` is. The writer
  appends and goes back only to patch lengths and counts; it holds 64 KiB of records and the events (until `finish`), so a
  recording of gigabytes is never in memory. `InMemoryMdfSink` is a sink for small files and tests.
- **While it is open** the file is one a unit leaves at a power loss: the identifier is `UnFinMF` and the unfinalized flags say the
  cycle counts, the last data block's length and the payloads' byte count are stale. The reader reads such a file to its last
  whole record. `flush()` writes the held records and brings the lengths and counts up to date in the file, so a tool that
  ignores the flags reads it as well; `finish()` does the same, appends the events (EV blocks, linked from the header) and makes
  the identifier `MDF`.
- **The head** carries the start time and time class (HD), the `<common_properties>` the caller passes in the header's comment, and
  the tool, vendor and version in the file history.
- **Frames**: times are microseconds from the start; a frame longer than 8 bytes must be flagged CAN FD and be one of the CAN FD
  lengths (12, 16, 20, 24, 32, 48, 64). Events are point events with a name and a comment, of any `MdfEventType` and `MdfEventCause`.

## Building

`./gradlew check` builds the library and runs its tests and code style checks: Kotlin 2.3.10 and JDK 21, which the
toolchain resolver fetches where it's missing. The iOS targets compile to klibs on any host (`compileKotlinIosArm64`,
`compileKotlinIosSimulatorArm64`); linking and running their tests takes macOS.

The code style is the Gefera app's: ktlint formats (`.editorconfig`, through Spotless) and detekt lints
(`config/detekt/detekt.yml`); every compilation formats the sources and lints them first. The public API is declared
explicitly (Kotlin's explicit API mode).

## Tests

`Mdf4BusLogWriterTest` writes bus logs and reads them back through the reader, frame for frame (every CAN FD length, extended
IDs, remote frames, events, the header's properties), cuts an unfinished log at every byte, and streams 100,000 frames through
the small buffer.

`commonTest` also holds a writer of MDF files (`Mdf4TestWriter`, `ExtraWriters`, `FirmwareLogWriter`) that is independent of
the reader and writes the firmware's logs in its exact layout: `TestWriterMatchesFirmwareTest` rebuilds twelve of the
fixtures from what they record and compares the bytes. The reader is tested against it for every block it reads (lists of
equal and unequal blocks, headers of transposed zipped lists, signal data, arrays, the hierarchy, events, attachments,
column storage, remote and virtual masters, record IDs of every width, every unfinalized flag), every conversion, data type
and byte order, sorted and unsorted data, a file cut at every byte, a file growing as it is scanned, damaged files (sizes
that overflow, a list that links to the wrong block, an attachment past the end, chains that loop) and random mutations of a
file, of the firmware's log and of a file with a block of every kind (`RichFile`), with numbers set to the values that
overflow: whatever the damage, reading ends in an `MdfException` or in what the file still holds.

On the JVM the reader is checked against the firmware's real logs and asammdf's values (`FirmwareFixturesTest`, for the
logs of rt_core 5427774, and `Cbff74eFixturesTest`, for those of cbff74e, which also carry the channels' sources and limits, the
unit's UID, boot and session, a clock set to real time, the configurations attached to the file and the event that ends it: both
share `FixtureChecks`; see `src/jvmTest/resources/fixtures/cbff74e/README.md`) and against
a matrix of files asammdf 8.8.27 writes (`AsammdfMatrixTest`): MDF 4.00, 4.10 and 4.20; plain, deflated and transposed
data in lists; strings and invalidation bits; masters that are angles, distances and indexes; many groups; arrays, events
and attachments; every kind of conversion; and column storage. The files and asammdf's values for them (`expected_*.json`)
are in `src/jvmTest/resources/asammdf`, made by `make_matrix_files.py` in a virtual environment with `asammdf==8.8.27`.
`LargeFilesTest` measures opening a file of 200 groups of 100 channels and shows that a scan reads only the groups it is
asked for.

`PublicSamplesTest` reads public logs of another logger (CSS Electronics' CANedge, MDF 4.11, unfinished as a power loss
leaves them) and compares their frames with asammdf's. The files state no licence, so they are not kept here: run
`python src/jvmTest/resources/samples/fetch_samples.py` (it checks each file by size and CRC-32, and with asammdf installed
also writes the first frames asammdf reads) and the test runs from then on; without the files it is skipped.

## In Gefera

A git submodule at `el_kotlin_mdf/`, which the app's build includes as a composite build, its coordinates
`com.eerie_leap:el_kotlin_mdf`. The Kotlin version follows the app's: both builds load one Kotlin Gradle plugin.

## License

Apache License 2.0, see [LICENSE](LICENSE).
