// Scratch generator of the MDF reader's fixtures from rt_core cbff74e (log-plots-firmware plan, G-17).
//
// Logs written by the firmware's own logger, Mdf4LoggerSensorReading (rt_core cbff74e), over an
// in-memory stream, with the sensors, the logging configuration and every reading made up by hand.
// The calls are those of LogWriterService/ProcessTick: StartLogging() when a file opens,
// LogReading(tick time, reading) once for each sensor's latest reading and once for each queued CAN
// frame at every tick, a rotation (StopLogging(), then StartLogging() at the time of the last tick,
// as RotateFile() does), StopLogging() at the end. Nothing is stubbed or reimplemented: every byte of
// every file comes out of rt_core's Mdf4File.
//
// Built as a ztest app against rt_core, read-only (see CMakeLists.txt); run with
// MDF_FIXTURE_OUT=<folder> ./zephyr.exe. Every file is written to that folder, with one
// manifest_<family>.json per family: the configuration, every reading fed and what the logger
// answered, and the byte span of every record written.
#include <algorithm>
#include <array>
#include <charconv>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <map>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include <eerie_memory.hpp>
#include <zephyr/sys/crc.h>
#include <zephyr/ztest.h>

#include "configuration/cbor/cbor_serializer.h"
#include "domain/adc_domain/configuration/adc_default_configuration.h"
#include "domain/adc_domain/configuration/parsers/adc_configuration_cbor_parser.h"
#include "domain/adc_domain/models/adc_configuration.h"
#include "domain/canbus_domain/configuration/parsers/canbus_configuration_cbor_parser.h"
#include "domain/canbus_domain/models/canbus_configuration.h"
#include "domain/logging_domain/configuration/parsers/logging_configuration_cbor_parser.h"
#include "domain/logging_domain/event_bus/logging_status_reason.h"
#include "domain/logging_domain/loggers/log_file_end.h"
#include "domain/logging_domain/loggers/log_file_start.h"
#include "domain/logging_domain/loggers/logger_metadata.h"
#include "domain/logging_domain/models/log_session.h"
#include "domain/logging_domain/models/logging_start_cause.h"
#include "domain/sensor_domain/configuration/parsers/sensors_cbor_parser.h"
#include "domain/logging_domain/loggers/mdf4_logger_sensor_reading.h"
#include "domain/logging_domain/models/logging_configuration.h"
#include "domain/logging_domain/services/log_file_naming.h"
#include "domain/sensor_domain/models/sensor.h"
#include "domain/sensor_domain/models/sensor_reading.h"
#include "domain/sensor_domain/models/sources/canbus_source.h"
#include "subsys/canbus/can_frame.h"
#include "utilities/memory/memory_resource_manager.h"

#include "memory_stream_buf.h"

using namespace eerie_memory;
using namespace eerie_leap::utilities::memory;
using namespace eerie_leap::subsys::canbus;
using namespace eerie_leap::domain::sensor_domain::models;
using namespace eerie_leap::domain::sensor_domain::models::sources;
using namespace eerie_leap::domain::logging_domain::models;
using namespace eerie_leap::domain::logging_domain::loggers;
using namespace eerie_leap::domain::logging_domain::services;
using rt_core_test::MemoryStreamBuf;
using eerie_leap::configuration::cbor::CborSerializer;
using namespace eerie_leap::domain::adc_domain::models;
using namespace eerie_leap::domain::adc_domain::configuration;
using namespace eerie_leap::domain::adc_domain::configuration::parsers;
using namespace eerie_leap::domain::canbus_domain::models;
using namespace eerie_leap::domain::canbus_domain::configuration::parsers;
using namespace eerie_leap::domain::logging_domain::configuration::parsers;
using namespace eerie_leap::domain::sensor_domain::configuration::parsers;
using eerie_leap::domain::logging_domain::event_bus::GetReasonName;
using eerie_leap::domain::logging_domain::event_bus::LoggingStatusReason;
using eerie_leap::utilities::voltage_interpolator::CalibrationData;
using eerie_leap::utilities::voltage_interpolator::InterpolationMethod;

ZTEST_SUITE(fixtures, NULL, NULL, NULL, NULL, NULL);

