# ⚡ KPower — Karoo Power Extension

### Power *without* a power meter — and advanced pedalling dynamics *with* one.

KPower turns your **Hammerhead Karoo 2 / 3** into a power meter by **estimating your watts from physics**
(slope, rolling resistance, real-air-density aero and acceleration, minus drivetrain losses). And if you
own a real ANT+ meter, it unlocks the **advanced cycling dynamics the Karoo can't record on its own** —
written straight into your ride FIT.

🇪🇸 **Guía rápida en español:** [docs/guia-rapida.md](docs/guia-rapida.md) · Karoo OS ≥ 1.524.2003 (tested on Karoo 3).

## Two tools in one

**1 · Estimated power — no sensor needed**
- Pair **“KPW Estimated”** and the Karoo records power, NP, TSS and zones **natively**, just like a real meter.
- Physics model with **real temperature/pressure/wind** (Open-Meteo, or [Headwind](https://github.com/timklge/karoo-headwind) if installed), tyre/surface rolling resistance, height-based aero and an acceleration term.
- **Multiple bikes**, auto-selected per Karoo ride profile.
- On-screen fields: **Est. Power / 3s / NP**.

**2 · Advanced cycling dynamics — with a real ANT+ meter**
KPower reads your meter's raw ANT+ broadcast and records the dynamics the Karoo can't, into the ride FIT (view them in [intervals.icu](https://intervals.icu)):
- **Torque** (overall + per leg)
- **Power phase** & **peak power phase** (left/right)
- **Platform centre offset (PCO)** + rider position + **torque barycenter**
- **L/R balance**, **torque effectiveness**, **pedal smoothness**

> **Where to look:** intervals.icu shows every field automatically. Garmin Connect shows native TE/PS/
> balance, but keeps KPower's own fields (torque, power phase, PCO, barycenter) as separate
> developer-data charts, not inside its "Cycling Dynamics" section — no arc graphic there (SDK
> limitation, not a bug). Strava ignores developer fields. Details: **[docs/real-meter-and-fit.md](docs/real-meter-and-fit.md#where-to-see-it-after-the-ride)**.

Plus **auto-naming** (brand + model), **battery level & low-battery alert**, and optional **estimate-vs-real logging** to compare both in the FIT. Details: **[docs/real-meter-and-fit.md](docs/real-meter-and-fit.md)**.

> Power is an **estimation**, not a measurement — the parameters matter. Setup: **[docs/configuration.md](docs/configuration.md)**.

## Quick start — which setup is mine?

| Your case | Pair as Karoo power | KPower on-screen fields you'd add |
|---|---|---|
| **No power meter** | **KPW Estimated** | none needed (Karoo shows power/NP natively); optional Est. Power / 3s / NP |
| **Real meter, want the estimate too (compare)** | the real meter (native) | Est. Power / 3s / NP |
| **Two meters at once** | meter A (native) | Real Power / 3s / NP (shows the 2nd meter) |
| **Real meter, want the FIT extras** | the real meter (native) | none — the Karoo shows the dynamics; KPower just enriches the FIT |

Steps:
1. **No meter:** *Estimator* tab → **+** → fill the bike → on the Karoo pair **“KPW Estimated”** as power. Ride.
2. **Real meter:** *Real meter* tab → **Scan → Add → Enable** → pair the meter **natively** in your ride
   profile (the Karoo then shows power, cadence, balance, TE, PS, torque). KPower reads it in parallel to
   write the FIT extras. Turn on **“Log estimated power (FIT)”** if you want to compare estimate vs real.

> **The Karoo already shows balance / torque effectiveness / pedal smoothness / torque natively** for a
> natively-paired meter, so KPower no longer duplicates them as on-screen fields. KPower's unique
> contribution with a real meter is in the **FIT** (torque, power phase, PCO, barycenter) — see
> [docs/real-meter-and-fit.md](docs/real-meter-and-fit.md).

## Install (sideload)

**Karoo 3 (v ≥ 1.527):** open the [latest APK](https://github.com/lockevod/Karoo-KPower/releases/latest/download/kpower.apk)
link on your phone → share to the **Hammerhead Companion** app → install.

**Karoo 2:** enable sideloading ([DC Rainmaker guide](https://www.dcrainmaker.com/2021/02/how-to-sideload-android-apps-on-your-hammerhead-karoo-1-karoo-2.html)),
then `adb install app-release.apk`.

> Upgrading from **< 1.9.5**? Uninstall the old version first (incompatible data format).

## Settings

Two tabs: **Estimator** (your bikes — weight, tyres, aero, FTP, power offset; Simple or Advanced mode) and
**Real meter** (add/enable a real ANT+ meter, battery alert, “log estimate to FIT”, diagnostics).
Full reference: **[docs/configuration.md](docs/configuration.md)** ·
real meter & FIT: **[docs/real-meter-and-fit.md](docs/real-meter-and-fit.md)**.

## Known issues

- It's an estimate: the biggest unknown is **wind**. KPower pulls wind/temperature/pressure from
  Open-Meteo automatically (or from the [Headwind](https://github.com/timklge/karoo-headwind) extension
  if installed) — nothing to configure. Dial in Crr/CdA from references (Advanced mode).
- The Karoo can lag on slope/speed for a few seconds → brief inaccurate estimates; it self-corrects.
- Use the **Power 3s** field for a steadier reading.
- Tested on Karoo 3 / metric. After updating, you may need to re-pair the virtual sensor.

## Credits

- Built on the work of **timklge** ([repos](https://github.com/timklge?tab=repositories)) — parts of his
  code make this extension possible.
- Power model: <https://www.gribble.org/cycling/power_v_speed.html>
- KPower stores/shares nothing — it only uses the Karoo sensors and weather APIs to estimate power.

## Links

[karoo-ext](https://github.com/hammerheadnav/karoo-ext) ·
[Open-Meteo](https://open-meteo.com/) ·
[Headwind](https://github.com/timklge/karoo-headwind) ·
[bicyclerollingresistance.com](https://www.bicyclerollingresistance.com/)
