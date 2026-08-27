#ifndef BITOS_MEDIA_TIMELINE_HPP
#define BITOS_MEDIA_TIMELINE_HPP

#include <cstdint>
#include <optional>

namespace bitos::media {

struct ClipTimeMapping final {
    std::int64_t timeline_start_us;
    std::int64_t timeline_duration_us;
    std::int64_t source_in_us;
    double playback_rate;

    [[nodiscard]] std::optional<std::int64_t> source_time(std::int64_t timeline_time_us) const;
};

}  // namespace bitos::media

#endif
