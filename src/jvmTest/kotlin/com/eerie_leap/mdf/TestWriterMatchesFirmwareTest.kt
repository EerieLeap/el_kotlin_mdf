package com.eerie_leap.mdf

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The test writer ([FirmwareLogWriter]) against the real thing: for each of the firmware's logs it is fed the readings the
 * fixtures record (from asammdf's values and the byte spans of the manifests) and has to write the very bytes the firmware wrote,
 * so the files the other tests build are laid out as a unit's are.
 */
class TestWriterMatchesFirmwareTest {
    private val rebuilt = listOf(
        "values.mf4",
        "values_unclosed.mf4",
        "can.mf4",
        "can_unclosed.mf4",
        "can_mixed.mf4",
        "can_mixed_unclosed.mf4",
        "conversions.mf4",
        "empty.mf4",
        "log_data_20251022T061059Z_3071.mf4",
        "log_data_20251022T061101Z_5128.mf4",
        "rotation_second_unclosed.mf4",
        "wide_ids.mf4",
    )

    @Test
    fun writesTheBytesTheFirmwareWrote() {
        for (name in rebuilt) {
            val real = Fixtures.bytes(name)

            assertSameBytes(name, real, rebuild(Fixtures.entry(name)))
        }
    }

    @Test
    fun theCutFixturesArePrefixesOfTheirParents() {
        for (entry in Fixtures.files.filter { it["cut"] != null }) {
            val name = entry["name"].text()
            val parent = Fixtures.bytes(entry["derived_from"].text())

            assertContentEquals(parent.copyOf(entry["cut"].obj()["length"].int()), Fixtures.bytes(name), name)
        }
    }

    private fun assertSameBytes(name: String, expected: ByteArray, actual: ByteArray) {
        val firstDifference = (0 until minOf(expected.size, actual.size)).firstOrNull { expected[it] != actual[it] }
        if (firstDifference != null) {
            val from = maxOf(0, firstDifference - CONTEXT)
            fun hex(bytes: ByteArray) = bytes.copyOfRange(from, minOf(bytes.size, firstDifference + CONTEXT)).joinToString(" ") {
                (it.toInt() and 0xFF).toString(16).padStart(2, '0')
            }
            fail(
                "$name differs at byte $firstDifference (0x${firstDifference.toString(
                    16,
                )}):\n  real   ${hex(expected)}\n  writer ${hex(actual)}",
            )
        }
        assertEquals(expected.size, actual.size, "$name: size")
    }

    // The file as the firmware's logger would write it, from what the fixtures record of its session.
    private fun rebuild(entry: Map<String, Any?>): ByteArray {
        val family = Fixtures.families()[entry["family"]].obj()
        val metadata = family["metadata"].obj()
        val asammdf = entry["asammdf"].obj()
        val sensors = family["sensors"].list().map { it.obj() }.filter { it["has_channel_group"] == true }
        val groups = asammdf["groups"].list().map { it.obj() }

        val writer = FirmwareLogWriter(recordIdSize = if (2 * sensors.size + 1 <= MAX_ONE_BYTE_ID) 1 else 2)
        writer.startNs = asammdf["header"].obj()["start_time_ns"].long()
        writer.toolId = metadata["tool_id"].text()
        writer.toolVersion = metadata["tool_version"].text()
        writer.properties = listOf(
            "device_name" to metadata["device_name"].text(),
            "product_id" to metadata["product_id"].long().toString(),
            "device_id" to metadata["device_id"].long().toString(),
            "hw_version" to metadata["hw_version"].long().toString(),
            "sw_version" to metadata["sw_version"].long().toString(),
            "sensors_configuration_hash" to metadata["sensors_configuration_hash"].long().toString(),
            "logging_interval_ms" to family["logging_interval_ms"].long().toString(),
        )

        val handles = sensors.map { sensor ->
            val comment = commentOf(sensor["name"].text(), sensor["description"].text())
            if (sensor["type"] == "CANBUS_RAW") {
                writer.can(FirmwareLogWriter.CanSensor(sensor["id"].text(), comment))
            } else {
                val raw = if (sensor["has_raw_channel"] == true) {
                    val formula = sensor["mdf_formula"] as String?
                    FirmwareLogWriter.Raw(formula, if (formula == null) "expression: ${sensor["expression"].text()}" else null)
                } else {
                    null
                }
                writer.sensor(FirmwareLogWriter.ValueSensor(sensor["id"].text(), sensor["unit"].text(), comment, raw))
            }
        }

        // Each record, in the order the file has them: the sensor it belongs to and which of that sensor's records it is.
        val next = IntArray(sensors.size)
        for (span in entry["record_spans"].list().map { it.list() }) {
            val index = sensors.indexOfFirst { it["index"].int() == span[2].int() }
            val group = groups.first { it["name"] == sensors[index]["id"] }
            val k = next[index]++
            if (sensors[index]["type"] == "CANBUS_RAW") {
                writer.frame(handles[index], frameOf(group["frames"].list()[k].obj()))
            } else {
                writeValue(writer, handles[index], group, k)
            }
        }
        return writer.build(finished = entry["identifier"] == "MDF     ")
    }

    private fun writeValue(writer: FirmwareLogWriter, sensor: Int, group: Map<String, Any?>, k: Int) {
        val channels = group["channels"].list().map { it.obj() }
        val value = channels[0]["stored"].obj()
        val raw = channels.getOrNull(1)?.get("stored")?.obj()
        val invalid =
            (if (value["invalid"].list()[k].int() != 0) 1 else 0) or (if (raw != null && raw["invalid"].list()[k].int() != 0) 2 else 0)
        writer.value(
            sensor,
            group["master"].obj()["timestamps"].list()[k].double(),
            value["samples"].list()[k].double().toFloat(),
            raw?.get("samples")?.list()?.get(k)?.double()?.toFloat(),
            invalid,
        )
    }

    private fun frameOf(frame: Map<String, Any?>): FirmwareLogWriter.Frame = FirmwareLogWriter.Frame(
        time = frame["t"].double(),
        id = frame["id"].long(),
        data = frame["data"].text().chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
        bus = frame["bus_channel"].int(),
        isExtended = frame["extended"] == true,
        isTransmit = frame["transmit"] == true,
        isFd = frame["fd"] == true,
        isBitRateSwitch = frame["brs"] == true,
    )

    // Mdf4LoggerSensorReading's GetSensorComment: the name, then the description after ": ", whichever there is.
    private fun commentOf(name: String, description: String): String? {
        val comment = if (description.isEmpty()) {
            name
        } else if (name.isEmpty()) {
            description
        } else {
            "$name: $description"
        }
        return comment.ifEmpty { null }
    }

    private companion object {
        const val CONTEXT = 24
        const val MAX_ONE_BYTE_ID = 255
    }
}
