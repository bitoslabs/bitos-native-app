add_library(bitos_media_core STATIC
    src/bitos_media.cpp
    src/timeline.cpp
)

target_compile_features(bitos_media_core PUBLIC cxx_std_20)
target_include_directories(bitos_media_core PUBLIC include)
target_compile_options(bitos_media_core PRIVATE
    $<$<CXX_COMPILER_ID:AppleClang,Clang,GNU>:-Wall;-Wextra;-Wpedantic;-Werror>
)

if(BITOS_BUILD_TESTS)
    enable_testing()
    add_executable(bitos_media_core_tests tests/timeline_test.cpp)
    target_link_libraries(bitos_media_core_tests PRIVATE bitos_media_core)
    add_test(NAME bitos_media_core_tests COMMAND bitos_media_core_tests)
endif()
