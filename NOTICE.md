# Attribution

ParsV2R is a fork of **v2rayNG** by 2dust.

- Upstream: https://github.com/2dust/v2rayNG
- Licence: GPL-3.0
- Forked at: v2rayNG 2.3.8

Effectively all of the code in this repository is upstream's work. This fork changes the
application id, the name, the icon, the colour palette and the update endpoints. The engineering
belongs to 2dust and the v2rayNG contributors.

It also carries the changes from **PattNG** by patterniha, applied on top of the same
upstream base (v2rayNG master at a9b1242): the Aether profile type and its scanner, delay
tester and key handling, the cipherSuites / unsafe fingerprint options, and the switch to
patterniha's AndroidLibXrayLite build.

- PattNG: https://github.com/patterniha/PattNG (GPL-3.0)

Bundled cores:

- [Xray-core](https://github.com/XTLS/Xray-core), via
  [patterniha/AndroidLibXrayLite](https://github.com/patterniha/AndroidLibXrayLite) — MPL-2.0
- [Aether](https://github.com/CluvexStudio/aether) by CluvexStudio, built from source at v2.0.0,
  shipped as a separate executable — AGPL-3.0
- [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) by hev — MIT

Source for everything here is this repository, and upstream's is at the URL above.
