#!/usr/bin/env bash
# Cross-compiles the babl/GEGL dependency chain for a single Android ABI:
#   glib (pulls libffi + pcre2 as meson wrap subprojects)
#     -> json-glib -> libjpeg-turbo -> zlib -> libpng -> babl -> gegl
#
# libjpeg-turbo, zlib, and libpng are hard (non-optional, no meson-wrap
# fallback) dependencies of gegl/meson.build -- confirmed by reading it
# directly, not guessed. zlib is needed because libpng's generated
# pkg-config file requires it, and the NDK sysroot ships libz.so/zlib.h
# but no zlib.pc for pkg-config to find. All three build via CMake using
# the NDK's own toolchain file; babl/gegl/glib/json-glib stay on meson.
#
# Only runs meaningfully on a machine with:
#   - ANDROID_NDK_HOME set (GitHub Actions ubuntu runners ship this)
#   - meson, ninja, cmake, pkg-config, git on PATH
#   - full internet access (GNOME GitHub mirrors + meson wrapdb)
#
# This sandbox has neither the NDK nor access to gitlab.gnome.org, so this
# script is exercised by .github/workflows/native-libs.yml, not locally.
# Flags below (esp. glib's disabled features) are a best-effort starting
# point for a bionic/Android target -- expect to iterate on these once CI
# actually runs them; see README.md "Known risk areas" for the list of
# things most likely to need adjustment on first real run.
set -euo pipefail

ABI="${1:?Usage: build-native-deps.sh <abi> <api-level> <output-prefix>}"
API="${2:?missing api level}"
PREFIX="${3:?missing output prefix}"
WORKDIR="${WORKDIR:-$(mktemp -d)}"
JOBS="${JOBS:-$(nproc)}"

: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must be set}"

NDK_CMAKE_TOOLCHAIN="${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake"
if [[ ! -f "$NDK_CMAKE_TOOLCHAIN" ]]; then
  echo "NDK CMake toolchain file not found: $NDK_CMAKE_TOOLCHAIN" >&2
  exit 1
fi

mkdir -p "$PREFIX"
CROSS_FILE="${WORKDIR}/android-${ABI}.ini"
"$(dirname "$0")/gen-cross-file.sh" "$ABI" "$API" "$CROSS_FILE"

# Cross pkg-config lookups must only see our own prefix, never the host's.
export PKG_CONFIG_PATH=""
export PKG_CONFIG_LIBDIR="${PREFIX}/lib/pkgconfig:${PREFIX}/share/pkgconfig"
export PKG_CONFIG_SYSROOT_DIR=""

clone_shallow () {
  local org="$1" repo="$2" dest="$3" tag="${4:-}"
  if [[ -n "$tag" ]]; then
    git clone --depth 1 --branch "$tag" "https://github.com/${org}/${repo}.git" "$dest"
  else
    git clone --depth 1 "https://github.com/${org}/${repo}.git" "$dest"
  fi
}

build_meson_project () {
  local src="$1"; shift
  local builddir="${src}/_build-${ABI}"
  meson setup "$builddir" "$src" \
    --cross-file "$CROSS_FILE" \
    --prefix "$PREFIX" \
    --libdir lib \
    --default-library shared \
    --buildtype release \
    --wrap-mode default \
    "$@"
  meson compile -C "$builddir" -j "$JOBS"
  meson install -C "$builddir"
}

build_cmake_project () {
  local src="$1"; shift
  local builddir="${src}/_build-${ABI}"
  cmake -S "$src" -B "$builddir" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK_CMAKE_TOOLCHAIN" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-${API}" \
    -DCMAKE_INSTALL_PREFIX="$PREFIX" \
    -DCMAKE_FIND_ROOT_PATH="$PREFIX" \
    -DCMAKE_PREFIX_PATH="$PREFIX" \
    -DBUILD_SHARED_LIBS=ON \
    -DCMAKE_BUILD_TYPE=Release \
    "$@"
  cmake --build "$builddir" --parallel "$JOBS"
  cmake --install "$builddir"
}

