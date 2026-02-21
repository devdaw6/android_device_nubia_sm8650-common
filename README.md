# Device configuration for nubia SM8650 family 

```
#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#
```

## Recent power-focused defaults

- Default display refresh left at framework/vendor defaults (0 / peak 120).
- PowerHAL camera-related boosts and post-boot input/governor aggressiveness reduced.
- PowerCap service switched to smart 2-stage screen-off mode: soft cap first, deeper cap after 45s.
- Wi-Fi scan intervals are relaxed further (screen-on connected/disconnected schedules extended up to 900s).
- Moving PNO scan interval increased from 30s to 420s.
- Radio defaults tuned for battery: `persist.vendor.radio.enableadvancedscan=false`, higher signal/test timers, and default network set to LTE/WCDMA/GSM (`ro.telephony.default_network=9,9`) so 5G remains user-switchable.
- Opportunistic/data-switch carrier hysteresis timers increased to `21600000/21600000/21600000` and post-switch scan timer to `43200000` to reduce periodic secondary-SIM churn.
- Opportunistic ping-pong/backoff timers (including `opportunistic.5g_*`) were also raised to multi-hour values and data-switch ping test was disabled to suppress repeated modem churn.
- Bluetooth defaults trimmed for background efficiency: disabled `OPP` and `PAN (NAP/PANU)` profiles; kept call/audio/watch-critical profiles (`HFP/A2DP/AVRCP/GATT/PBAP/MAP`).
- Wakelock-focused tune-ups from logs: less Wi-Fi neighbor/multicast wake activity and less aggressive screen-off dormancy polling in DPM.
- LMKD tuned for earlier background cleanup (`ro.lmk.minfree` increased, pressure controls adjusted).
- Read-ahead and swappiness reduced after boot to lower background I/O activity.
- Swap creation disabled via `ro.vendor.qti.config.swap=false`; global swappiness forced to 25 post-boot.
- Kernel cmdline `ignore_loglevel` removed.
- Background memory cgroup `swappiness` lowered from `140` to `80`.
- Added performance caps UI (NubiaParts): default capped CPU/GPU; per-app whitelist can restore full clocks.
- UFS: clkscale stays disabled; hibern8 stays 5000; clkgate left enabled (no re-enable on boot_completed).
