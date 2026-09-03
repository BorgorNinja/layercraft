cmake_minimum_required(VERSION 3.22.1)
project(layercraft_engine)

# STATUS: stub build. This currently produces a .so with placeholder JNI
# implementations and NO GEGL/babl linkage.
#
# Roadmap (see /README.md #1-2):
#   1. Cross-compile babl + GEGL for Android ABIs via meson cross-files
#      (babl has no deps; GEGL needs glib/gobject/json-glib at minimum).
#   2. Install the resulting .so/.a + headers under a prefix, then:
#        find_package / pkg-config or manual target_link_libraries here
#        against libgegl-0.4, libbabl-0.1, libglib-2.0, libgobject-2.0.
#   3. Replace the placeholder bodies in native-engine.cpp with real
#      gegl_node_new_child / gegl_node_process calls.

add_library(layercraft_engine SHARED
    native-engine.cpp
)

find_library(log-lib log)

target_link_libraries(layercraft_engine
    ${log-lib}
    # gegl-0.4 babl-0.1 glib-2.0 gobject-2.0   <- add once step 1-2 done
)

target_compile_options(layercraft_engine PRIVATE -Wall -Wextra)
