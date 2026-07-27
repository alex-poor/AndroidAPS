> ⚠️ **Experimental fork** — adds a proof-of-concept **YpsoPump** pump driver (`pump/ypsopump/`, branch
> `ypsopump-integration`). See **[pump/ypsopump/README.md](pump/ypsopump/README.md)**. Alpha, dosing-capable,
> requires a rooted device + pump-key extraction. **Not affiliated with the AndroidAPS project or Ypsomed —
> use at your own risk.** Built on the RE work of [SandraK82](https://github.com/SandraK82/ypsopump-research)
> and [vicktor](https://github.com/vicktor/ypsomed-pump). Everything below is the upstream AndroidAPS README.

> ℹ️ **Fork change — NSClientv3 accepts `http://` Nightscout URLs.** Upstream AAPS is HTTPS-only for
> Nightscout sync, enforced at four independent layers: the URL input validator, the NSClientv3 plugin's
> scheme handling, the `nssdk` network builder, and the app's Android network-security config
> (`app/src/main/res/xml/network_security_config.xml`). This fork loosens all four so a plain-HTTP
> Nightscout can be used. **This is intended specifically for VPN / mesh setups (e.g. Tailscale,
> WireGuard, ZeroTier) where the transport is already end-to-end encrypted and TLS on Nightscout is
> redundant.** Cleartext is permitted only for the explicitly listed hosts in `network_security_config.xml`
> — everything else stays HTTPS-only. **Do not point NSClientv3 at a plain-HTTP endpoint over the public
> internet or an untrusted LAN**: your API token and health data would travel unencrypted. Add or remove
> allowed hosts by editing the `<domain>` entries in that file.

# AAPS
* Check the wiki: https://wiki.aaps.app
*  Everyone who’s been looping with AAPS needs to fill out the form after 3 days of looping  https://docs.google.com/forms/d/14KcMjlINPMJHVt28MDRupa4sz4DDIooI4SrW0P3HSN8/viewform?c=0&w=1

[![Support Server](https://img.shields.io/discord/629952586895851530.svg?label=Discord&logo=Discord&colorB=7289da&style=for-the-badge)](https://discord.gg/4fQUWHZ4Mw)

[![CircleCI](https://circleci.com/gh/nightscout/AndroidAPS/tree/master.svg?style=svg)](https://circleci.com/gh/nightscout/AndroidAPS/tree/master)
[![Crowdin](https://d322cqt584bo4o.cloudfront.net/androidaps/localized.svg)](https://translations.aaps.app/project/androidaps)
[![Documentation Status](https://readthedocs.org/projects/androidaps/badge/?version=latest)](https://wiki.aaps.app/en/latest/?badge=latest)
[![codecov](https://codecov.io/gh/nightscout/AndroidAPS/branch/master/graph/badge.svg?token=EmklfIV6bH)](https://codecov.io/gh/nightscout/AndroidAPS)

DEV: 
[![CircleCI](https://circleci.com/gh/nightscout/AndroidAPS/tree/dev.svg?style=svg)](https://circleci.com/gh/nightscout/AndroidAPS/tree/dev)
[![codecov](https://codecov.io/gh/nightscout/AndroidAPS/branch/dev/graph/badge.svg?token=EmklfIV6bH)](https://codecov.io/gh/nightscout/AndroidAPS/tree/dev)

<img src="https://cdn.iconscout.com/icon/free/png-256/bitcoin-384-920569.png" srcset="https://cdn.iconscout.com/icon/free/png-512/bitcoin-384-920569.png 2x" alt="Bitcoin Icon" width="100">

3KawK8aQe48478s6fxJ8Ms6VTWkwjgr9f2
