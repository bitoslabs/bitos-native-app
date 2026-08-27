cmake_minimum_required(VERSION 3.24)
project(bitos_native LANGUAGES C CXX)

option(BITOS_BUILD_TESTS "Build native media-core tests" ON)

add_subdirectory(native/media-core)
