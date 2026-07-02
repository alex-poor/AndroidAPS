#!/usr/bin/env bash
# Live monitor for the HovorkaMPC APS plugin running inside AAPS.
# Surfaces each invoke(): the BG it saw, its estimated glucose, the basal rate it decided,
# plus what the Loop does with it (enact / suggestion). Watch for: sane rates vs oref,
# estimated-G tracking real BG, and any max-basal pinning or errors.
ADB=/home/alex/projects/camaps/tools/android-sdk/platform-tools/adb
echo "== waiting for device =="; $ADB wait-for-device
echo "== clearing log; monitoring HovorkaMPC + loop enact events (Ctrl-C to stop) =="
$ADB logcat -c
$ADB logcat -v time 2>/dev/null | grep --line-buffered -iE \
  'HovorkaMPC|est\.G=|EventAPSCalculation|APSResult|enactTempBasal|TempBasalAbsolute.*LOOP|SMB|superbolus|LOOP DISABLED|reason:' \
  | grep --line-buffered -ivE 'chatty|Choreographer'
