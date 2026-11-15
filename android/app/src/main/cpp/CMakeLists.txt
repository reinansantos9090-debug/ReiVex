cmake_minimum_required(VERSION 3.22.1)
project(reivex_fastio LANGUAGES CXX)

add_library(reivex_fastio SHARED fast_file_copy.cpp)
target_compile_features(reivex_fastio PRIVATE cxx_std_17)
target_compile_options(reivex_fastio PRIVATE -O3 -fvisibility=hidden)
# Android 15+ supports 16 KiB memory pages.
target_link_options(reivex_fastio PRIVATE "-Wl,-z,max-page-size=16384")
