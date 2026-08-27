#include "bitos_media/bitos_media.h"

#include "bitos_media/timeline.hpp"

bitos_media_version bitos_media_get_version(void) {
    return bitos_media_version{BITOS_MEDIA_ABI_VERSION, 0u, 1u, 0u};
}

bitos_status bitos_timeline_source_time(
    const int64_t timeline_time_us,
    const int64_t clip_start_us,
    const int64_t source_in_us,
    const double playback_rate,
    int64_t* const source_time_us
) {
    if (source_time_us == nullptr) {
        return BITOS_STATUS_INVALID_ARGUMENT;
    }

    const bitos::media::ClipTimeMapping mapping{
        clip_start_us,
        INT64_MAX,
        source_in_us,
        playback_rate,
    };
    const auto result = mapping.source_time(timeline_time_us);
    if (!result.has_value()) {
        return BITOS_STATUS_OUT_OF_RANGE;
    }
    *source_time_us = result.value();
    return BITOS_STATUS_OK;
}
