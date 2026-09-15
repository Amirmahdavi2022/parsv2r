<div align="center">

<img src="fastlane/metadata/android/en-US/images/icon.png" width="130" alt="ParsV2R">

# ParsV2R

**A V2Ray / Xray client for Android, in Persian and English.**

</div>

---

ParsV2R is a fork of [v2rayNG](https://github.com/2dust/v2rayNG). Everything v2rayNG does, this
does: subscriptions, per-app proxy, routing rules, asset files, TCPing and real-delay tests,
proxy chains, policy groups, QR import, backup and restore, and the full set of protocols.

On top of that it carries the extras from [PattNG](https://github.com/patterniha/PattNG):

- **Aether profiles.** A profile type that runs the [Aether](https://github.com/CluvexStudio/aether)
  core (WARP / MASQUE). You don't need a config for it, it scans for a working Cloudflare edge
  on its own.
- **cipherSuites and the unsafe fingerprint** in settings and in share links.
- **Patched Xray core** that can connect to unencrypted VLESS and Trojan configs on public addresses.

And the ParsV2R bits on the outside:

- **Gold-on-black.** The whole app carries the ParsV2R palette instead of the stock accent.
- **Persian, properly.** The translation ships and is selectable like any other language.
- **Its own updates.** Update checks point at this repository, not upstream's.

Nothing was removed. If you already know v2rayNG, you already know this.

## Download

Grab the APK from [Releases](https://github.com/Amirmahdavi2022/parsv2r/releases). The universal
build installs on any phone; the per-ABI builds are smaller if you know which one you need.

## Credit

The engineering here belongs to 2dust and the v2rayNG contributors, and the Aether integration
and the Xray patches come from [patterniha](https://github.com/patterniha/PattNG) and
[CluvexStudio](https://github.com/CluvexStudio/aether). The cores are
[Xray-core](https://github.com/XTLS/Xray-core),
[Aether](https://github.com/CluvexStudio/aether) and
[hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel). Licensed GPL-3.0, same as upstream.
If you like the Aether part, go give PattNG a star too.

Telegram: [@parsv2r](https://t.me/parsv2r)
