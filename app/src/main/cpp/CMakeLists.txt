cmake_minimum_required(VERSION 3.22.1)
project(layercraft_engine)

# Links against a prefix produced by scripts/build-native-deps.sh (glib,
# json-glib, babl, gegl cross-compiled for this ABI). That prefix is built
# in CI (.github/workflows/native-libs.yml) -- it does not exist in a
# fresh checkout. Pass its location via -DNATIVE_DEPS_PREFIX=<path>.
#
# If NATIVE_DEPS_PREFIX is unset, this falls back to building the stub
# (no GEGL calls, JNI functions return placeholders) so the app module
# still builds standalone for UI-only development.

set(NATIVE_DEPS_PREFIX "" CACHE PATH "Prefix containing cross-compiled glib/babl/gegl for this ABI")

add_library(layercraft_engine SHARED
    native-engine.cpp
)

find_library(log-lib log)
target_link_libraries(layercraft_engine ${log-lib})

if(NATIVE_DEPS_PREFIX)
    message(STATUS "layercraft_engine: linking GEGL/babl from ${NATIVE_DEPS_PREFIX}")

    target_compile_definitions(layercraft_engine PRIVATE LAYERCRAFT_HAVE_GEGL=1)

    target_include_directories(layercraft_engine PRIVATE
        ${NATIVE_DEPS_PREFIX}/include/gegl-0.4
        ${NATIVE_DEPS_PREFIX}/include/babl-0.1
        ${NATIVE_DEPS_PREFIX}/include/glib-2.0
        ${NATIVE_DEPS_PREFIX}/lib/glib-2.0/include
        ${NATIVE_DEPS_PREFIX}/include/json-glib-1.0
    )

    target_link_directories(layercraft_engine PRIVATE ${NATIVE_DEPS_PREFIX}/lib)

    target_link_libraries(layercraft_engine
        gegl-0.4
        babl-0.1
        gobject-2.0
        glib-2.0
        gio-2.0
        gmodule-2.0
        json-glib-1.0
    )
else()
    message(WARNING "layercraft_engine: NATIVE_DEPS_PREFIX not set -- building stub with no GEGL linkage")
endif()

target_compile_options(layercraft_engine PRIVATE -Wall -Wextra)
