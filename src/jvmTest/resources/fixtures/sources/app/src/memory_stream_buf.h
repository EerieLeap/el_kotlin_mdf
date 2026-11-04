#pragma once

#include <algorithm>
#include <cstring>
#include <ios>
#include <streambuf>
#include <string>
#include <utility>

namespace rt_core_test {

// A seekable in-memory stream for tests. std::stringbuf would do, but its seekoff() is not
// linkable on every supported platform (the aarch64 SDK's libstdc++ disagrees with picolibc on
// the width of std::streamoff), so the seeks are implemented here. Writes overwrite or extend at
// the shared position; reads start at the beginning until a seek moves them.
class MemoryStreamBuf : public std::streambuf {
private:
    std::string data_;
    size_t write_position_ = 0;
    size_t read_position_ = 0;

protected:
    std::streamsize xsputn(const char* s, std::streamsize n) override {
        if(n <= 0)
            return 0;

        const auto count = static_cast<size_t>(n);
        if(write_position_ + count > data_.size())
            data_.resize(write_position_ + count);

        std::memcpy(data_.data() + write_position_, s, count);
        write_position_ += count;

        return n;
    }

    int_type overflow(int_type c) override {
        if(traits_type::eq_int_type(c, traits_type::eof()))
            return traits_type::not_eof(c);

        const char ch = traits_type::to_char_type(c);
        return xsputn(&ch, 1) == 1 ? c : traits_type::eof();
    }

    std::streamsize xsgetn(char* s, std::streamsize n) override {
        if(n <= 0 || read_position_ >= data_.size())
            return 0;

        const auto count = std::min(static_cast<size_t>(n), data_.size() - read_position_);
        std::memcpy(s, data_.data() + read_position_, count);
        read_position_ += count;

        return static_cast<std::streamsize>(count);
    }

    int_type underflow() override {
        if(read_position_ >= data_.size())
            return traits_type::eof();

        return traits_type::to_int_type(data_[read_position_]);
    }

    int_type uflow() override {
        if(read_position_ >= data_.size())
            return traits_type::eof();

        return traits_type::to_int_type(data_[read_position_++]);
    }

    pos_type seekoff(off_type off, std::ios_base::seekdir way, std::ios_base::openmode which) override {
        const auto base = [&](size_t current) -> off_type {
            if(way == std::ios_base::beg)
                return 0;
            if(way == std::ios_base::end)
                return static_cast<off_type>(data_.size());

            return static_cast<off_type>(current);
        };

        const bool moves_write = (which & std::ios_base::out) != 0;
        const bool moves_read = (which & std::ios_base::in) != 0;
        if(!moves_write && !moves_read)
            return { static_cast<off_type>(-1) };

        const off_type target = base(moves_write ? write_position_ : read_position_) + off;
        if(target < 0 || std::cmp_greater(target, data_.size()))
            return { static_cast<off_type>(-1) };

        if(moves_write)
            write_position_ = static_cast<size_t>(target);
        if(moves_read)
            read_position_ = static_cast<size_t>(target);

        return { target };
    }

    pos_type seekpos(pos_type position, std::ios_base::openmode which) override {
        return seekoff(static_cast<off_type>(position), std::ios_base::beg, which);
    }

public:
    MemoryStreamBuf() = default;
    MemoryStreamBuf(const MemoryStreamBuf&) = delete;
    MemoryStreamBuf& operator=(const MemoryStreamBuf&) = delete;
    MemoryStreamBuf(MemoryStreamBuf&&) = default;
    MemoryStreamBuf& operator=(MemoryStreamBuf&&) = default;
    ~MemoryStreamBuf() override = default;

    /** Storage for @p size bytes up front, so writes up to that size do not allocate. */
    void Reserve(size_t size) {
        data_.reserve(size);
    }

    [[nodiscard]] size_t Capacity() const {
        return data_.capacity();
    }
    [[nodiscard]] std::string Str() const {
        return data_;
    }
};

} // namespace rt_core_test
