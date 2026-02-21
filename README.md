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
- Added generic MCC `250` anti-churn fallback: APN retry after disconnect raised to `3600000`, plus cross-SIM/opportunistic data-switch paths disabled at generic profile level.
- Bluetooth defaults trimmed for background efficiency: disabled `OPP` and `PAN (NAP/PANU)` profiles; kept call/audio/watch-critical profiles (`HFP/A2DP/AVRCP/GATT/PBAP/MAP`).
- Wakelock-focused tune-ups from logs: less Wi-Fi neighbor/multicast wake activity and less aggressive screen-off dormancy polling in DPM.
- Wi-Fi connected/disconnected screen-on scan schedules were relaxed further (removed 120s connected start, extended tails to 1200/2400s) and low-score scan period raised to 900s.
- LMKD tuned for earlier background cleanup (`ro.lmk.minfree` increased, pressure controls adjusted).
- Read-ahead and swappiness reduced after boot to lower background I/O activity.
- Swap creation disabled via `ro.vendor.qti.config.swap=false`; global swappiness forced to 25 post-boot.
- Kernel cmdline `ignore_loglevel` removed.
- Background memory cgroup `swappiness` lowered from `140` to `80`.
- Added performance caps UI (NubiaParts): default capped CPU/GPU; per-app whitelist can restore full clocks.
- UFS: clkscale stays disabled; hibern8 stays 5000; clkgate left enabled (no re-enable on boot_completed).
- Medium-risk kernel tuning: removed `cpufreq.default_governor=performance` and `rcupdate.rcu_expedited=1` from kernel cmdline; lowered `sched_pelt_multiplier` from `4` to `2`.
- Medium-risk post-boot tuning: reduced input/hispeed boosts, slowed CPU up-rate limits, lowered policy min frequencies, tightened `sched_fmax_cap`, and moved background cpuset to `0-3` (away from high-power cores `5-6`).
- Additional aggressive step (~30% vs previous profile): further lowered WALT caps/boosts (`sched_fmax_cap`, `sched_max_freq_partial_halt`, `input_boost`, `hispeed_freq`, `rtg_boost_freq`) and further slowed `up_rate_limit_us`; `sched_pelt_multiplier` reduced to `1`.
- Medium profile update: disabled early kernel `sched_boost` and removed cpuctl `sched_boost_no_override`/top-app colocate forcing to reduce unnecessary foreground over-boosting.
- Core-ctl and scheduler softened again for daily use: fewer guaranteed big/prime cores online, higher task thresholds, milder `sched_ed_boost`, higher low-latency threshold.
- Bus DCVS ceilings reduced (`UBWCP 640000`, `LLCC 710000`, `DDR 2400000`) for lower interconnect power under mixed load.
- Debug/tracing-heavy modules removed from autoload lists (`coresight`, `stm_*`, `*_debug`, perfmon debug paths) to reduce background overhead.
