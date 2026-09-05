---
name: Issue with something this fork adds
about: The YpsoPump driver, the HovorkaMPC algorithm, the Compose UI and skins, or the loop build
title: ''
labels: ''
assignees: ''
---

<!-- Upstream AAPS bugs belong at nightscout/AndroidAPS. This tracker is for the fork's own code. -->

## Which part

<!-- YpsoPump driver / HovorkaMPC / UI + skins / build + packaging / docs -->

## What happened

<!-- The precise time it happened, and what led up to it. Times matter: the logs are timestamped and
     most of this fork's failures are only legible against the surrounding minutes. -->

## Are you looping with this build?

<!-- This fork delivers insulin. Whether you were in closed loop, open loop, or reading status only
     changes what a report means — please say which. -->

## Version

<!-- Settings > About. Note the version label is a hand-typed build label, not a commit hash, so add
     the commit you built from if you know it. -->

## Logs

<!-- /storage/emulated/0/Android/data/info.nightscout.androidaps/files/AndroidAPS.log
     (logcat holds ~30 minutes; that file goes back much further). Attach or paste the window
     around the event. Redact your Nightscout URL and API secret. -->

## For YpsoPump driver reports

- Pump firmware version (pump menu, or the Firmware row on the pump tab):
- Was the mylife or CamAPS FX app running or connected at the time?

<!-- Two controllers on one pump desynchronise its counters, and that looks like a driver fault when
     it isn't. Worth ruling out first. -->
