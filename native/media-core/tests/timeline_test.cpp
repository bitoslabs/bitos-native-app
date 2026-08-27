#include "bitos_media/bitos_media.h"
#include "bitos_media/timeline.hpp"

#include <cassert>
#include <cstdint>

int main() {
    using bitos::media::ClipTimeMapping;

    const ClipTimeMapping normal{1'000'000, 5'000'000, 2'000'000, 1.0};
    assert(normal.source_time(1'000'000).value() == 2'000'000);
    assert(normal.source_time(2'500'000).value() == 3'500'000);
    assert(!normal.source_time(6'000'000).has_value());

    const ClipTimeMapping double_speed{0, 3'000'000, 500'000, 2.0};
    assert(double_speed.source_time(1'000'000).value() == 2'500'000);

    std::int64_t output = 0;
    assert(bitos_timeline_source_time(2'000'000, 1'000'000, 5'000'000, 0.5, &output) ==
           BITOS_STATUS_OK);
    assert(output == 5'500'000);
    assert(bitos_timeline_source_time(0, 0, 0, 1.0, nullptr) ==
           BITOS_STATUS_INVALID_ARGUMENT);

    return 0;
}