echo "== [1/7] glib =="
clone_shallow GNOME glib "${WORKDIR}/glib"
build_meson_project "${WORKDIR}/glib" \
  -Dtests=false \
  -Dinstalled_tests=false \
  -Dlibmount=disabled \
  -Dselinux=disabled \
  -Dxattr=false \
  -Dnls=disabled

echo "== [2/7] json-glib =="
clone_shallow GNOME json-glib "${WORKDIR}/json-glib"
build_meson_project "${WORKDIR}/json-glib" \
  -Dtests=false \
  -Dintrospection=disabled \
  -Dgtk_doc=disabled

echo "== [3/7] libjpeg-turbo (gegl hard dep, no meson-wrap fallback) =="
clone_shallow libjpeg-turbo libjpeg-turbo "${WORKDIR}/libjpeg-turbo"
build_cmake_project "${WORKDIR}/libjpeg-turbo" \
  -DENABLE_STATIC=OFF \
  -DENABLE_SHARED=ON \
  -DWITH_SIMD=OFF \
  -DWITH_TURBOJPEG=OFF

echo "== [4/7] zlib (libpng hard dep; NDK sysroot has libz.so but no .pc file) =="
clone_shallow madler zlib "${WORKDIR}/zlib"
build_cmake_project "${WORKDIR}/zlib" \
  -DZLIB_BUILD_EXAMPLES=OFF

echo "== [5/7] libpng (gegl hard dep, no meson-wrap fallback) =="
clone_shallow pnggroup libpng "${WORKDIR}/libpng"
build_cmake_project "${WORKDIR}/libpng" \
  -DPNG_SHARED=ON \
  -DPNG_STATIC=OFF \
  -DPNG_TESTS=OFF \
  -DPNG_TOOLS=OFF

echo "== [6/7] babl =="
clone_shallow GNOME babl "${WORKDIR}/babl"
build_meson_project "${WORKDIR}/babl" \
  -Denable-gir=false \
  -Dwith-docs=false

echo "== [7/7] gegl =="
clone_shallow GNOME gegl "${WORKDIR}/gegl"
build_meson_project "${WORKDIR}/gegl" \
  -Dintrospection=false \
  -Ddocs=false \
  -Dworkshop=false \
  -Dcairo=disabled \
  -Dgtk-doc=false

echo "Done. Installed to: $PREFIX"
find "$PREFIX/lib" -maxdepth 1 -name "*.so*" 2>/dev/null || true

# Stage a flat jniLibs/<abi>/ layout so app/build.gradle.kts can bundle
# every dependency .so into the APK, not just the ones CMake builds
# directly. Gradle's CMake integration only packages what its own build
# produces (liblayercraft_engine.so) -- it has no visibility into shared
# libraries an external prefix was linked against, so without this step
# the APK links fine but fails at runtime with UnsatisfiedLinkError on
# missing DT_NEEDED entries (confirmed by inspecting v0.1.0-alpha's APK:
# it contained only liblayercraft_engine.so + libc++_shared.so, none of
# glib/gegl/babl/etc).
#
# `cp -L` dereferences symlinks: meson/libtool-style installs produce a
# chain (libfoo.so -> libfoo.so.0 -> libfoo.so.0.0.0), and Android's
# bionic linker resolves DT_NEEDED entries by filename within the APK's
# lib/<abi>/ directory, not via the desktop-style symlink chain -- so
# every name in that chain needs to exist as a real file post-copy, not
# a symlink that would dangle once moved out of $PREFIX/lib.
echo "== Staging jniLibs/${ABI} =="
mkdir -p "${PREFIX}/jniLibs/${ABI}"
find "$PREFIX/lib" -maxdepth 1 -name "*.so*" -exec cp -L {} "${PREFIX}/jniLibs/${ABI}/" \;
echo "Staged $(ls "${PREFIX}/jniLibs/${ABI}" | wc -l) files:"
ls -la "${PREFIX}/jniLibs/${ABI}"

