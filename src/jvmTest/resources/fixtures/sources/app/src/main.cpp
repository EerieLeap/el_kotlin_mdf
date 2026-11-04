// Scratch generator of the MDF reader's fixtures (log-plots plan, Appendix B).
//
// Logs written by the firmware's own logger, Mdf4LoggerSensorReading (rt_core 5427774), over an
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
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include <eerie_memory.hpp>
#include <zephyr/ztest.h>

#include "domain/logging_domain/loggers/logger_metadata.h"
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
    std::optional<uint8_t> bus_channel;    // CANBUS_RAW: the CAN interface index; none = no source
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

std::shared_ptr<Sensor> MakeSensor(const SensorSpec& spec) {
    auto sensor = std::make_shared<Sensor>(std::allocator_arg, Mrm::GetDefaultPmr(), spec.id);
    sensor->is_enabled = spec.is_enabled;
    sensor->metadata.name = spec.name;
    sensor->metadata.unit = spec.unit;
    sensor->metadata.description = spec.description;
    sensor->configuration.type = spec.type;
    sensor->configuration.expression = spec.expression;

    if(IsCan(spec)) {
        if(spec.bus_channel.has_value()) {
            sensor->configuration.canbus_source = make_unique_pmr<CanbusSource>(
                Mrm::GetDefaultPmr(), *spec.bus_channel, 0x100
            );
        }
        sensor->configuration.UpdateConnectionString();
    } else if(spec.type == SensorType::PHYSICAL_ANALOG || spec.type == SensorType::PHYSICAL_INDICATOR) {
        sensor->configuration.channel = 0;
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

struct MetadataSpec {
    std::string device_name = "esp32s3_pm_hw_02"; // CONFIG_BOARD of the EL PM100
    std::string tool_id = "rt_daq";
    std::string tool_version = "1.3.0";
    uint32_t product_id = 50177; // EL PM100
    uint8_t device_id = 0;
    uint32_t hw_version = (2U << 24) | (1U << 16) | 0U; // 2.1.0
    uint32_t sw_version = (1U << 24) | (3U << 16) | 0U; // 1.3.0, the tool version
    uint32_t sensors_configuration_hash = 0;
};

LoggerMetadata ToLoggerMetadata(const MetadataSpec& spec) {
    LoggerMetadata metadata;
    metadata.device_name = spec.device_name;
    metadata.tool_id = spec.tool_id;
    metadata.tool_version = spec.tool_version;
    metadata.product_id = spec.product_id;
    metadata.device_id = spec.device_id;
    metadata.hw_version = spec.hw_version;
    metadata.sw_version = spec.sw_version;
    metadata.sensors_configuration_hash = spec.sensors_configuration_hash;
    return metadata;
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

class Rig {
public:
    std::vector<SensorSpec> specs;
    std::vector<std::shared_ptr<Sensor>> sensors;
    MetadataSpec metadata;
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
        logger_ = std::make_unique<Mdf4LoggerSensorReading>(*configuration_, sensors, ToLoggerMetadata(metadata));
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
        FinishFile();
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

        const bool started = logger_->StartLogging(*stream_, At(origin_us + start_rel_us));
        zassert_true(started, "StartLogging failed");
        file.header_bytes = logger_->GetBytesWritten();
    }

    void FinishFile() {
        LoggedFile& file = files.back();
        file.unclosed = stream_->Str();
        file.finalized = logger_->StopLogging();
        file.closed = stream_->Str();
        zassert_true(file.finalized, "StopLogging failed");
        // RecordEndTime(): the last tick written into the file; a file closed before its first tick
        // ends where it began.
        file.end_rel_us = std::max(last_tick_us_, file.start_rel_us);
    }

    // LogWriterService::RotateFile(): close the full file, open the next one at the time of the last tick.
    void Rotate() {
        FinishFile();
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

// ------------------------------------------------------------------------------------------------
// The sensors of the main fixtures

std::vector<SensorSpec> ThreeSensors() {
    SensorSpec oil;
    oil.id = "oil_pressure";
    oil.type = SensorType::PHYSICAL_ANALOG;
    oil.name = "Oil pressure";
    oil.unit = "bar";
    oil.description = "sump, before the filter";
    oil.expression = "sqrt(x) * 2 + 1";
    oil.log_raw = true;

    SensorSpec ignition;
    ignition.id = "ignition";
    ignition.type = SensorType::PHYSICAL_INDICATOR;
    ignition.name = "Ignition";
    ignition.description = "key switch";
    ignition.log_raw = true; // an indicator without an expression has no raw value to log

    SensorSpec power;
    power.id = "engine_power";
    power.type = SensorType::VIRTUAL_ANALOG;
    power.name = "Engine power";
    power.unit = "kW";
    power.description = "torque times speed";
    power.only_new = true;

    return { oil, ignition, power };
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
        + ",\"finalized\":" + Bool(f.is_finalized) + ",\"record_spans\":" + (f.spans.empty() ? "[]" : f.spans) + "}";
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
            + ",\"mdf_formula\":" + (formula.has_value() && !s.expression.empty() ? Q(*formula) : "null") + "}"
        );
    }
    return "[" + Join(items, ",\n") + "]";
}

std::string MetadataJson(const MetadataSpec& m) {
    return "{\"device_name\":" + Q(m.device_name) + ",\"tool_id\":" + Q(m.tool_id) + ",\"tool_version\":"
        + Q(m.tool_version) + ",\"product_id\":" + Num(m.product_id) + ",\"device_id\":" + Num(m.device_id)
        + ",\"hw_version\":" + Num(m.hw_version) + ",\"sw_version\":" + Num(m.sw_version)
        + ",\"sensors_configuration_hash\":" + Num(m.sensors_configuration_hash) + "}";
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
        + ",\"metadata\":" + MetadataJson(rig.metadata) + ",\"sensors\":" + SensorsJson(rig)
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
    return out;
}

OutFile EmitCut(
    const LoggedFile& source,
    const std::string& name,
    const std::string& original_name,
    const std::string& original,
    uint64_t length,
    const std::string& role,
    const std::string& description,
    const std::string& cut_json
) {

    zassert_true(length <= original.size(), "a cut cannot be longer than its file");
    OutFile out = Emit(source, name, original.substr(0, length), role, description, original.substr(0, 8) == "MDF     ");
    out.derived_from = original_name;
    out.cut = cut_json;
    return out;
}

std::string CutJson(uint64_t length, const std::string& where, size_t record_from_end, uint64_t bytes_into_record, uint64_t record_length) {
    return "{\"length\":" + Num(length) + ",\"where\":" + Q(where) + ",\"record_from_end\":" + Num(record_from_end)
        + ",\"bytes_of_that_record_kept\":" + Num(bytes_into_record) + ",\"that_record_length\":" + Num(record_length) + "}";
}

// ------------------------------------------------------------------------------------------------
// Fixture 1: three sensors; fixture 3 (values): the same log unclosed and cut

constexpr int VALUES_TICKS = 40;
constexpr int64_t SESSION_ORIGIN_US = UNIT_CLOCK_ORIGIN_US + 42'317'500; // 42.3175 s after power-on

void BuildValues() {
    MetadataSpec metadata;
    metadata.device_id = 5;
    metadata.sensors_configuration_hash = 0x5E4A91C3U;

    Rig rig(ThreeSensors(), 100, metadata, SESSION_ORIGIN_US);
    rig.values.push_back(ValueFeed{ 0, OilPressureSamples() });
    rig.values.push_back(ValueFeed{ 1, IgnitionSamples() });
    rig.values.push_back(ValueFeed{ 2, EnginePowerSamples(17'000) });

    rig.Start();
    for(int k = 1; k <= VALUES_TICKS; k++)
        rig.Tick(TickTime(k));
    rig.Stop();

    const LoggedFile& file = rig.files.at(0);
    const std::string& closed = file.closed;
    const std::string& unclosed = file.unclosed;
    zassert_true(file.written.size() > 10, "records");

    std::vector<OutFile> out;
    out.push_back(Emit(file, "values.mf4", closed, "closed",
        "Three sensors (an analog input with sqrt(x) * 2 + 1 and its raw value, an indicator, a virtual sensor with Only new data), 40 ticks of 100 ms, one session closed by StopLogging().", true));
    out.push_back(Emit(file, "values_unclosed.mf4", unclosed, "unclosed",
        "The same session as values.mf4 without StopLogging(): the file as it is after a power loss (UnFinMF, flags 0x01 0x04 0x20, DT length and cycle counts not patched).", false));
    out.back().derived_from = "values.mf4";

    // Cuts of the unclosed file inside its last records. written.back() is the last record.
    const auto& w = file.written;
    const auto cut = [&](const std::string& name, const std::string& what, size_t from_end, uint64_t keep) {
        const Written& record = w[w.size() - from_end];
        const uint64_t record_length = record.end - record.begin;
        zassert_true(keep <= record_length, "cut inside its record");
        const uint64_t length = record.begin + keep;
        out.push_back(EmitCut(file, name, "values_unclosed.mf4", unclosed, length, "unclosed_cut", what,
            CutJson(length, what, from_end, keep, record_length)));
    };

    cut("values_unclosed_cut_boundary.mf4",
        "Cut exactly at a record boundary: the last record is gone whole, nothing is partial.", 1, 0);
    cut("values_unclosed_cut_id_only.mf4",
        "The last record has only its record ID (1 byte of 14).", 1, 1);
    cut("values_unclosed_cut_mid_time.mf4",
        "The last record is cut inside its Float64 time (ID and 6 of the 8 time bytes).", 1, 7);
    cut("values_unclosed_cut_mid_value.mf4",
        "The last record is cut inside its Float32 value (ID, time and 2 of the 4 value bytes).", 1, 11);
    cut("values_unclosed_cut_no_invalidation.mf4",
        "The last record lacks only its invalidation byte (13 of 14 bytes).", 1, 13);
    cut("values_unclosed_cut_mid_raw.mf4",
        "The record before the last (the analog sensor's, 18 bytes with a raw value) is cut inside its raw value (15 of 18 bytes).", 2, 15);

    out.push_back(EmitCut(file, "values_unclosed_header_only.mf4", "values_unclosed.mf4", unclosed, file.header_bytes,
        "unclosed_cut", "Only the header: a log that was started and stopped before its first record.",
        CutJson(file.header_bytes, "end of the header", 0, 0, 0)));
    out.push_back(EmitCut(file, "values_cut_in_header.mf4", "values_unclosed.mf4", unclosed, file.header_bytes / 2,
        "damaged", "Cut inside the header (half of it): not a readable MDF file, the reader must say it is cut short.",
        CutJson(file.header_bytes / 2, "inside the header", 0, 0, 0)));

    // The closed file cut short, as a download in progress: its identifier says MDF and its DT length
    // says more than the file holds.
    {
        const Written& last = w.back();
        const uint64_t length = last.begin + 7;
        out.push_back(EmitCut(file, "values_closed_cut_mid_record.mf4", "values.mf4", closed, length, "closed_cut",
            "The closed file cut inside its last record, as a download in progress leaves it (identifier MDF, DT length and cycle counts final).",
            CutJson(length, "inside the last record", 1, 7, last.end - last.begin)));
        out.push_back(EmitCut(file, "values_closed_cut_header_only.mf4", "values.mf4", closed, file.header_bytes,
            "closed_cut", "The closed file cut right after the header (all counts final, no record yet).",
            CutJson(file.header_bytes, "end of the header", 0, 0, 0)));
    }

    WriteManifest("values",
        "Fixture 1 (three sensors) and its unclosed and cut copies (fixture 3, values).", rig, out);
}

// ------------------------------------------------------------------------------------------------
// Fixture 2: a raw CAN frame sensor; fixture 3 (CAN): unclosed and cut inside the last frame

constexpr int CAN_TICKS = 30;

void BuildCan() {
    SensorSpec can;
    can.id = "can_raw";
    can.type = SensorType::CANBUS_RAW;
    can.name = "Raw frames";
    can.description = "all traffic on CAN 0";
    can.bus_channel = 0;

    MetadataSpec metadata;
    metadata.device_id = 0; // not claimed on the CDMP network yet
    metadata.sensors_configuration_hash = 0x77C0FFEEU;

    Rig rig({ can }, 100, metadata, SESSION_ORIGIN_US + 3'600'000'000LL);
    rig.cans.push_back(CanFeed{ 0, CanFrames(0), 0 });

    rig.Start();
    for(int k = 1; k <= CAN_TICKS; k++)
        rig.Tick(TickTime(k));
    rig.Stop();

    const LoggedFile& file = rig.files.at(0);
    std::vector<OutFile> out;
    out.push_back(Emit(file, "can.mf4", file.closed, "closed",
        "One raw CAN frame sensor (bus 0, BusChannel 1): standard, extended, CAN FD, FD without BRS, transmitted frames, a 64 byte payload, a 10 byte FD payload (DLC 9), a shared timestamp, 30 ticks of 100 ms.", true));
    out.push_back(Emit(file, "can_unclosed.mf4", file.unclosed, "unclosed",
        "The same session as can.mf4 without StopLogging() (power loss): UnFinMF, flags 0x01 0x04 0x20, the VLSD group's byte count not patched.", false));
    out.back().derived_from = "can.mf4";

    // The last frame is 20 bytes: its payload record is 1 + 4 + 20 = 25 bytes and its frame record 24.
    const Written& last = file.written.back();
    zassert_equal(last.payload_length, 20U, "the last frame is 20 bytes");
    const uint64_t pair = last.end - last.begin;
    zassert_equal(pair, 49U, "payload record 25 + frame record 24");

    const auto cut = [&](const std::string& name, const std::string& what, uint64_t keep) {
        const uint64_t length = last.begin + keep;
        out.push_back(EmitCut(file, name, "can_unclosed.mf4", file.unclosed, length, "unclosed_cut", what,
            CutJson(length, what, 1, keep, pair)));
    };

    cut("can_unclosed_cut_boundary.mf4", "Cut at the boundary before the last frame's payload record: whole frames only.", 0);
    cut("can_unclosed_cut_vlsd_id.mf4", "The last frame's payload record has only its record ID.", 1);
    cut("can_unclosed_cut_vlsd_mid_length.mf4", "Cut inside the payload record's 4-byte length (ID and 2 length bytes).", 3);
    cut("can_unclosed_cut_vlsd_no_payload.mf4", "The payload record's length (20) is complete and no payload byte follows.", 5);
    cut("can_unclosed_cut_mid_payload.mf4", "Cut inside the payload (10 of its 20 bytes): mid-payload.", 15);
    cut("can_unclosed_cut_orphan_payload.mf4", "The payload record is complete and the frame record it belongs to is absent: an orphan payload, which readers ignore.", 25);
    cut("can_unclosed_cut_frame_id_only.mf4", "Orphan payload plus the frame record's record ID only.", 26);
    cut("can_unclosed_cut_mid_frame.mf4", "Orphan payload plus 15 bytes of the 24 byte frame record (inside its CAN_DataFrame).", 40);
    cut("can_unclosed_cut_frame_missing_last_byte.mf4", "The last frame's records are complete but for the last byte of the frame record (48 of 49 bytes).", 48);

    WriteManifest("can", "Fixture 2 (raw CAN frames) and its unclosed and cut copies (fixture 3, CAN).", rig, out);
}

// ------------------------------------------------------------------------------------------------
// Fixture 4: two files of one session made by rotation

// Sensors of the rotation session: the three of fixture 1 and a CAN sensor on bus 1.
std::vector<SensorSpec> RotationSensors() {
    auto specs = ThreeSensors();
    SensorSpec can;
    can.id = "can_raw";
    can.type = SensorType::CANBUS_RAW;
    can.name = "Raw frames";
    can.description = "all traffic on CAN 1";
    can.bus_channel = 1;
    specs.push_back(can);
    return specs;
}

std::unique_ptr<Rig> RunRotationSession(uint64_t max_bytes, int ticks) {
    MetadataSpec metadata;
    metadata.device_id = 9;
    metadata.sensors_configuration_hash = 0x1B2C3D4EU;

    auto rig = std::make_unique<Rig>(RotationSensors(), 100, metadata, SESSION_ORIGIN_US + 7'200'000'000LL);
    rig->max_bytes = max_bytes;
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
    first.name = name_of(first, 3071);
    second.name = name_of(second, 5128);

    std::vector<OutFile> out;
    out.push_back(Emit(first, first.name, first.closed, "closed",
        "First file of a rotated session: ticks 1 to 22 of 100 ms, closed by the rotation (StopLogging()) when it reached the size limit.", true));
    out.push_back(Emit(second, second.name, second.closed, "closed",
        "Second file of the session: opened at the time of tick 22 (the last tick of the first file), ticks 23 to 40, closed by the end of the session.", true));
    out.push_back(Emit(second, "rotation_second_unclosed.mf4", second.unclosed, "unclosed",
        "The second file as a power loss leaves it (the session's last file never closed).", false));
    out.back().derived_from = second.name;

    const std::string extra = "\"rotation\":{\"limit_bytes\":" + Num(limit) + ",\"due_after_tick\":"
        + Num(ROTATION_AFTER_TICK) + ",\"first_file\":" + Q(first.name) + ",\"second_file\":" + Q(second.name)
        + ",\"second_start_rel_us\":" + Num(second.start_rel_us) + ",\"first_end_rel_us\":" + Num(first.end_rel_us)
        + ",\"first_end_ms\":" + Num((rig.origin_us + first.end_rel_us) / 1000)
        + ",\"second_end_ms\":" + Num((rig.origin_us + second.end_rel_us) / 1000) + "}";

    WriteManifest("rotation",
        "Fixture 4: one session of four sensors (an analog input with a raw value, an indicator, a virtual sensor with Only new data and a raw CAN sensor on bus 1) in two files made by rotation.",
        rig, out, extra);
}

// ------------------------------------------------------------------------------------------------
// Extras: every conversion the logger writes, comments, an ID of two bytes, CAN beside values

void BuildConversions() {
    std::vector<SensorSpec> specs;
    const auto add = [&specs](std::string id, SensorType type, std::string name, std::string unit, std::string description, std::string expression, bool log_raw) {
        SensorSpec s;
        s.id = std::move(id);
        s.type = type;
        s.name = std::move(name);
        s.unit = std::move(unit);
        s.description = std::move(description);
        s.expression = std::move(expression);
        s.log_raw = log_raw;
        specs.push_back(s);
    };

    add("lin", SensorType::PHYSICAL_ANALOG, "Linear", "V", "x * 2 + 1", "x * 2 + 1", true);                   // 0
    add("pow2", SensorType::PHYSICAL_ANALOG, "Power", "V", "x ^ 2 + 3 becomes (X * X) + 3", "x ^ 2 + 3", true); // 1
    add("funcs", SensorType::PHYSICAL_ANALOG, "Functions", "", "abs log sin cos tan sqrt", "abs(x) - log(x) + sin(x) * cos(x) + tan(x) / 10 + sqrt(x)", true); // 2
    add("noconv", SensorType::PHYSICAL_ANALOG, "Not convertible", "V", "reads another sensor", "x + other_sensor", true); // 3
    add("ind_expr", SensorType::PHYSICAL_INDICATOR, "Indicator expression", "", "a comparison", "x > 0.5", true); // 4
    add("noraw", SensorType::PHYSICAL_ANALOG, "Raw not logged", "V", "has an expression, raw value off", "x * 3", false); // 5
    add("bare", SensorType::VIRTUAL_INDICATOR, "", "", "", "", false);                                          // 6
    add("utf8", SensorType::VIRTUAL_ANALOG, "Temp\xC3\xA9rature d'huile", "\xC2\xB0" "C", "R&D <test> \"quoted\" \xE2\x80\x94 caf\xC3\xA9", "", false); // 7
    add("off_sensor", SensorType::PHYSICAL_ANALOG, "Disabled sensor", "V", "is_enabled false: no channel", "x", true); // 8
    add("off_logging", SensorType::PHYSICAL_ANALOG, "Not logged", "V", "logging disabled: no channel", "x", true);     // 9
    specs[8].is_enabled = false;
    specs[9].log_enabled = false;

    MetadataSpec metadata;
    metadata.device_id = 3;
    metadata.sensors_configuration_hash = 0x00000000U; // 0: the unit ran a default configuration

    Rig rig(specs, 50, metadata, SESSION_ORIGIN_US + 10'800'000'000LL);

    for(size_t i = 0; i < specs.size(); i++) {
        std::vector<SampleSpec> samples;
        for(int j = 0; j < 12; j++) {
            const int64_t t = 8'000 + 50'000LL * j;
            // Volts in the domain of every function above (positive).
            const float raw = 0.5F + static_cast<float>(Tri(j + static_cast<int>(i), 16)) * 0.25F;
            float value = raw;
            switch(i) {
                case 0: value = static_cast<float>(static_cast<double>(raw) * 2.0 + 1.0); break;
                case 1: value = static_cast<float>(static_cast<double>(raw) * static_cast<double>(raw) + 3.0); break;
                case 2: {
                    const double x = raw;
                    value = static_cast<float>(std::fabs(x) - std::log(x) + std::sin(x) * std::cos(x) + std::tan(x) / 10.0 + std::sqrt(x));
                    break;
                }
                case 3: value = raw + 1.0F; break; // stands for x + other_sensor
                case 4: value = raw > 0.5F ? 1.0F : 0.0F; break;
                case 5: value = raw * 3.0F; break;
                case 6: value = (j % 3 == 0) ? 1.0F : 0.0F; break;
                case 7: value = 90.0F + static_cast<float>(j); break;
                default: value = raw; break;
            }
            const bool has_raw = specs[i].expression.empty() ? false : true;
            samples.push_back(Sample(t, value, has_raw ? std::optional<float>(raw) : std::nullopt));
        }
        rig.values.push_back(ValueFeed{ i, samples });
    }

    rig.Start();
    for(int k = 1; k <= 12; k++)
        rig.Tick(TickTime(k, 50'000));
    rig.Stop();

    std::vector<OutFile> out;
    out.push_back(Emit(rig.files.at(0), "conversions.mf4", rig.files.at(0).closed, "closed",
        "Ten sensors for the conversion and comment paths: linear, power (x ^ 2), functions, a not convertible expression (comment 'expression: ...' instead of a conversion), an indicator with a comparison, an expression with the raw value off, a sensor with no name, unit or description, a UTF-8 and XML-special name and description, a sensor disabled and one not logged (no channel group). 12 ticks of 50 ms.", true));

    WriteManifest("conversions", "Extra: conversions, comments and absent channels.", rig, out);
}

void BuildCanMixed() {
    std::vector<SensorSpec> specs = ThreeSensors();
    SensorSpec can_a;
    can_a.id = "can_a";
    can_a.type = SensorType::CANBUS_RAW;
    can_a.name = "CAN 0";
    can_a.bus_channel = 0;
    SensorSpec can_b;
    can_b.id = "can_b";
    can_b.type = SensorType::CANBUS_RAW;
    can_b.name = "CAN 1";
    can_b.description = "second bus";
    can_b.bus_channel = 1;
    SensorSpec can_none;
    can_none.id = "can_unknown";
    can_none.type = SensorType::CANBUS_RAW;
    can_none.name = "No bus";
    // no source at all: BusChannel 0 ("not specified")

    // Order: oil (1), can_a (VLSD 2, frames 3), ignition (4), can_b (5, 6), can_unknown (7, 8), power (9)
    specs = { specs[0], can_a, specs[1], can_b, can_none, specs[2] };

    MetadataSpec metadata;
    metadata.device_id = 11;
    metadata.sensors_configuration_hash = 0xA5A5A5A5U;

    Rig rig(specs, 100, metadata, SESSION_ORIGIN_US + 14'400'000'000LL);
    rig.values.push_back(ValueFeed{ 0, OilPressureSamples() });
    rig.cans.push_back(CanFeed{ 1, CanFrames(0), 0 });
    rig.values.push_back(ValueFeed{ 2, IgnitionSamples() });
    rig.cans.push_back(CanFeed{ 3, CanFrames(1), 0 });
    rig.cans.push_back(CanFeed{ 4, CanFrames(2), 0 });
    rig.values.push_back(ValueFeed{ 5, EnginePowerSamples(17'000) });

    rig.Start();
    for(int k = 1; k <= 30; k++)
        rig.Tick(TickTime(k));
    rig.Stop();

    std::vector<OutFile> out;
    out.push_back(Emit(rig.files.at(0), "can_mixed.mf4", rig.files.at(0).closed, "closed",
        "Values and three raw CAN sensors in one file (record IDs 1; 2,3; 4; 5,6; 7,8; 9): an analog input, a CAN sensor on bus 0, an indicator, one on bus 1 (BusChannel 2), one with no bus (BusChannel 0), a virtual sensor; 30 ticks of 100 ms. Records of all groups interleave in time order.", true));
    out.push_back(Emit(rig.files.at(0), "can_mixed_unclosed.mf4", rig.files.at(0).unclosed, "unclosed",
        "The same session without StopLogging().", false));
    out.back().derived_from = "can_mixed.mf4";

    WriteManifest("can_mixed", "Extra: values and raw CAN sensors in one file.", rig, out);
}

void BuildWideIds() {
    // 127 value sensors and a CAN sensor: 128 enabled sensors, so GetRecordIdSizeBytes() is 2.
    // Not reachable on a unit (SensorLimits::MAX_COUNT is 64), written by the same logger.
    std::vector<SensorSpec> specs;
    for(int i = 0; i < 127; i++) {
        SensorSpec s;
        char id[16];
        snprintf(id, sizeof(id), "ch%03d", i);
        s.id = id;
        s.type = SensorType::PHYSICAL_ANALOG;
        char name[32];
        snprintf(name, sizeof(name), "Channel %d", i);
        s.name = name;
        s.unit = "V";
        specs.push_back(s);
    }
    SensorSpec can;
    can.id = "can_raw";
    can.type = SensorType::CANBUS_RAW;
    can.name = "Raw frames";
    can.bus_channel = 0;
    specs.push_back(can);

    MetadataSpec metadata;
    metadata.device_id = 1;
    metadata.sensors_configuration_hash = 0x0BADF00DU;

    Rig rig(specs, 250, metadata, SESSION_ORIGIN_US + 18'000'000'000LL);
    for(size_t i = 0; i < 127; i++) {
        std::vector<SampleSpec> samples;
        for(int j = 0; j < 4; j++)
            samples.push_back(Sample(250'000LL * j + 1'000 + static_cast<int64_t>(i) * 100,
                0.5F + static_cast<float>(i) * 0.015625F + static_cast<float>(j) * 0.25F));
        rig.values.push_back(ValueFeed{ i, samples });
    }
    std::vector<FrameSpec> frames;
    frames.push_back(Frame(100'000, 0x123, false, false, false, false, 8, 1));
    frames.push_back(Frame(600'000, 0x18FEF100, true, false, true, true, 12, 2));
    frames.push_back(Frame(900'000, 0x7FF, false, true, false, false, 2, 3));
    rig.cans.push_back(CanFeed{ 127, frames, 0 });

    rig.Start();
    for(int k = 1; k <= 4; k++)
        rig.Tick(TickTime(k, 250'000));
    rig.Stop();

    std::vector<OutFile> out;
    out.push_back(Emit(rig.files.at(0), "wide_ids.mf4", rig.files.at(0).closed, "closed",
        "127 value sensors and a raw CAN sensor: 128 enabled sensors, so the data group has a record ID of 2 bytes (value groups 1..127, the VLSD group 128, the CAN group 129). Synthetic: a unit logs at most 64 sensors.", true));

    WriteManifest("wide_ids", "Extra: a record ID of two bytes.", rig, out);
}

void BuildEmpty() {
    MetadataSpec metadata;
    metadata.device_id = 2;
    metadata.sensors_configuration_hash = 0;

    Rig rig(ThreeSensors(), 100, metadata, SESSION_ORIGIN_US + 21'600'000'000LL);
    rig.Start();
    rig.Stop();

    std::vector<OutFile> out;
    out.push_back(Emit(rig.files.at(0), "empty.mf4", rig.files.at(0).closed, "closed",
        "A session started and stopped before its first tick: the three sensors of values.mf4, closed, no record, every count 0.", true));

    WriteManifest("empty", "Extra: a closed log with no record.", rig, out);
}

} // namespace

ZTEST(fixtures, test_MakeFixtures) {
    const char* dir = std::getenv("MDF_FIXTURE_OUT");
    if(dir != nullptr && *dir != '\0')
        g_out_dir = dir;

    BuildValues();
    BuildCan();
    BuildRotation();
    BuildConversions();
    BuildCanMixed();
    BuildWideIds();
    BuildEmpty();

    for(const auto& name : g_written_names)
        printk("FIXTURE %s\n", name.c_str());
}
