# Third-party software

ParsV2R itself is licensed under GPL-3.0. It ships two pieces of software written by other
people, both built from source at a pinned commit by `scripts/fetch-native.sh`.

---

## Xray-core

- Project: https://github.com/XTLS/Xray-core
- Licence: Mozilla Public License 2.0
- Pinned: tag `v26.3.27`, commit `d2758a023cd7f4174a5a5fa4ff66e487d4342ba0`
- Shipped as: `libxray.so` (a standalone executable, run as a separate process)

Xray is not linked into the app. It is launched with `ProcessBuilder` and reached over a loopback
SOCKS port, so it stays a separate program that happens to live inside the same package.

## hev-socks5-tunnel

- Project: https://github.com/heiher/hev-socks5-tunnel
- Author: hev
- Licence: MIT
- Pinned: commit `941c758101385d145c66210ac88991daaf27d4b6`
- Shipped as: `libhev-socks5-tunnel.so` (a shared library)

The class `hev.htproxy.TProxyService` in this repository exists only because the library's
`JNI_OnLoad` looks that exact name up and registers its native methods against it. Its package
and class name are fixed by the library and must not be changed.

---

## Source offer

Source for everything bundled here is available at the URLs above, at the exact commits named.
The build script that produces the binaries is `scripts/fetch-native.sh` in this repository.
