#!/usr/bin/env bash
#
# Builds the two native pieces the app needs and drops them into app/src/main/jniLibs.
#
#   libxray.so                  the Xray core, a standalone executable
#   libhev-socks5-tunnel.so     the tun <-> socks bridge, a shared library loaded by the app
#
# Both are pinned to an exact commit. Nothing here downloads a prebuilt binary, so what ships is
# what this script built from source.
#
# Needs: go, the Android NDK (ANDROID_NDK_HOME), and a working network.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNI_DIR="$ROOT/app/src/main/jniLibs"
WORK="${WORK_DIR:-$ROOT/.native-build}"

XRAY_REPO="https://github.com/XTLS/Xray-core"
XRAY_TAG="v26.3.27"
XRAY_COMMIT="d2758a023cd7f4174a5a5fa4ff66e487d4342ba0"

HEV_REPO="https://github.com/heiher/hev-socks5-tunnel"
HEV_COMMIT="941c758101385d145c66210ac88991daaf27d4b6"

ABIS=(arm64-v8a armeabi-v7a x86_64)
GOARCHS=(arm64 arm amd64)
# ELF e_machine values, checked after the build so a binary for the wrong CPU cannot ship.
ELF_MACHINES=(183 40 62)

say() { printf '\n== %s\n' "$*"; }

require() {
  command -v "$1" >/dev/null 2>&1 || { echo "missing required tool: $1" >&2; exit 1; }
}

elf_machine() {
  # Reads e_machine (2 bytes, little endian) at offset 18 of the ELF header.
  od -An -tu1 -j18 -N2 "$1" | awk '{print $1 + $2 * 256}'
}

# --------------------------------------------------------------------- xray

build_xray() {
  say "Xray-core $XRAY_TAG ($XRAY_COMMIT)"
  require go
  local src="$WORK/xray"
  if [ ! -d "$src/.git" ]; then
    rm -rf "$src"
    git clone --quiet "$XRAY_REPO" "$src"
  fi
  git -C "$src" fetch --quiet --tags origin
  git -C "$src" checkout --quiet "$XRAY_COMMIT"

  local actual
  actual="$(git -C "$src" rev-parse HEAD)"
  if [ "$actual" != "$XRAY_COMMIT" ]; then
    echo "expected commit $XRAY_COMMIT but got $actual" >&2
    exit 1
  fi

  for i in "${!ABIS[@]}"; do
    local abi="${ABIS[$i]}" goarch="${GOARCHS[$i]}" machine="${ELF_MACHINES[$i]}"
    say "  building $abi"
    mkdir -p "$JNI_DIR/$abi"
    local out="$JNI_DIR/$abi/libxray.so"
    # CGO off gives a fully static binary with no libc dependency, which runs on Android
    # unchanged. The consequence to remember: the pure Go resolver wants /etc/resolv.conf,
    # which Android does not have -- which is exactly why every config this app generates
    # carries its own dns block.
    local goarm=""
    if [ "$goarch" = "arm" ]; then goarm=7; fi
    (
      cd "$src"
      env CGO_ENABLED=0 GOOS=linux GOARCH="$goarch" GOARM="$goarm" \
        go build -o "$out" -trimpath -ldflags "-s -w -buildid=" ./main
    )
    [ -s "$out" ] || { echo "$abi produced nothing" >&2; exit 1; }
    local got
    got="$(elf_machine "$out")"
    if [ "$got" != "$machine" ]; then
      echo "$abi: ELF machine is $got, expected $machine" >&2
      exit 1
    fi
    echo "     $(du -h "$out" | cut -f1)  machine=$got"
  done
}

# ---------------------------------------------------------------------- hev

build_hev() {
  say "hev-socks5-tunnel ($HEV_COMMIT)"
  local ndk="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
  [ -n "$ndk" ] || { echo "ANDROID_NDK_HOME is not set" >&2; exit 1; }
  [ -x "$ndk/ndk-build" ] || { echo "no ndk-build in $ndk" >&2; exit 1; }

  local src="$WORK/hev"
  if [ ! -d "$src/.git" ]; then
    rm -rf "$src"
    git clone --quiet --recursive "$HEV_REPO" "$src"
  fi
  git -C "$src" fetch --quiet origin
  git -C "$src" checkout --quiet "$HEV_COMMIT"
  git -C "$src" submodule update --quiet --init --recursive

  # Its Android.mk builds both a shared library and a standalone binary. Only the shared library
  # is wanted: it is the one carrying the JNI entry points the app binds to.
  (
    cd "$src"
    "$ndk/ndk-build" \
      NDK_PROJECT_PATH=. \
      APP_BUILD_SCRIPT=./Android.mk \
      NDK_APPLICATION_MK=./Application.mk \
      APP_ABI="arm64-v8a armeabi-v7a x86_64" \
      -j"$(nproc)" >/dev/null
  )

  for i in "${!ABIS[@]}"; do
    local abi="${ABIS[$i]}" machine="${ELF_MACHINES[$i]}"
    local built="$src/libs/$abi/libhev-socks5-tunnel.so"
    [ -s "$built" ] || { echo "$abi: the bridge did not build" >&2; exit 1; }
    local got
    got="$(elf_machine "$built")"
    if [ "$got" != "$machine" ]; then
      echo "$abi: bridge ELF machine is $got, expected $machine" >&2
      exit 1
    fi
    mkdir -p "$JNI_DIR/$abi"
    cp "$built" "$JNI_DIR/$abi/libhev-socks5-tunnel.so"
    echo "     $abi  $(du -h "$built" | cut -f1)  machine=$got"
  done
}

# --------------------------------------------------------------------- main

mkdir -p "$WORK" "$JNI_DIR"
require git
require od
build_xray
build_hev

say "what landed in jniLibs"
find "$JNI_DIR" -type f | sort | while read -r f; do
  printf '  %-60s %s\n' "${f#$ROOT/}" "$(du -h "$f" | cut -f1)"
done