namespace {

using Clock = std::chrono::system_clock;
using TimePoint = Clock::time_point;

// RtcProvider's fixed date, in microseconds: the unit clock is this plus the time since power-on.
constexpr int64_t UNIT_CLOCK_ORIGIN_US = 1'761'106'217'000LL * 1000;
constexpr int64_t TICK_JITTER_STEP_US = 100; // the kernel tick of the unit clock

TimePoint At(int64_t us) {
    return TimePoint(std::chrono::duration_cast<Clock::duration>(std::chrono::microseconds(us)));
}

// ------------------------------------------------------------------------------------------------
// Output: files and JSON

std::string g_out_dir = ".";
std::vector<std::string> g_written_names;

void WriteFile(const std::string& name, std::string_view bytes) {
    const std::string path = g_out_dir + "/" + name;
    std::ofstream file(path, std::ios::binary | std::ios::trunc);
    zassert_true(file.is_open(), "cannot open %s", path.c_str());
    file.write(bytes.data(), static_cast<std::streamsize>(bytes.size()));
    file.close();
    zassert_true(!file.fail(), "cannot write %s", path.c_str());
    g_written_names.push_back(name);
}

std::string Q(std::string_view text) {
    std::string out = "\"";
    for(const char c : text) {
        const auto u = static_cast<unsigned char>(c);
        if(c == '"')
            out += "\\\"";
        else if(c == '\\')
            out += "\\\\";
        else if(u < 0x20) {
            char buffer[8];
            snprintf(buffer, sizeof(buffer), "\\u%04x", static_cast<unsigned>(u));
            out += buffer;
        } else
            out += c; // UTF-8 passes through
    }
    out += '"';
    return out;
}

template<typename T>
std::string Num(T value) {
    if constexpr(std::is_floating_point_v<T>) {
        char buffer[64];
        const auto result = std::to_chars(buffer, buffer + sizeof(buffer), value);
        return std::string(buffer, result.ptr);
    } else {
        return std::to_string(static_cast<long long>(value));
    }
}

std::string NumOrNull(const std::optional<float>& value) {
    return value.has_value() ? Num(*value) : "null";
}

std::string Bool(bool value) {
    return value ? "true" : "false";
}

std::string Hex(std::string_view bytes) {
    static constexpr const char* DIGITS = "0123456789abcdef";
    std::string out;
    out.reserve(bytes.size() * 2);
    for(const char c : bytes) {
        const auto u = static_cast<unsigned char>(c);
        out += DIGITS[u >> 4];
        out += DIGITS[u & 0xF];
    }
    return out;
}

std::string Join(const std::vector<std::string>& items, std::string_view separator = ",\n") {
    std::string out;
    for(size_t i = 0; i < items.size(); i++) {
        if(i > 0)
            out += separator;
        out += items[i];
    }
    return out;
}

const char* ErrorName(ReadingError error) {
    switch(error) {
        case ReadingError::NONE: return "NONE";
        case ReadingError::READER_FAILED: return "READER_FAILED";
        case ReadingError::NO_VALUE: return "NO_VALUE";
        case ReadingError::WRONG_STATE: return "WRONG_STATE";
        case ReadingError::INPUT_UNAVAILABLE: return "INPUT_UNAVAILABLE";
        case ReadingError::EXPRESSION_FAILED: return "EXPRESSION_FAILED";
        case ReadingError::EXPRESSION_NOT_A_NUMBER: return "EXPRESSION_NOT_A_NUMBER";
        case ReadingError::SCRIPT_FAILED: return "SCRIPT_FAILED";
    }
    return "UNKNOWN";
}

const char* TypeName(SensorType type) {
    switch(type) {
        case SensorType::NONE: return "NONE";
        case SensorType::PHYSICAL_ANALOG: return "PHYSICAL_ANALOG";
        case SensorType::VIRTUAL_ANALOG: return "VIRTUAL_ANALOG";
        case SensorType::PHYSICAL_INDICATOR: return "PHYSICAL_INDICATOR";
        case SensorType::VIRTUAL_INDICATOR: return "VIRTUAL_INDICATOR";
        case SensorType::CANBUS_RAW: return "CANBUS_RAW";
        case SensorType::CANBUS_ANALOG: return "CANBUS_ANALOG";
        case SensorType::CANBUS_INDICATOR: return "CANBUS_INDICATOR";
        case SensorType::USER_ANALOG: return "USER_ANALOG";
        case SensorType::USER_INDICATOR: return "USER_INDICATOR";
        case SensorType::CONTROL_INDICATOR: return "CONTROL_INDICATOR";
        case SensorType::CONTROL_ANALOG: return "CONTROL_ANALOG";
        case SensorType::COUNT: break;
    }
    return "UNKNOWN";
}

// ------------------------------------------------------------------------------------------------
// Sensors and configurations

struct SensorSpec {
    std::string id;
    SensorType type = SensorType::PHYSICAL_ANALOG;
    std::string name;
    std::string unit;
    std::string description;
    std::string expression;
    bool is_enabled = true;                // Sensor::is_enabled
    bool log_enabled = true;               // SensorLoggingConfiguration::is_enabled
    bool log_raw = false;                  // SensorLoggingConfiguration::log_raw_value
    bool only_new = false;                 // SensorLoggingConfiguration::log_only_new_data
    std::optional<uint8_t> bus_channel;    // CAN sensors: the CAN interface index; none = no source
    uint32_t frame_id = 0x100;             // CAN sensors
    std::string signal;                    // CANBUS_ANALOG / CANBUS_INDICATOR
    std::optional<uint32_t> channel;       // ADC / GPIO channel
    InterpolationMethod method = InterpolationMethod::NONE;
    std::vector<CalibrationData> table;    // PHYSICAL_ANALOG
    std::optional<int> sampling_ms;        // SensorConfiguration::sampling_rate_ms
    std::string script_path;               // USER_* sensors
};

bool IsCan(const SensorSpec& spec) {
    return spec.type == SensorType::CANBUS_RAW;
}

// The logger's own rule for a raw channel (AddValueTarget).
bool HasRawChannel(const SensorSpec& spec) {
    return spec.is_enabled && spec.log_enabled && !IsCan(spec) && !spec.expression.empty() && spec.log_raw
        && (spec.type == SensorType::PHYSICAL_ANALOG || spec.type == SensorType::PHYSICAL_INDICATOR);
}

bool HasChannelGroup(const SensorSpec& spec) {
    return spec.is_enabled && spec.log_enabled;
}

// What the plan (LOG_PLOTS_FIRMWARE_PLAN.md section 7) says a sensor's value channel gets, worked out here
// from the specification alone, so the manifest holds an expectation that is not the logger's own code.
struct ExpectedSource {
    std::string name;    // empty: no SI block on the channel
    int source_type = 0; // MDF si_type: 0 other, 1 ECU, 2 bus, 3 I/O device, 4 tool, 5 user
    int bus_type = 0;    // si_bus_type: 0 none, 2 CAN
};

ExpectedSource ExpectedSourceOf(const SensorSpec& spec) {
    if(IsCan(spec))
        return {};

    switch(SensorTypeTraits::Of(spec.type).source) {
        case SensorSourceKind::ADC:
            return { "ADC " + std::to_string(spec.channel.value_or(0)), 3, 0 };
        case SensorSourceKind::GPIO:
            return { "DI " + std::to_string(spec.channel.value_or(0)), 3, 0 };
        case SensorSourceKind::CAN_SIGNAL: {
            char frame[16];
            snprintf(frame, sizeof(frame), "%X", static_cast<unsigned>(spec.frame_id));
            return { "CAN " + std::to_string(spec.bus_channel.value_or(0) + 1) + " \xC2\xB7 0x" + frame + " \xC2\xB7 "
                    + spec.signal,
                2, 2 };
        }
        case SensorSourceKind::EXPRESSION:
            return { "virtual", 4, 0 };
        case SensorSourceKind::SCRIPT:
            return { "script", 4, 0 };
        case SensorSourceKind::CONTROL:
            return { "control", 5, 0 };
        default:
            return {};
    }
}

// Limits by the plan: an indicator that no script produces is 0..1; a linear table without expression or
// script is bounded by its lowest and highest value; nothing else.
std::optional<std::pair<double, double>> ExpectedLimitsOf(const SensorSpec& spec) {
    if(IsCan(spec))
        return std::nullopt;

    const auto traits = SensorTypeTraits::Of(spec.type);
    if(traits.value_kind == SensorValueKind::INDICATOR && spec.script_path.empty()
        && traits.source != SensorSourceKind::SCRIPT) {
        return std::make_pair(0.0, 1.0);
    }

    if(spec.type == SensorType::PHYSICAL_ANALOG && spec.method == InterpolationMethod::LINEAR
        && spec.expression.empty() && spec.script_path.empty() && !spec.table.empty()) {

        double low = spec.table.front().value;
        double high = low;
        for(const auto& point : spec.table) {
            low = std::min(low, static_cast<double>(point.value));
            high = std::max(high, static_cast<double>(point.value));
        }
        return std::make_pair(low, high);
    }

    return std::nullopt;
}

std::shared_ptr<Sensor> MakeSensor(const SensorSpec& spec) {
    auto sensor = std::make_shared<Sensor>(std::allocator_arg, Mrm::GetDefaultPmr(), spec.id);
    sensor->is_enabled = spec.is_enabled;
    sensor->metadata.name = spec.name;
    sensor->metadata.unit = spec.unit;
    sensor->metadata.description = spec.description;
    sensor->configuration.type = spec.type;
    sensor->configuration.expression = spec.expression;
    sensor->configuration.sampling_rate_ms = spec.sampling_ms;
    sensor->configuration.script_path = spec.script_path;

    const auto traits = sensor->configuration.GetTraits();
    if(traits.uses_canbus) {
        if(spec.bus_channel.has_value()) {
            sensor->configuration.canbus_source = make_unique_pmr<CanbusSource>(
                Mrm::GetDefaultPmr(), *spec.bus_channel, spec.frame_id, spec.signal
            );
        }
        sensor->configuration.UpdateConnectionString();
    } else if(traits.requires_channel) {
        sensor->configuration.channel = spec.channel.value_or(0);
    }

    if(spec.method != InterpolationMethod::NONE) {
        sensor->configuration.interpolation_method = spec.method;
        sensor->configuration.calibration_table.assign(spec.table.begin(), spec.table.end());
    }

    return sensor;
}

LoggingConfiguration MakeConfiguration(
    const std::vector<SensorSpec>& specs,
    const std::vector<std::shared_ptr<Sensor>>& sensors,
    uint32_t interval_ms
) {

    LoggingConfiguration configuration(std::allocator_arg, Mrm::GetDefaultPmr());
    configuration.logging_interval_ms = interval_ms;
    // Left at its default (10 MB): the writer's size limit is a byte count the driver applies itself,
    // as LogWriterService::SetSizeLimitsForTesting() lets a test do; the logger never reads it.

    for(size_t i = 0; i < specs.size(); i++) {
        configuration.sensor_configurations.emplace(
            sensors[i]->id_hash,
            SensorLoggingConfiguration{
                .is_enabled = specs[i].log_enabled, .log_raw_value = specs[i].log_raw,
                .log_only_new_data = specs[i].only_new }
        );
    }

    return configuration;
}

// The EL PM100's inputs (device tree): 4 ADC and 8 GPIO channels.
constexpr uint32_t ADC_CHANNEL_COUNT = 4;
constexpr uint32_t GPIO_CHANNEL_COUNT = 8;

// ---- the stored configurations a log embeds (F-8): serialized by rt_core's own parsers and serializer ----

template<typename T>
std::vector<uint8_t> Encode(const T& cbor) {
    eerie_leap::configuration::cbor::CborSerializer<T> serializer;
    const auto bytes = serializer.Serialize(cbor);
    zassert_true(!bytes.empty(), "a configuration did not encode");
    return std::vector<uint8_t>(bytes.begin(), bytes.end());
}

// The CAN bus configuration of a session: a variant picks which messages it holds.
PmrUniquePtr<CanbusConfiguration> MakeCanbusConfiguration(int variant) {
    auto configuration = make_unique_pmr<CanbusConfiguration>(Mrm::GetDefaultPmr());

    CanChannelConfiguration channel_0(std::allocator_arg, Mrm::GetDefaultPmr());
    channel_0.type = CanbusType::CANFD;
    channel_0.is_extended_id = false;
    channel_0.bus_channel = 0;
    channel_0.bitrate = 500000;
    channel_0.data_bitrate = 2000000;

    const auto add_message = [](CanChannelConfiguration& channel, uint32_t frame_id, const char* name,
                                 std::optional<int> interval_ms, uint32_t size) {
        auto message = std::make_shared<CanMessageConfiguration>(std::allocator_arg, Mrm::GetDefaultPmr());
        message->frame_id = frame_id;
        message->name = name;
        message->message_size = size;
        message->send_interval_ms = interval_ms;
        channel.message_configurations.push_back(message);
        return message;
    };
    const auto add_signal = [](CanMessageConfiguration& message, const char* name, const char* unit,
                                uint32_t start_bit, uint32_t size_bits, float factor, float offset, bool is_signed) {
        CanSignalConfiguration signal(std::allocator_arg, Mrm::GetDefaultPmr());
        signal.start_bit = start_bit;
        signal.size_bits = size_bits;
        signal.factor = factor;
        signal.offset = offset;
        signal.byte_order = CanSignalByteOrder::LITTLE_ENDIAN_INTEL;
        signal.is_signed = is_signed;
        signal.SetName(name);
        signal.unit = unit;
        message.signal_configurations.emplace_back(std::move(signal));
    };

    // Received: the frames the CAN signal sensors read.
    const auto engine = add_message(channel_0, 0x316, "ENGINE", std::nullopt, 8);
    add_signal(*engine, "RPM", "rpm", 16, 16, 0.25F, 0.0F, false);
    const auto brakes = add_message(channel_0, 0x2A0, "BRAKES", std::nullopt, 8);
    add_signal(*brakes, "BRAKE", "", 0, 1, 1.0F, 0.0F, false);
    // Sent: a sensor's value under its own name.
    if(variant == 0) {
        const auto out = add_message(channel_0, 0x7E0, "EL_OUT", 100, 8);
        add_signal(*out, "coolant_temp", "\xC2\xB0" "C", 0, 16, 0.1F, -40.0F, true);
    }

    CanChannelConfiguration channel_1(std::allocator_arg, Mrm::GetDefaultPmr());
    channel_1.type = CanbusType::CLASSICAL_CAN;
    channel_1.bus_channel = 1;
    channel_1.bitrate = 250000;
    const auto aux = add_message(channel_1, 0x100, "AUX", std::nullopt, 8);
    add_signal(*aux, "AUX_STATE", "", 0, 8, 1.0F, 0.0F, false);

    configuration->channel_configurations.emplace(channel_0.bus_channel, std::move(channel_0));
    configuration->channel_configurations.emplace(channel_1.bus_channel, std::move(channel_1));
    configuration->com_configuration.bus_channel = 1;
    return configuration;
}

std::unique_ptr<AdcConfiguration> MakeAdcConfiguration() {
    auto configuration = std::make_unique<AdcConfiguration>();
    configuration->samples = AdcDefaultConfiguration::SAMPLES;
    configuration->channel_configurations = std::make_shared<std::vector<std::shared_ptr<AdcChannelConfiguration>>>();
    for(uint32_t i = 0; i < ADC_CHANNEL_COUNT; i++) {
        auto channel = std::make_shared<AdcChannelConfiguration>();
        channel->calibrator = AdcDefaultConfiguration::CreateCalibrator(Mrm::GetDefaultPmr());
        configuration->channel_configurations->push_back(channel);
    }
    return configuration;
}

// The four files a unit with ADC inputs attaches, in LoggingController's order (ADC, CAN bus, sensors,
// logging): the stored CBOR as rt_core serializes it, with the attachment's name, MIME type and comment as
// MakeAttachmentsProvider() makes them. The sensors' CRC-32 is returned apart: it is the header's
// sensors_configuration_hash, as the supervisor takes it from the stored configuration's info.
struct Attachments {
    std::vector<LoggerAttachment> list;
    uint32_t sensors_crc = 0;
};

Attachments MakeAttachments(
    const std::vector<std::shared_ptr<Sensor>>& sensors,
    const LoggingConfiguration& logging,
    int canbus_variant
) {
    struct Entry {
        const char* name;
        const char* label;
        uint8_t type;
        std::vector<uint8_t> bytes;
    };

    std::vector<Entry> entries;

    const AdcConfigurationCborParser adc_parser(ADC_CHANNEL_COUNT);
    const auto adc = MakeAdcConfiguration();
    entries.push_back({ "adc_config", "ADC", 0x02, Encode(*adc_parser.Serialize(*adc)) });

    CanbusConfigurationCborParser canbus_parser(nullptr);
    const auto canbus = MakeCanbusConfiguration(canbus_variant);
    entries.push_back({ "canbus_config", "CAN bus", 0x03, Encode(*canbus_parser.Serialize(*canbus)) });

    SensorsCborParser sensors_parser(nullptr);
    entries.push_back({ "sensors_config", "sensors", 0x04,
        Encode(*sensors_parser.Serialize(sensors, GPIO_CHANNEL_COUNT, ADC_CHANNEL_COUNT)) });

    entries.push_back({ "logging_config", "logging", 0x05,
        Encode(*LoggingConfigurationCborParser::Serialize(logging)) });

    Attachments attachments;
    for(auto& entry : entries) {
        const uint32_t crc = crc32_ieee(entry.bytes.data(), entry.bytes.size());
        if(entry.type == 0x04)
            attachments.sensors_crc = crc;

        LoggerAttachment attachment;
        attachment.file_name = std::string(entry.name) + ".cbor";
        attachment.mime_type = "application/cbor";
        attachment.comment_text = std::string("Eerie Leap ") + entry.label + " configuration";
        attachment.properties = { { "configuration_type", std::to_string(entry.type) },
            { "crc", std::to_string(crc) } };
        attachment.bytes = std::move(entry.bytes);
        attachments.list.push_back(std::move(attachment));
    }

    return attachments;
}

struct MetadataSpec {
    std::string device_name = "esp32s3_pm_hw_02"; // CONFIG_BOARD of the EL PM100
    std::string tool_id = "rt_daq";
    std::string tool_version = "1.3.0";
    uint32_t product_id = 50177; // EL PM100
    uint8_t device_id = 0;
    uint32_t unit_uid = 2864434397U; // 0xAABBCCDD, the plan's example
    uint32_t boot_id = 913024577U;
    uint32_t hw_version = (2U << 24) | (1U << 16) | 0U; // 2.1.0
    uint32_t sw_version = (1U << 24) | (3U << 16) | 0U; // 1.3.0, the tool version
    uint32_t session_id = 40211837U;
    LoggingStartCause start_cause = LoggingStartCause::Trigger;
    int canbus_variant = 0;
    // Set: real time minus the unit clock, in microseconds; the files are on real time (clock "set", class 10).
    std::optional<int64_t> wall_clock_offset_us;
};

LoggerMetadata ToLoggerMetadata(
    const MetadataSpec& spec,
    const std::shared_ptr<LogSession>& session,
    const Attachments& attachments
) {
    LoggerMetadata metadata;
    metadata.device_name = spec.device_name;
    metadata.tool_id = spec.tool_id;
    metadata.tool_version = spec.tool_version;
    metadata.product_id = spec.product_id;
    metadata.device_id = spec.device_id;
    metadata.unit_uid = spec.unit_uid;
    metadata.boot_id = spec.boot_id;
    metadata.hw_version = spec.hw_version;
    metadata.sw_version = spec.sw_version;
    metadata.sensors_configuration_hash = attachments.sensors_crc;
    metadata.session = session;
    metadata.attachments = attachments.list; // the logger takes the bytes; the rig keeps its own copy
    return metadata;
}

std::shared_ptr<LogSession> MakeLogSession(const MetadataSpec& metadata) {
    auto session = std::make_shared<LogSession>();
    session->id = metadata.session_id;
    session->cause = metadata.start_cause;
    return session;
}

// ------------------------------------------------------------------------------------------------
// Feeds: what the sensors produce, by hand

struct SampleSpec {
    int64_t t_us = 0;       // the reading's own timestamp, microseconds after the session start
    int64_t visible_us = 0; // from when a tick sees it as the sensor's latest reading
    std::optional<float> value;
    std::optional<float> raw;
    ReadingError error = ReadingError::NONE;
};

SampleSpec Sample(
    int64_t t_us,
    std::optional<float> value,
    std::optional<float> raw = std::nullopt,
    ReadingError error = ReadingError::NONE,
    int64_t visible_us = -1
) {
    return SampleSpec{ .t_us = t_us, .visible_us = visible_us < 0 ? t_us : visible_us, .value = value, .raw = raw,
        .error = error };
}

struct FrameSpec {
    int64_t t_us = 0;
    CanFrame frame;
};

FrameSpec Frame(
    int64_t t_us,
    uint32_t id,
    bool is_extended,
    bool is_transmit,
    bool is_fd,
    bool is_brs,
    size_t length,
    uint8_t seed
) {

    FrameSpec spec;
    spec.t_us = t_us;
    spec.frame.id = id;
    spec.frame.is_extended = is_extended;
    spec.frame.is_transmit = is_transmit;
    spec.frame.is_can_fd = is_fd;
    spec.frame.is_bitrate_switch = is_brs;
    spec.frame.data.Resize(length);
    for(size_t i = 0; i < length; i++)
        spec.frame.data[i] = static_cast<uint8_t>(seed + i * 3);
    return spec;
}

FrameSpec FrameBytes(
    int64_t t_us,
    uint32_t id,
    bool is_extended,
    bool is_transmit,
    bool is_fd,
    bool is_brs,
    std::initializer_list<uint8_t> bytes
) {

    FrameSpec spec = Frame(t_us, id, is_extended, is_transmit, is_fd, is_brs, 0, 0);
    spec.frame.data = bytes;
    return spec;
}

// Exact in float: a triangle wave 0..period/2..0, and a small noise from an integer generator.
int Tri(int j, int period = 32) {
    const int p = j % period;
    return p <= period / 2 ? p : period - p;
}

uint32_t Noise(uint32_t& state) {
    state = state * 1664525U + 1013904223U;
    return (state >> 24) & 7U;
}

// sqrt(x) * 2 + 1, the expression of the first sensor, evaluated as the unit does (float result).
float OilPressureValue(float raw) {
    return static_cast<float>(std::sqrt(static_cast<double>(raw)) * 2.0 + 1.0);
}

// ------------------------------------------------------------------------------------------------
// The rig: one logger, one session, ticks as LogWriterService makes them

struct Written {
    uint64_t begin = 0; // offsets into the file
    uint64_t end = 0;
    size_t sensor = 0;
    size_t payload_length = 0; // CAN: the frame's payload
};

struct LoggedFile {
    std::string name;
    int64_t start_rel_us = 0; // the header's start time, microseconds after the session start
    int64_t end_rel_us = 0;   // the last tick written into it (what LogEndTimes keeps)
    uint64_t header_bytes = 0;
    bool finalized = false;
    std::optional<uint32_t> stop_reason; // the LogFileEnd given to StopLogging(): empty for a rotation
    uint32_t overruns = 0;
    uint32_t frames_dropped = 0;
    std::string closed;   // after StopLogging()
    std::string unclosed; // the stream as it was just before StopLogging(): a file after a power loss
    std::vector<Written> written;
};

struct ValueFeed {
    size_t sensor = 0;
    std::vector<SampleSpec> samples; // ordered by visible_us
};

struct CanFeed {
    size_t sensor = 0;
    std::vector<FrameSpec> frames; // ordered by t_us
    size_t next = 0;
};

// What StopLogging() is told of the end of a file.
struct EndSpec {
    std::optional<LoggingStatusReason> reason; // empty: a rotation
    uint32_t overruns = 0;
    uint32_t frames_dropped = 0;
};

class Rig {
public:
    std::vector<SensorSpec> specs;
    std::vector<std::shared_ptr<Sensor>> sensors;
    MetadataSpec metadata;
    Attachments attachments;
    std::shared_ptr<LogSession> session;
    EndSpec rotation_end{ std::nullopt, 0, 0 };
    EndSpec final_end{ LoggingStatusReason::Stopped, 0, 0 };
    uint32_t interval_ms = 100;
    int64_t origin_us = 0; // unit clock, microseconds: where the first file's header starts

