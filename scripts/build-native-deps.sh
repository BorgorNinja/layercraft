#!/usr/bin/env bash
# Cross-compiles the babl/GEGL dependency chain for a single Android ABI:
#   glib (pulls libffi + pcre2 as meson wrap subprojects)
#     -> json-glib -> babl -> gegl
#
# Only runs meaningfully on a machine with:
#   - ANDROID_NDK_HOME set (GitHub Actions ubuntu runners ship this)
#   - meson, ninja, pkg-config, git on PATH
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

mkdir -p "$PREFIX"
CROSS_FILE="${WORKDIR}/android-${ABI}.ini"
"$(dirname "$0")/gen-cross-file.sh" "$ABI" "$API" "$CROSS_FILE"

# Cross pkg-config lookups must only see our own prefix, never the host's.
export PKG_CONFIG_PATH=""
export PKG_CONFIG_LIBDIR="${PREFIX}/lib/pkgconfig:${PREFIX}/share/pkgconfig"
export PKG_CONFIG_SYSROOT_DIR=""

clone_shallow () {
  local repo="$1" dest="$2" tag="${3:-}"
  if [[ -n "$tag" ]]; then
    git clone --depth 1 --branch "$tag" "https://github.com/GNOME/${repo}.git" "$dest"
  else
    git clone --depth 1 "https://github.com/GNOME/${repo}.git" "$dest"
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

echo "== [1/4] glib =="
clone_shallow glib "${WORKDIR}/glib"
build_meson_project "${WORKDIR}/glib" \
  -Dtests=false \
  -Dinstalled_tests=false \
  -Dlibmount=disabled \
  -Dselinux=disabled \
  -Dxattr=false \
  -Dnls=disabled

echo "== [2/4] json-glib =="
clone_shallow json-glib "${WORKDIR}/json-glib"
build_meson_project "${WORKDIR}/json-glib" \
  -Dtests=false \
  -Dintrospection=disabled \
  -Dgtk_doc=disabled

echo "== [3/4] babl =="
clone_shallow babl "${WORKDIR}/babl"
build_meson_project "${WORKDIR}/babl" \
  -Denable-gir=false \
  -Dwith-docs=false

echo "== [4/4] gegl =="
clone_shallow gegl "${WORKDIR}/gegl"
build_meson_project "${WORKDIR}/gegl" \
  -Dintrospection=false \
  -Ddocs=false \
  -Dworkshop=false \
  -Dcairo=disabled \
  -Dgtk-doc=false

echo "Done. Installed to: $PREFIX"
find "$PREFIX/lib" -maxdepth 1 -name "*.so*" 2>/dev/null || true
