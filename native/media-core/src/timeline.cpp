#include "bitos_media/timeline.hpp"

#include <cmath>
#include <limits>

namespace bitos::media {

std::optional<std::int64_t> ClipTimeMapping::source_time(
    const std::int64_t timeline_time_us
) const {
    if (timeline_duration_us <= 0 || playback_rate <= 0.0 || !std::isfinite(playback_rate)) {
        return std::nullopt;
    }
    const auto relative = static_cast<long double>(timeline_time_us) -
        static_cast<long double>(timeline_start_us);
    if (relative < 0.0L || relative >= static_cast<long double>(timeline_duration_us)) {
        return std::nullopt;
    }

    const auto mapped = static_cast<long double>(source_in_us) +
        relative * static_cast<long double>(playback_rate);
    if (mapped < static_cast<long double>(std::numeric_limits<std::int64_t>::min()) ||
        mapped > static_cast<long double>(std::numeric_limits<std::int64_t>::max())) {
        return std::nullopt;
    }
    return static_cast<std::int64_t>(std::llround(mapped));
}

}  // namespace bitos::media