    std::vector<ValueFeed> values;
    std::vector<CanFeed> cans;

    uint64_t max_bytes = 0; // 0 never rotates
    std::vector<LoggedFile> files;
    std::vector<std::string> calls;
    std::vector<int64_t> tick_times;
    std::vector<uint64_t> tick_bytes;
    int rotations = 0;

    Rig(std::vector<SensorSpec> sensor_specs, uint32_t interval, MetadataSpec metadata_spec, int64_t origin) :
        specs(std::move(sensor_specs)),
        metadata(std::move(metadata_spec)),
        interval_ms(interval),
        origin_us(origin) {

        for(const auto& spec : specs)
            sensors.push_back(MakeSensor(spec));

        configuration_ = std::make_unique<LoggingConfiguration>(MakeConfiguration(specs, sensors, interval_ms));
        attachments = MakeAttachments(sensors, *configuration_, metadata.canbus_variant);
        session = MakeLogSession(metadata);
        logger_ = std::make_unique<Mdf4LoggerSensorReading>(
            *configuration_, sensors, ToLoggerMetadata(metadata, session, attachments)
        );
    }

    void Start() {
        stream_ = std::make_unique<MemoryStreamBuf>();
        BeginFile(0);
    }

    void Tick(int64_t tick_us) {
        tick_no_++;

        // Before the snapshot, so every reading of this tick goes into the new file.
        if(rotation_due_)
            Rotate();

        last_tick_us_ = tick_us;
        const TimePoint now = At(origin_us + tick_us);

        for(const auto& feed : values) {
            const SampleSpec* sample = Latest(feed, tick_us);
            if(sample == nullptr)
                continue;

            SensorReading reading(sensors[feed.sensor].get());
            reading.value = sample->value;
            reading.raw_value = sample->raw;
            reading.timestamp = At(origin_us + sample->t_us);
            reading.source = ReadingSource::PROCESSING;
            reading.status = ReadingStatus::PROCESSED;
            if(sample->error != ReadingError::NONE)
                reading.SetError(sample->error);

            const uint64_t before = logger_->GetBytesWritten();
            const bool logged = logger_->LogReading(now, reading);
            const uint64_t after = logger_->GetBytesWritten();
            zassert_equal(logged, after > before, "LogReading's answer and the byte count disagree");

            if(logged)
                files.back().written.push_back(Written{ .begin = before, .end = after, .sensor = feed.sensor });

            calls.push_back(
                "{\"kind\":\"value\",\"tick\":" + Num(tick_no_) + ",\"file\":" + Num(files.size() - 1)
                + ",\"tick_us\":" + Num(tick_us) + ",\"sensor\":" + Q(specs[feed.sensor].id)
                + ",\"ts_us\":" + Num(sample->t_us) + ",\"value\":" + NumOrNull(sample->value)
                + ",\"raw\":" + NumOrNull(sample->raw) + ",\"error\":" + Q(ErrorName(sample->error))
                + ",\"logged\":" + Bool(logged) + ",\"bytes_after\":" + Num(after) + "}"
            );
        }

        for(auto& feed : cans) {
            while(feed.next < feed.frames.size() && feed.frames[feed.next].t_us <= tick_us) {
                const FrameSpec& spec = feed.frames[feed.next++];

                SensorReading reading(sensors[feed.sensor].get());
                reading.source = ReadingSource::ISR;
                reading.status = ReadingStatus::PROCESSED;
                reading.timestamp = At(origin_us + spec.t_us);
                reading.can_frame = &spec.frame;

                const uint64_t before = logger_->GetBytesWritten();
                const bool logged = logger_->LogReading(now, reading);
                const uint64_t after = logger_->GetBytesWritten();
                zassert_true(logged, "a CAN frame must be logged");

                files.back().written.push_back(Written{
                    .begin = before, .end = after, .sensor = feed.sensor, .payload_length = spec.frame.data.size() });

                const std::string payload = std::string(
                    reinterpret_cast<const char*>(spec.frame.data.data()), spec.frame.data.size()
                );
                calls.push_back(
                    "{\"kind\":\"can\",\"tick\":" + Num(tick_no_) + ",\"file\":" + Num(files.size() - 1)
                    + ",\"tick_us\":" + Num(tick_us) + ",\"sensor\":" + Q(specs[feed.sensor].id)
                    + ",\"ts_us\":" + Num(spec.t_us) + ",\"id\":" + Num(spec.frame.id)
                    + ",\"extended\":" + Bool(spec.frame.is_extended) + ",\"transmit\":" + Bool(spec.frame.is_transmit)
                    + ",\"fd\":" + Bool(spec.frame.is_can_fd) + ",\"brs\":" + Bool(spec.frame.is_bitrate_switch)
                    + ",\"length\":" + Num(spec.frame.data.size()) + ",\"data\":" + Q(Hex(payload))
                    + ",\"logged\":true,\"bytes_after\":" + Num(after) + "}"
                );
            }
        }

        rotation_due_ = max_bytes > 0 && logger_->GetBytesWritten() >= max_bytes;
        tick_times.push_back(tick_us);
        tick_bytes.push_back(logger_->GetBytesWritten());
    }

