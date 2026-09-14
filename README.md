<div align="center">

<img src="brand/icon-512.png" width="140" alt="ParsV2R">

# ParsV2R

**A clean Android proxy client for Xray.**
Bring your own config. Nothing else required.

English · فارسی

</div>

---

## What it is

ParsV2R is a client. You give it a server, it connects to it. That's the whole idea.

It does not come with servers, it does not fetch a list from anywhere, and it does not phone
home. If you have a config from somewhere, paste it in and press the button.

## What it takes

Paste anything into the one box and it works out what you gave it:

| | |
|---|---|
| **Share links** | `vless://` · `vmess://` · `trojan://` · `ss://` · `socks://` |
| **A pile of links** | paste them all at once, duplicates get dropped |
| **Subscriptions** | the base64 body a panel hands you |
| **Raw JSON** | a full Xray config, comments and all |

The JSON path is the one most clients skip. If you write your own configs, or a panel gave you
one, you can run it as it is. Three things get corrected on the way in, because otherwise they
fail quietly:

- the inbound is put back on the port the tunnel actually dials
- a DNS block is added if there isn't one, since a Go binary on Android has no `/etc/resolv.conf`
  and without it every hostname server just looks dead
- `allowInsecure` is stripped at any depth — current Xray removed it and refuses the whole file
  the moment it sees it, which older panels still emit everywhere

Everything else you wrote is left alone. Your routing, your balancer, your five outbounds.

## Both languages

English and Persian, switchable in Settings, and it follows the system by default. No restart, no
half-translated screens.

## How it's put together

```
  your app  ──▶  tun interface  ──▶  hev-socks5-tunnel  ──▶  Xray  ──▶  your server
                                     (shared library)        (own process)
```

Two native pieces, both built from pinned source in CI:

- **Xray** runs as a standalone executable, not a linked library. It gets its own process, so it
  can't collide with anything, and Android will only execute it from the native library directory
  anyway.
- **hev-socks5-tunnel** is loaded into the app and handed the tun file descriptor directly. No
  second process, no passing descriptors over a socket.

The SOCKS port between them never changes. That's deliberate: it means the server on the far side
can be swapped without touching the tun interface, so apps on the phone never see a drop.

The core is started and proved to be listening *before* the VPN interface goes up. The other way
round captures everyone's traffic and then discovers the server was dead, which looks like your
internet broke rather than a server failing.

## Building it

```bash
./scripts/fetch-native.sh     # needs go and the Android NDK
./gradlew :app:assembleRelease
```

`fetch-native.sh` clones Xray and hev at exact commits, builds all three ABIs, and checks the ELF
header of every binary it produced so one built for the wrong CPU can't slip through. Nothing is
downloaded prebuilt.

The `:core` module is plain Java with no Android in it at all, which is the point — the whole
config layer runs on a desktop JVM, so its behaviour is known rather than hoped for. 164 checks,
run by CI on every push:

```bash
./gradlew :core:test
```

## Privacy

Nothing leaves your device. No analytics, no crash reporting, no server list being fetched, no
account. The only thing the app talks to is the server you gave it.

## Licence

GPL-3.0. Bundled at build time: [Xray-core](https://github.com/XTLS/Xray-core) (MPL-2.0) and
[hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) (MIT). See `NOTICE.md`.

Telegram: [@parsv2r](https://t.me/parsv2r)
