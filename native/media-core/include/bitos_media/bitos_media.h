#ifndef BITOS_MEDIA_BITOS_MEDIA_H
#define BITOS_MEDIA_BITOS_MEDIA_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define BITOS_MEDIA_ABI_VERSION 1u

typedef enum bitos_status {
    BITOS_STATUS_OK = 0,
    BITOS_STATUS_INVALID_ARGUMENT = 1,
    BITOS_STATUS_OUT_OF_RANGE = 2,
    BITOS_STATUS_UNSUPPORTED = 3,
    BITOS_STATUS_INTERNAL = 4
} bitos_status;

typedef struct bitos_media_version {
    uint32_t abi;
    uint32_t major;
    uint32_t minor;
    uint32_t patch;
} bitos_media_version;

bitos_media_version bitos_media_get_version(void);

bitos_status bitos_timeline_source_time(
    int64_t timeline_time_us,
    int64_t clip_start_us,
    int64_t source_in_us,
    double playback_rate,
    int64_t* source_time_us
);

#ifdef __cplusplus
}
#endif

#endif