    // The session's end: closes the file that is open.
    void Stop() {
        FinishFile(final_end);
    }

private:
    std::unique_ptr<LoggingConfiguration> configuration_;
    std::unique_ptr<Mdf4LoggerSensorReading> logger_;
    std::unique_ptr<MemoryStreamBuf> stream_;
    int tick_no_ = 0;
    int64_t last_tick_us_ = 0;
    bool rotation_due_ = false;

    static const SampleSpec* Latest(const ValueFeed& feed, int64_t tick_us) {
        const SampleSpec* best = nullptr;
        for(const auto& sample : feed.samples) {
            if(sample.visible_us > tick_us)
                break;
            best = &sample;
        }
        return best;
    }

    void BeginFile(int64_t start_rel_us) {
        files.emplace_back();
        LoggedFile& file = files.back();
        file.start_rel_us = start_rel_us;

        std::optional<Clock::duration> offset;
        if(metadata.wall_clock_offset_us.has_value()) {
            offset = std::chrono::duration_cast<Clock::duration>(std::chrono::microseconds(*metadata.wall_clock_offset_us));
        }
        const bool started = logger_->StartLogging(
            *stream_, LogFileStart{ .time = At(origin_us + start_rel_us), .wall_clock_offset = offset }
        );
        zassert_true(started, "StartLogging failed");
        file.header_bytes = logger_->GetBytesWritten();
    }

    void FinishFile(const EndSpec& end_spec) {
        LoggedFile& file = files.back();
        // RecordEndTime(): the last tick written into the file; a file closed before its first tick
        // ends where it began.
        file.end_rel_us = std::max(last_tick_us_, file.start_rel_us);
        file.stop_reason = end_spec.reason.has_value() ? std::optional<uint32_t>(static_cast<uint32_t>(*end_spec.reason))
                                                       : std::nullopt;
        file.overruns = end_spec.overruns;
        file.frames_dropped = end_spec.frames_dropped;

        const LogFileEnd end{ .time = At(origin_us + file.end_rel_us),
            .stop_reason = end_spec.reason,
            .overruns = end_spec.overruns,
            .frames_dropped = end_spec.frames_dropped };

        file.unclosed = stream_->Str();
        file.finalized = logger_->StopLogging(end);
        file.closed = stream_->Str();
        zassert_true(file.finalized, "StopLogging failed");
    }

