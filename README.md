# el_kotlin_mdf

A reader of ASAM MDF 4 measurement files (`.mf4`) in Kotlin Multiplatform common code, for the Eerie Leap units' logs
and the Gefera app that plots them. It runs wherever the app does: the JVM (the desktop, and Android, which takes the JVM
variant) and iOS. It reads; it never writes MDF.

**Status: the core is built.** It reads a file's structure and its data, the units' own logs byte for byte (the fixtures
under `src/jvmTest/resources/fixtures` are written by the firmware's own logger, with asammdf's values for each, and the
tests check every sample against them). The rest of MDF 4 that another writer's file may hold is planned in Gefera's log
plots plan (Phase 1, step 4) and listed below.

## What it reads

- MDF 4.00 to 4.2x; MDF 3 is refused (`MdfFailure.UnsupportedVersion`).
- The blocks ID, HD, FH, MD, TX, DG, CG, CN, CC, SI, DT and DZ (inflated whole, at most 64 MiB, transposed back where its
  zip type says so): sorted and unsorted data groups, variable-length (VLSD) channels, invalidation bits, structures of
  channels (CN compositions).
- Files their writer didn't finish (`UnFinMF`, as a unit leaves a log at a power loss) and files cut short (a download in
  progress): the data to the end of the file, up to the last whole record; the cycle counts are the scan's own.
- Conversions 0 to 11: identity, linear, rational, algebraic (the library's own formula parser: `X`, numbers, `+ - * /`,
  unary minus, parentheses, `sin cos tan sqrt abs log`; anything else leaves the values as stored), value-to-value tables
  with and without interpolation, value ranges to values, values and value ranges to texts, text to value, text to text,
  bit fields to texts. A type it doesn't take is `MdfConversion.Unsupported`, which says why.
- Values: integers of any bit count to 64 at any bit offset, both byte orders; floats of 16, 32 and 64 bits; strings
  (Latin-1, UTF-8, UTF-16) and byte arrays (for a readout).
- ASAM bus logging: `CAN_DataFrame` groups as `MdfCanFrame`s, joined to their payloads by offset.
- Random access through a source the caller gives (`MdfSource`), so a file of gigabytes is never held in memory.

Not yet (the next step of the plan; each says so where it is, with an `MdfFailure.Unsupported`, and the rest of the file
reads): data lists (DL, HL), signal data blocks (SD) of sorted VLSD channels, arrays (CA), the channel hierarchy (CH),
events (EV), attachments (AT), MDF 4.2's column storage (DV, DI, LD, RV, RI). Never: sample reduction (SR, RD), which only
repeats the data.

## Using it

```kotlin
val file = Mdf4Reader.open(source)               // structure only; fails with an MdfException (MdfFailure says why)
file.header.startNs; file.history; file.comment  // the header, the file history, the header's properties
val group = file.groups.single()                 // data groups, their channel groups, channels, conversions, sources

val scanner = MdfScanner.create(file, group, requests, sink)  // requests: MdfChannelRequest(channel, stored = false)
val progress = scanner.advance()                 // reads what the source holds now; call again as it grows
```

`MdfSampleSink.onSample(request, time, value, isInvalid)` gets each sample with the group's master converted (seconds for a
time master); `stored = true` asks for the values as stored. `MdfScanProgress` says how many bytes were read, how many there
are, whether the file is complete and, if the records can't be read on, why. `CanFrames` lists a file's CAN groups and reads
their frames.

Every failure is an `MdfException` whose `MdfFailure` is for the caller to word: not MDF, a version it doesn't take, cut
short (`Truncated`: a block, or a link to one, past the end of the file), damaged at a byte (`Damaged`), something it doesn't
take (`Unsupported`), too large (`TooLarge`). `Mdf4Reader.open(source, lenient = true)` leaves out what it can't read of a
file's structure instead (`MdfFile.issues`) and reads the rest.

## Building

`./gradlew check` builds the library and runs its tests and code style checks: Kotlin 2.3.10 and JDK 21, which the
toolchain resolver fetches where it's missing. The iOS targets compile to klibs on any host (`compileKotlinIosArm64`,
`compileKotlinIosSimulatorArm64`); linking and running their tests takes macOS.

The code style is the Gefera app's: ktlint formats (`.editorconfig`, through Spotless) and detekt lints
(`config/detekt/detekt.yml`); every compilation formats the sources and lints them first. The public API is declared
explicitly (Kotlin's explicit API mode).

## Tests

`commonTest` holds a writer of MDF files (`Mdf4TestWriter`, `FirmwareLogWriter`) that is independent of the reader and writes
the firmware's logs in its exact layout: `TestWriterMatchesFirmwareTest` rebuilds twelve of the fixtures from what they record
and compares the bytes. The reader is tested against it (every conversion, data type and byte order, sorted and unsorted
data, zipped blocks, a file cut at every byte, a file growing as it is scanned, damaged files, random mutations of a file) and,
on the JVM, against the firmware's real logs and asammdf's values (`FirmwareFixturesTest`).

## In Gefera

A git submodule at `el_kotlin_mdf/`, which the app's build includes as a composite build, its coordinates
`com.eerie_leap:el_kotlin_mdf`. The Kotlin version follows the app's: both builds load one Kotlin Gradle plugin.
