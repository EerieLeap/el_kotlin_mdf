# el_kotlin_mdf

A reader of ASAM MDF 4 measurement files (`.mf4`) in Kotlin Multiplatform common code, for the Eerie Leap units' logs
and the Gefera app that plots them. It runs wherever the app does: the JVM (the desktop, and Android, which takes the JVM
variant) and iOS.

**Status: started.** It reads the identification block, which tells an MDF 4 file, finished or not, from anything else.
The rest is planned in Gefera's log plots plan (Phase 1).

## What it is to read

- MDF 4.00 to 4.2x; MDF 3 is refused.
- The blocks ID, HD, FH, MD, TX, DG, CG, CN, CC, SI, DT, DL, DZ, HL and SD: sorted and unsorted data groups,
  variable-length (VLSD) channels, invalidation bits, structures of channels.
- Files their writer didn't finish (`UnFinMF`, as a unit leaves a log at a power loss): the data to the end of the file,
  up to the last whole record.
- Conversions 0 to 8: identity, linear, rational, algebraic, value-to-value tables with and without interpolation, value
  ranges to values, values and value ranges to texts. Others leave the values as stored.
- ASAM bus logging: `CAN_DataFrame` groups as frames.
- Random access through a source the caller gives, so a file of gigabytes is never held in memory.

Not: arrays (CA), sample reduction (SR, RD), attachments (AT), events (EV), channel hierarchies (CH); writing.

## Building

`./gradlew check` builds the library and runs its tests and code style checks: Kotlin 2.3.10 and JDK 21, which the
toolchain resolver fetches where it's missing. The iOS targets build on macOS only.

The code style is the Gefera app's: ktlint formats (`.editorconfig`, through Spotless) and detekt lints
(`config/detekt/detekt.yml`); every compilation formats the sources and lints them first. The public API is declared
explicitly (Kotlin's explicit API mode).

## In Gefera

A git submodule at `el_kotlin_mdf/`, which the app's build includes as a composite build, its coordinates
`com.eerie_leap:el_kotlin_mdf`. The Kotlin version follows the app's: both builds load one Kotlin Gradle plugin.