    // LogWriterService::RotateFile(): close the full file, open the next one at the time of the last tick.
    void Rotate() {
        FinishFile(rotation_end);
        stream_ = std::make_unique<MemoryStreamBuf>();
        BeginFile(last_tick_us_);
        rotation_due_ = false;
        rotations++;
    }
};

// A tick's time: the nominal period plus the kernel-tick jitter a real writer shows (0 to 800 us).
int64_t TickTime(int k, int64_t interval_us = 100'000) {
    return static_cast<int64_t>(k) * interval_us + static_cast<int64_t>((k * 37) % 9) * TICK_JITTER_STEP_US;
}

// Sensor 1: a reading every 40 ms (13 ms, 53 ms, ...), the expression applied to the raw volts. A stall
// from 1.55 s to 1.9 s leaves the ticks inside it holding the last sample (rewritten at the tick time),
// and one sample taken at 1.653 s is committed only at 1.745 s, after the tick of 1.7 s repeated the
// sample before it: the logger moves it to just after that repeat. A tick logs only the latest sample, so
// the three invalid readings are placed where a tick sees them (the latest sample of tick k is j = 5m - 1
// for k = 2m and 5m + 2 for k = 2m + 1): an expression failure at j = 14 (value invalid, raw valid), a
// missing raw value at j = 62 and neither value at j = 87.
std::vector<SampleSpec> OilPressureSamples() {
    std::vector<SampleSpec> samples;
    uint32_t noise = 12345;
    std::optional<float> previous_value;

    for(int j = 0; j < 100; j++) {
        const int64_t t = 13'000 + 40'000LL * j;
        const float raw = 0.75F + static_cast<float>(Tri(j)) * 0.0625F + static_cast<float>(Noise(noise)) / 512.0F;
        const float value = OilPressureValue(raw);

        const bool is_stall = j >= 39 && j <= 46;
        if(j == 41) {
            // The late sample: taken at 1.653 s, committed at 1.745 s.
            samples.push_back(Sample(t, value, raw, ReadingError::NONE, 1'745'000));
        } else if(!is_stall) {
            if(j == 14)
                samples.push_back(Sample(t, previous_value, raw, ReadingError::EXPRESSION_FAILED));
            else if(j == 62)
                samples.push_back(Sample(t, value, std::nullopt));
            else if(j == 87)
                samples.push_back(Sample(t, std::nullopt, std::nullopt));
            else
                samples.push_back(Sample(t, value, raw));
        }

        previous_value = value;
    }

    std::ranges::stable_sort(samples, {}, &SampleSpec::visible_us);
    return samples;
}

// Sensor 2: an indicator, a new reading only when it changes (or fails).
std::vector<SampleSpec> IgnitionSamples() {
    return { Sample(5'000, 0.0F), Sample(352'000, 1.0F), Sample(1'208'000, 0.0F), Sample(1'633'000, 1.0F),
        Sample(2'410'000, std::nullopt, std::nullopt, ReadingError::READER_FAILED), Sample(2'950'000, 1.0F),
        Sample(3'290'000, 0.0F) };
}

// Sensor 3: a virtual sensor that computes a value every 250 ms; one computation fails.
std::vector<SampleSpec> EnginePowerSamples(int64_t offset_us) {
    std::vector<SampleSpec> samples;
    std::optional<float> previous_value;
    for(int j = 0; j < 16; j++) {
        const float value = 40.0F + static_cast<float>(Tri(j * 3)) * 2.5F;
        if(j == 6)
            samples.push_back(Sample(offset_us + 250'000LL * j, previous_value, std::nullopt, ReadingError::EXPRESSION_FAILED));
        else
            samples.push_back(Sample(offset_us + 250'000LL * j, value));
        previous_value = value;
    }
    return samples;
}

// The CAN frames of the CAN fixtures, by the time they arrive.
std::vector<FrameSpec> CanFrames(int variant) {
    std::vector<FrameSpec> frames;

    // Standard, extended and FD frames, every length class, both directions, a shared timestamp.
    frames.push_back(FrameBytes(12'000, 0x123, false, false, false, false, { 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88 }));
    frames.push_back(FrameBytes(34'000, 0x7FF, false, false, false, false, {}));
    frames.push_back(FrameBytes(34'000, 0x100, false, true, false, false, { 0xAA, 0xBB, 0xCC }));
    frames.push_back(FrameBytes(151'000, 0x18FEF100, true, false, false, false, { 0xDE, 0xAD, 0xBE, 0xEF, 0x00, 0x01, 0x02, 0x03 }));
    frames.push_back(FrameBytes(203'500, 0x1FFFFFFF, true, false, false, false, { 0xFF, 0x00 }));
    frames.push_back(Frame(310'000, 0x321, false, false, true, true, 12, 0x10));
    frames.push_back(Frame(451'000, 0x18DAF110, true, false, true, true, 64, 0x00));
    frames.push_back(Frame(470'000, 0x400, false, false, true, false, 10, 0x40)); // 10 bytes: DLC 9, DataLength 10
    frames.push_back(Frame(622'000, 0x555, false, true, true, true, 16, 0x50));
    frames.push_back(Frame(640'000, 0x556, false, false, true, true, 20, 0x60));
    frames.push_back(Frame(655'000, 0x557, false, false, true, true, 24, 0x70));
    frames.push_back(Frame(700'000, 0x558, false, false, true, true, 32, 0x80));
    frames.push_back(Frame(702'000, 0x559, false, false, true, false, 48, 0x90));
    frames.push_back(Frame(705'000, 0x1ABCDEF0, true, false, true, false, 7, 0xA0)); // FD with a classic-sized payload
    frames.push_back(FrameBytes(910'000, 0x001, false, false, false, false, { 0x5A }));
    frames.push_back(FrameBytes(1'230'000, 0x080, false, false, false, false, { 0x01, 0x02, 0x03, 0x04 }));

    // Periodic traffic: 0x180 every 50 ms from 1.3 s to 2.3 s, a counter in the first byte.
    for(int i = 0; i < 21; i++) {
        FrameSpec spec = Frame(1'300'000 + 50'000LL * i, 0x180, false, false, false, false, 8, 0);
        spec.frame.data[0] = static_cast<uint8_t>(i);
        frames.push_back(spec);
    }

    // The last frame of the session: 20 bytes, so a cut inside it is well inside its payload.
    frames.push_back(Frame(2'940'000, 0x18DB33F1, true, false, true, true, 20, 0xC0));

    if(variant == 1) {
        // The second CAN sensor's traffic: fewer frames, other IDs.
        frames.clear();
        frames.push_back(Frame(70'000, 0x200, false, false, false, false, 8, 0x01));
        frames.push_back(Frame(70'000, 0x201, false, false, false, false, 8, 0x02));
        frames.push_back(Frame(430'000, 0x1CEBFF00, true, false, true, true, 24, 0x03));
        frames.push_back(Frame(1'100'000, 0x200, false, true, false, false, 8, 0x04));
        frames.push_back(Frame(1'900'000, 0x202, false, false, true, false, 12, 0x05));
    } else if(variant == 2) {
        // A sensor with no known bus: three frames.
        frames.clear();
        frames.push_back(Frame(250'000, 0x300, false, false, false, false, 3, 0x07));
        frames.push_back(Frame(1'450'000, 0x301, true, false, false, false, 5, 0x08));
        frames.push_back(Frame(2'250'000, 0x302, false, false, true, true, 16, 0x09));
    }

    return frames;
}

// ------------------------------------------------------------------------------------------------
// Manifests

struct OutFile {
    std::string name;
    std::string role;
    std::string description;
    std::string derived_from;   // name of the file it is a copy or cut of
    std::string cut;            // JSON object, or empty
    size_t size = 0;
    int64_t start_rel_us = -1;
    int64_t end_rel_us = -1;
    uint64_t header_bytes = 0;
    std::string spans;          // JSON array of [begin, end, sensor index] of the records
    std::string end;            // JSON object: what StopLogging() was told, or empty
    bool is_finalized = false;
};

std::string SpansJson(const LoggedFile& file, uint64_t limit) {
    std::vector<std::string> items;
    for(const auto& w : file.written) {
        if(w.end <= limit) {
            items.push_back(
                "[" + Num(w.begin) + "," + Num(w.end) + "," + Num(w.sensor) + "," + Num(w.payload_length) + "]"
            );
        }
    }
    return "[" + Join(items, ",") + "]";
}

std::string OutFileJson(const OutFile& f) {
    return "{\"name\":" + Q(f.name) + ",\"role\":" + Q(f.role) + ",\"description\":" + Q(f.description)
        + ",\"derived_from\":" + (f.derived_from.empty() ? "null" : Q(f.derived_from)) + ",\"cut\":"
        + (f.cut.empty() ? "null" : f.cut) + ",\"size\":" + Num(f.size) + ",\"start_rel_us\":"
        + (f.start_rel_us < 0 ? "null" : Num(f.start_rel_us)) + ",\"end_rel_us\":"
        + (f.end_rel_us < 0 ? "null" : Num(f.end_rel_us)) + ",\"header_bytes\":" + Num(f.header_bytes)
        + ",\"finalized\":" + Bool(f.is_finalized) + ",\"end\":" + (f.end.empty() ? "null" : f.end) + ",\"record_spans\":" + (f.spans.empty() ? "[]" : f.spans) + "}";
}

std::string SourceJson(const SensorSpec& s) {
    const auto e = ExpectedSourceOf(s);
    if(e.name.empty() || !HasChannelGroup(s))
        return "null";
    return "{\"name\":" + Q(e.name) + ",\"source_type\":" + Num(e.source_type) + ",\"bus_type\":"
        + Num(e.bus_type) + "}";
}

std::string LimitsJson(const SensorSpec& s) {
    const auto limits = ExpectedLimitsOf(s);
    if(!limits.has_value() || !HasChannelGroup(s))
        return "null";
    return "[" + Num(limits->first) + "," + Num(limits->second) + "]";
}

std::string SensorsJson(const Rig& rig) {
    std::vector<std::string> items;
    for(size_t i = 0; i < rig.specs.size(); i++) {
        const auto& s = rig.specs[i];
        const auto formula = Mdf4LoggerSensorReading::ToMdfFormula(s.expression);
        items.push_back(
            "{\"index\":" + Num(i) + ",\"id\":" + Q(s.id) + ",\"type\":" + Q(TypeName(s.type)) + ",\"name\":"
            + Q(s.name) + ",\"unit\":" + Q(s.unit) + ",\"description\":" + Q(s.description) + ",\"expression\":"
            + Q(s.expression) + ",\"sensor_enabled\":" + Bool(s.is_enabled) + ",\"logging_enabled\":"
            + Bool(s.log_enabled) + ",\"log_raw_value\":" + Bool(s.log_raw) + ",\"log_only_new_data\":"
            + Bool(s.only_new) + ",\"bus_channel\":"
            + (s.bus_channel.has_value() ? Num(*s.bus_channel) : "null") + ",\"has_channel_group\":"
            + Bool(HasChannelGroup(s)) + ",\"has_raw_channel\":" + Bool(HasRawChannel(s))
            + ",\"mdf_formula\":" + (formula.has_value() && !s.expression.empty() ? Q(*formula) : "null")
            + ",\"expected_source\":" + SourceJson(s) + ",\"expected_limits\":" + LimitsJson(s)
            + ",\"sampling_rate_ms\":" + (s.sampling_ms.has_value() ? Num(*s.sampling_ms) : "null")
            + ",\"script_path\":" + (s.script_path.empty() ? "null" : Q(s.script_path)) + "}"
        );
    }
    return "[" + Join(items, ",\n") + "]";
}

std::string MetadataJson(const Rig& rig) {
    const MetadataSpec& m = rig.metadata;
    return "{\"device_name\":" + Q(m.device_name) + ",\"tool_id\":" + Q(m.tool_id) + ",\"tool_version\":"
        + Q(m.tool_version) + ",\"product_id\":" + Num(m.product_id) + ",\"device_id\":" + Num(m.device_id)
        + ",\"unit_uid\":" + Num(m.unit_uid) + ",\"boot_id\":" + Num(m.boot_id) + ",\"session_id\":"
        + Num(m.session_id) + ",\"start_cause\":" + Q(GetStartCauseName(m.start_cause))
        + ",\"hw_version\":" + Num(m.hw_version) + ",\"sw_version\":" + Num(m.sw_version)
        + ",\"sensors_configuration_hash\":" + Num(rig.attachments.sensors_crc) + ",\"clock\":"
        + Q(m.wall_clock_offset_us.has_value() ? "set" : "uptime") + ",\"wall_clock_offset_us\":"
        + (m.wall_clock_offset_us.has_value() ? Num(*m.wall_clock_offset_us) : "null") + ",\"hd_time_class\":"
        + Num(m.wall_clock_offset_us.has_value() ? 10 : 0) + "}";
}

std::string AttachmentsJson(const Rig& rig) {
    std::vector<std::string> items;
    for(const auto& a : rig.attachments.list) {
        std::vector<std::string> properties;
        for(const auto& p : a.properties)
            properties.push_back("[" + Q(p.name) + "," + Q(p.value) + "]");
        const std::string bytes(reinterpret_cast<const char*>(a.bytes.data()), a.bytes.size());
        items.push_back("{\"file_name\":" + Q(a.file_name) + ",\"mime_type\":" + Q(a.mime_type)
            + ",\"comment_text\":" + Q(a.comment_text) + ",\"properties\":[" + Join(properties, ",")
            + "],\"size\":" + Num(a.bytes.size()) + ",\"crc32\":" + Num(crc32_ieee(a.bytes.data(), a.bytes.size()))
            + ",\"bytes_hex\":" + Q(Hex(bytes)) + "}");
    }
    return "[" + Join(items, ",\n") + "]";
}

std::string FileEndJson(const LoggedFile& f) {
    return "{\"stop_reason\":" + (f.stop_reason.has_value() ? Num(*f.stop_reason) : "null") + ",\"overruns\":"
        + Num(f.overruns) + ",\"frames_dropped\":" + Num(f.frames_dropped) + "}";
}

void WriteManifest(
    const std::string& family,
    const std::string& description,
    const Rig& rig,
    const std::vector<OutFile>& out_files,
    const std::string& extra = ""
) {

    std::vector<std::string> files;
    for(const auto& f : out_files)
        files.push_back(OutFileJson(f));

    std::vector<std::string> ticks;
    for(const auto t : rig.tick_times)
        ticks.push_back(Num(t));

    const std::string json = "{\"family\":" + Q(family) + ",\"description\":" + Q(description)
        + ",\"logging_interval_ms\":" + Num(rig.interval_ms) + ",\"origin_us\":" + Num(rig.origin_us)
        + ",\"origin_note\":\"unit-clock microseconds of the first file's header start (RtcProvider's fixed date plus uptime); every *_rel_us is microseconds after it\""
        + ",\"metadata\":" + MetadataJson(rig) + ",\"attachments\":" + AttachmentsJson(rig) + ",\"sensors\":" + SensorsJson(rig)
        + ",\"tick_times_rel_us\":[" + Join(ticks, ",") + "],\"rotations\":" + Num(rig.rotations)
        + (extra.empty() ? "" : "," + extra) + ",\n\"files\":[\n" + Join(files) + "\n],\n\"calls\":[\n"
        + Join(rig.calls) + "\n]}\n";

    WriteFile("manifest_" + family + ".json", json);
}

OutFile Emit(
    const LoggedFile& source,
    const std::string& name,
    const std::string& bytes,
    const std::string& role,
    const std::string& description,
    bool is_finalized
) {

    WriteFile(name, bytes);

    OutFile out;
    out.name = name;
    out.role = role;
    out.description = description;
    out.size = bytes.size();
    out.start_rel_us = source.start_rel_us;
    out.end_rel_us = source.end_rel_us;
    out.header_bytes = source.header_bytes;
    out.spans = SpansJson(source, bytes.size());
    out.is_finalized = is_finalized;
    if(is_finalized)
        out.end = FileEndJson(source);
    return out;
}

// ------------------------------------------------------------------------------------------------
// The sensors of the cbff74e fixtures

constexpr float VOLTAGE_STEP = 0.0625F; // 1/16: exact in float

SensorSpec OilPressure() {
    SensorSpec oil;
    oil.id = "oil_pressure";
    oil.type = SensorType::PHYSICAL_ANALOG;
    oil.name = "Oil pressure";
    oil.unit = "bar";
    oil.description = "sump, before the filter";
    oil.expression = "sqrt(x) * 2 + 1"; // an expression: no limits, whatever the table
    oil.log_raw = true;
    oil.channel = 1;
    oil.method = InterpolationMethod::LINEAR;
    oil.table = { { 0.5F, 0.0F }, { 4.5F, 10.0F } };
    oil.sampling_ms = 40;
    return oil;
}

SensorSpec Ignition() {
    SensorSpec ignition;
    ignition.id = "ignition";
    ignition.type = SensorType::PHYSICAL_INDICATOR; // no script: limits 0 and 1
    ignition.name = "Ignition";
    ignition.description = "key switch";
    ignition.log_raw = true; // an indicator without an expression has no raw value to log
    ignition.channel = 0;
    return ignition;
}

SensorSpec Virtual(std::string id, std::string name, std::string unit, std::string description, std::string expression) {
    SensorSpec virtual_sensor;
    virtual_sensor.id = std::move(id);
    virtual_sensor.type = SensorType::VIRTUAL_ANALOG;
    virtual_sensor.name = std::move(name);
    virtual_sensor.unit = std::move(unit);
    virtual_sensor.description = std::move(description);
    virtual_sensor.expression = std::move(expression);
    virtual_sensor.only_new = true;
    return virtual_sensor;
}

SensorSpec CoolantTemperature() {
    SensorSpec coolant;
    coolant.id = "coolant_temp";
    coolant.type = SensorType::PHYSICAL_ANALOG; // a linear table, no expression, no script: limits = the table's values
    coolant.name = "Coolant temperature";
    coolant.unit = "\xC2\xB0" "C";
    coolant.description = "radiator outlet";
    coolant.channel = 0;
    coolant.method = InterpolationMethod::LINEAR;
    // Given unsorted: the serializer stores a table sorted by voltage; the limits are its extreme values.
    coolant.table = { { 2.5F, 60.0F }, { 0.5F, -20.0F }, { 4.5F, 140.0F }, { 1.5F, 20.0F }, { 3.5F, 100.0F } };
    coolant.sampling_ms = 100;
    return coolant;
}

SensorSpec FuelLevel() {
    SensorSpec fuel;
    fuel.id = "fuel_level";
    fuel.type = SensorType::PHYSICAL_ANALOG; // a cubic spline can overshoot between its points: no limits
    fuel.name = "Fuel level";
    fuel.unit = "%";
    fuel.description = "tank sender";
    fuel.channel = 2;
    fuel.method = InterpolationMethod::CUBIC_SPLINE;
    fuel.table = { { 0.5F, 0.0F }, { 1.0F, 12.5F }, { 2.0F, 50.0F }, { 3.0F, 80.0F }, { 4.5F, 100.0F } };
    fuel.sampling_ms = 200;
    return fuel;
}

SensorSpec EngineSpeed() {
    SensorSpec rpm;
    rpm.id = "rpm";
    rpm.type = SensorType::CANBUS_ANALOG;
    rpm.name = "Engine speed";
    rpm.unit = "rpm";
    rpm.description = "ENGINE on CAN 0";
    rpm.bus_channel = 0;
    rpm.frame_id = 0x316;
    rpm.signal = "RPM";
    return rpm;
}

SensorSpec BrakePedal() {
    SensorSpec brake;
    brake.id = "brake";
    brake.type = SensorType::CANBUS_INDICATOR; // limits 0 and 1
    brake.name = "Brake pedal";
    brake.description = "BRAKES on CAN 0";
    brake.bus_channel = 0;
    brake.frame_id = 0x2A0;
    brake.signal = "BRAKE";
    return brake;
}

SensorSpec Boost() {
    SensorSpec boost;
    boost.id = "boost";
    boost.type = SensorType::USER_ANALOG; // produced by a Lua script (the logger never runs it)
    boost.name = "Boost pressure";
    boost.unit = "bar";
    boost.description = "from a script";
    boost.script_path = "scripts/sensors/boost.lua";
    boost.sampling_ms = 50;
    return boost;
}

SensorSpec ShiftLight() {
    SensorSpec shift;
    shift.id = "shift_light";
    shift.type = SensorType::USER_INDICATOR; // an indicator, but a script produces it: no limits
    shift.name = "Shift light";
    shift.description = "from a script";
    shift.script_path = "scripts/sensors/shift.lua";
    shift.sampling_ms = 100;
    return shift;
}

SensorSpec LaunchControl() {
    SensorSpec launch;
    launch.id = "launch";
    launch.type = SensorType::CONTROL_INDICATOR; // a button on the gauge: limits 0 and 1
    launch.name = "Launch control";
    launch.description = "gauge button";
    return launch;
}

SensorSpec RawCan(uint8_t bus, const char* description) {
    SensorSpec can;
    can.id = "can_raw";
    can.type = SensorType::CANBUS_RAW;
    can.name = "Raw frames";
    can.description = description;
    can.bus_channel = bus;
    return can;
}

// ---- readings, by hand ----

// A sample of the form (value, time) every period_us from offset_us, count of them.
template<typename Fn>
std::vector<SampleSpec> Periodic(int64_t offset_us, int64_t period_us, int count, Fn&& value_of) {
    std::vector<SampleSpec> samples;
    for(int j = 0; j < count; j++)
        samples.push_back(Sample(offset_us + period_us * j, value_of(j)));
    return samples;
}

// Volts 1.0 to 1.75, in steps of 1/16: the table's slope is 40 per volt, so the value is exact.
std::vector<SampleSpec> CoolantSamples() {
    std::vector<SampleSpec> samples;
    for(int j = 0; j < 40; j++) {
        const float volts = 1.0F + static_cast<float>(Tri(j, 24)) * VOLTAGE_STEP;
        const int64_t t = 31'000 + 100'000LL * j;
        if(j == 17)
            samples.push_back(Sample(t, std::nullopt, std::nullopt, ReadingError::NO_VALUE)); // the sender failed
        else
            samples.push_back(Sample(t, 40.0F * volts - 40.0F));
    }
    return samples;
}

std::vector<SampleSpec> FuelSamples() {
    return Periodic(55'000, 200'000, 20, [](int j) { return std::optional<float>(85.0F - 0.25F * static_cast<float>(j)); });
}

std::vector<SampleSpec> RpmSamples() {
    return Periodic(7'000, 20'000, 200, [](int j) { return std::optional<float>(900.0F + 25.0F * static_cast<float>(Tri(j, 40))); });
}

std::vector<SampleSpec> BrakeSamples() {
    return { Sample(9'000, 0.0F), Sample(1'200'000, 1.0F), Sample(1'900'000, 0.0F), Sample(3'100'000, 1.0F),
        Sample(3'600'000, 0.0F) };
}

std::vector<SampleSpec> BoostSamples() {
    return Periodic(3'000, 50'000, 80, [](int j) { return std::optional<float>(0.8F + VOLTAGE_STEP * static_cast<float>(Tri(j, 16))); });
}

std::vector<SampleSpec> ShiftSamples() {
    return { Sample(11'000, 0.0F), Sample(2'250'000, 1.0F), Sample(2'600'000, 0.0F) };
}

std::vector<SampleSpec> LaunchSamples() {
    return { Sample(2'000, 0.0F), Sample(2'000'000, 1.0F), Sample(2'500'000, 0.0F) };
}

// ------------------------------------------------------------------------------------------------
// Families

constexpr int VALUES_TICKS = 40;
constexpr int64_t SESSION_ORIGIN_US = UNIT_CLOCK_ORIGIN_US + 42'317'500; // 42.3175 s after power-on
// The unit clock set to real time: 2026-11-07T10:00:00Z is where the first file starts.
constexpr int64_t VALUES_REAL_START_US = 1'794'045'600LL * 1'000'000;
// 2026-11-07T12:15:30Z
constexpr int64_t NOSPACE_REAL_START_US = (1'794'045'600LL + 2 * 3600 + 15 * 60 + 30) * 1'000'000;

// The three counts a stop passes through LogFileEnd: what the EV block carries.
constexpr uint32_t VALUES_OVERRUNS = 3;
constexpr uint32_t VALUES_FRAMES_DROPPED = 11;

void BuildValues() {
    MetadataSpec metadata;
    metadata.device_id = 5;
    metadata.start_cause = LoggingStartCause::Smp;
    metadata.wall_clock_offset_us = VALUES_REAL_START_US - SESSION_ORIGIN_US;

    std::vector<SensorSpec> specs = { OilPressure(), Ignition(),
        Virtual("engine_power", "Engine power", "kW", "oil pressure times engine speed", "oil_pressure * rpm / 100"),
        CoolantTemperature(), FuelLevel(), EngineSpeed(), BrakePedal(), Boost(), ShiftLight(), LaunchControl() };

    Rig rig(specs, 100, metadata, SESSION_ORIGIN_US);
    rig.final_end = EndSpec{ LoggingStatusReason::Stopped, VALUES_OVERRUNS, VALUES_FRAMES_DROPPED };
    rig.values.push_back(ValueFeed{ 0, OilPressureSamples() });
    rig.values.push_back(ValueFeed{ 1, IgnitionSamples() });
    rig.values.push_back(ValueFeed{ 2, EnginePowerSamples(17'000) });
    rig.values.push_back(ValueFeed{ 3, CoolantSamples() });
    rig.values.push_back(ValueFeed{ 4, FuelSamples() });
    rig.values.push_back(ValueFeed{ 5, RpmSamples() });
    rig.values.push_back(ValueFeed{ 6, BrakeSamples() });
    rig.values.push_back(ValueFeed{ 7, BoostSamples() });
    rig.values.push_back(ValueFeed{ 8, ShiftSamples() });
    rig.values.push_back(ValueFeed{ 9, LaunchSamples() });

    rig.Start();
    for(int k = 1; k <= VALUES_TICKS; k++)
        rig.Tick(TickTime(k));
    rig.Stop();

    const LoggedFile& file = rig.files.at(0);
    zassert_true(file.written.size() > 10, "records");

    std::vector<OutFile> out;
    out.push_back(Emit(file, "values.mf4", file.closed, "closed",
        "Ten sensors on a set clock (2026-11-07T10:00:00Z, time class 10): an analog input with a linear table and sqrt(x) * 2 + 1 (with its raw value, no limits), an indicator (limits 0 and 1), a virtual sensor with Only new data, a coolant sensor with a linear table and no expression (limits -20 and 140), a fuel sensor with a cubic table (no limits), two CAN signal sensors (an analog one, and an indicator with limits 0 and 1), a script analog sensor, a script indicator (no limits) and a control indicator (limits 0 and 1); 40 ticks of 100 ms, the four stored configurations attached, closed by StopLogging() with the stop event (overruns 3, frames dropped 11).", true));
    out.push_back(Emit(file, "values_unclosed.mf4", file.unclosed, "unclosed",
        "The same session as values.mf4 without StopLogging(): the stream just before the stop (UnFinMF, no stop event). The attachments are in its header all the same.", false));
    out.back().derived_from = "values.mf4";

    WriteManifest("values", "Ten sensors on a set clock, with attachments, limits, sources and a stop event; and its unclosed copy.", rig, out);
}

// ---- a raw CAN frame sensor ----

constexpr int CAN_TICKS = 30;

void BuildCan() {
    MetadataSpec metadata;
    metadata.device_id = 0; // not claimed on the CDMP network yet
    metadata.boot_id = 1170331054U;
    metadata.session_id = 61880213U;
    metadata.start_cause = LoggingStartCause::Shell;
    metadata.canbus_variant = 1;

    Rig rig({ RawCan(0, "all traffic on CAN 0") }, 100, metadata, SESSION_ORIGIN_US + 3'600'000'000LL);
    rig.final_end = EndSpec{ LoggingStatusReason::Stopped, 0, 4 };
    rig.cans.push_back(CanFeed{ 0, CanFrames(0), 0 });

    rig.Start();
    for(int k = 1; k <= CAN_TICKS; k++)
        rig.Tick(TickTime(k));
    rig.Stop();

    const LoggedFile& file = rig.files.at(0);
    std::vector<OutFile> out;
    out.push_back(Emit(file, "can.mf4", file.closed, "closed",
        "One raw CAN frame sensor (CANBUS_RAW, bus 0, BusChannel 1) on the unit's clock, as the first can.mf4 (38 frames, 30 ticks of 100 ms), now with the CG comment's sensor_type, the attached configurations and the stop event (frames dropped 4).", true));

    WriteManifest("can", "A raw CAN frame sensor: its group's comment properties, the attachments and a stop event.", rig, out);
}

// ---- a session rotated by size, on the unit's clock ----

// Sensors of the rotation session: oil pressure, the ignition, a virtual sensor and a raw CAN sensor on bus 1.
std::vector<SensorSpec> RotationSensors() {
    return { OilPressure(), Ignition(),
        Virtual("oil_kpa", "Oil pressure", "kPa", "in kilopascals", "oil_pressure * 100"), RawCan(1, "all traffic on CAN 1") };
}

constexpr uint32_t ROTATION_FIRST_OVERRUNS = 2;
constexpr uint32_t ROTATION_FIRST_FRAMES_DROPPED = 5;

std::unique_ptr<Rig> RunRotationSession(uint64_t max_bytes, int ticks) {
    MetadataSpec metadata;
    metadata.device_id = 9;
    metadata.boot_id = 1170331054U;
    metadata.session_id = 77310215U;
    metadata.start_cause = LoggingStartCause::Trigger;
    metadata.canbus_variant = 1;

    auto rig = std::make_unique<Rig>(RotationSensors(), 100, metadata, SESSION_ORIGIN_US + 9'000'000'000LL);
    rig->max_bytes = max_bytes;
    rig->rotation_end = EndSpec{ std::nullopt, ROTATION_FIRST_OVERRUNS, ROTATION_FIRST_FRAMES_DROPPED };
    rig->final_end = EndSpec{ LoggingStatusReason::Stopped, 1, 2 };
    rig->values.push_back(ValueFeed{ 0, OilPressureSamples() });
    rig->values.push_back(ValueFeed{ 1, IgnitionSamples() });
    // 150 ms + 250 ms * j: the sample before the rotation (2.15 s) is older than the next file's start.
    rig->values.push_back(ValueFeed{ 2, EnginePowerSamples(150'000) });
    rig->cans.push_back(CanFeed{ 3, CanFrames(0), 0 });

    rig->Start();
    for(int k = 1; k <= ticks; k++)
        rig->Tick(TickTime(k));
    rig->Stop();
    return rig;
}

constexpr int ROTATION_TICKS = 40;
constexpr int ROTATION_AFTER_TICK = 22;

void BuildRotation() {
    // Pass 1 without a limit, to read the file size after the tick the rotation is due at; pass 2
    // rotates when the open file reaches it, which is what the writer's size limit does.
    const auto dry = RunRotationSession(0, ROTATION_TICKS);
    const uint64_t limit = dry->tick_bytes.at(ROTATION_AFTER_TICK - 1);
    zassert_true(dry->tick_bytes.at(ROTATION_AFTER_TICK - 2) < limit, "tick 22 writes bytes");

    const auto rig_ptr = RunRotationSession(limit, ROTATION_TICKS);
    Rig& rig = *rig_ptr;
    zassert_equal(rig.rotations, 1, "one rotation");
    zassert_equal(rig.files.size(), 2U, "two files");

    // Names as the writer makes them: log_data_<UTC start>_<random>.mf4, the start on the unit clock.
    const auto name_of = [&rig](const LoggedFile& file, uint32_t random_value) {
        const auto path = LogFileNaming::GetFilePath(
            "", "log_data", At(rig.origin_us + file.start_rel_us), random_value, "mf4"
        );
        std::string name = path.CStr();
        zassert_true(!name.empty() && name[0] == '/', "path form");
        return name.substr(1);
    };

    LoggedFile& first = rig.files[0];
    LoggedFile& second = rig.files[1];
    first.name = name_of(first, 4417);
    second.name = name_of(second, 9023);

    std::vector<OutFile> out;
    out.push_back(Emit(first, first.name, first.closed, "closed",
        "First file of a rotated session on the unit's clock (clock uptime, time class 0): ticks 1 to 22 of 100 ms, file_index 0, closed by the rotation (StopLogging() with no reason) when it reached the size limit: its event is the rotated one (overruns 2, frames dropped 5).", true));
    out.push_back(Emit(second, second.name, second.closed, "closed",
        "Second file of the session: file_index 1, the same session_id, opened at the time of tick 22, ticks 23 to 40, closed by the end of the session (stop_reason 0, overruns 1, frames dropped 2).", true));

    const std::string extra = "\"rotation\":{\"limit_bytes\":" + Num(limit) + ",\"due_after_tick\":"
        + Num(ROTATION_AFTER_TICK) + ",\"first_file\":" + Q(first.name) + ",\"second_file\":" + Q(second.name)
        + ",\"second_start_rel_us\":" + Num(second.start_rel_us) + ",\"first_end_rel_us\":" + Num(first.end_rel_us)
        + ",\"first_end_ms\":" + Num((rig.origin_us + first.end_rel_us) / 1000)
        + ",\"second_end_ms\":" + Num((rig.origin_us + second.end_rel_us) / 1000) + "}";

    WriteManifest("rotation",
        "One session of four sensors (an analog input with a raw value, an indicator, a virtual sensor with Only new data and a raw CAN sensor on bus 1) in two files made by rotation, on the unit's clock.",
        rig, out, extra);
}

// ---- a session that ends for lack of space ----

void BuildNoSpace() {
    MetadataSpec metadata;
    metadata.device_id = 5;
    metadata.boot_id = 2216008731U;
    metadata.session_id = 5310099U;
    metadata.start_cause = LoggingStartCause::Command;
    metadata.wall_clock_offset_us = NOSPACE_REAL_START_US - (SESSION_ORIGIN_US + 5'400'000'000LL);

    Rig rig({ OilPressure(), Ignition(), CoolantTemperature() }, 100, metadata, SESSION_ORIGIN_US + 5'400'000'000LL);
    rig.final_end = EndSpec{ LoggingStatusReason::NoSpace, 2, 0 };
    rig.values.push_back(ValueFeed{ 0, OilPressureSamples() });
    rig.values.push_back(ValueFeed{ 1, IgnitionSamples() });
    rig.values.push_back(ValueFeed{ 2, CoolantSamples() });

    rig.Start();
    for(int k = 1; k <= 25; k++)
        rig.Tick(TickTime(k));
    rig.Stop();

    std::vector<OutFile> out;
    out.push_back(Emit(rig.files.at(0), "nospace.mf4", rig.files.at(0).closed, "closed",
        "Three sensors on a set clock (2026-11-07T12:15:30Z), 25 ticks, ended because the volume had no room: stop_reason 2, the event's cause is error (name 'Logging stopped: no space'), overruns 2.", true));

    WriteManifest("nospace", "A log stopped for NoSpace.", rig, out);
}

} // namespace

ZTEST(fixtures, test_MakeFixtures) {
    const char* dir = std::getenv("MDF_FIXTURE_OUT");
    if(dir != nullptr && *dir != '\0')
        g_out_dir = dir;

    BuildValues();
    BuildCan();
    BuildRotation();
    BuildNoSpace();

    for(const auto& name : g_written_names)
        printk("FIXTURE %s\n", name.c_str());
}
