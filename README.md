# ⚡ KPower — Karoo Power Extension

### Power *without* a power meter — and advanced pedalling dynamics *with* one.

KPower turns your **Hammerhead Karoo 2 / 3** into a power meter by **estimating your watts from physics**
(slope, rolling resistance, real-air-density aero and acceleration, minus drivetrain losses). And if you
own a real ANT+ meter, it unlocks the **advanced cycling dynamics the Karoo can't record on its own** —
written straight into your ride FIT.

🇪🇸 **Guía rápida en español:** [docs/guia-rapida.md](docs/guia-rapida.md) · Karoo OS ≥ 1.524.2003 (tested on Karoo 3).

## What's new in 3.0 — a big step up from 1.9.x

3.0 is not an incremental update. The estimator has been rebuilt, and real-meter support with
**advanced cycling dynamics** is completely new.

### 1 · A much better estimator — no sensor needed

Pair **“KPW Estimated”** and the Karoo records power, NP, TSS and zones **natively**, just like a real
meter. Compared with 1.9.x, the model behind it has been rebuilt:

| | 1.9.x | 3.0 |
|---|---|---|
| **Grade** | the Karoo's grade stream, which arrives several seconds late | derived from altitude with lag compensation, and taken **from the route profile** when a route is loaded — power follows the climb instead of trailing it |
| **Acceleration** | ignored | **inertia term**: speeding up costs watts, slowing down gives them back |
| **Air density** | estimated from altitude only | **real temperature and pressure** (Open-Meteo, or [Headwind](https://github.com/timklge/karoo-headwind) if installed — no API key) |
| **Rolling resistance** | a Crr typed by hand × a fixed surface you picked | derived from your **tyre** (width, pressure, tread, tubeless — Berto tables) and the **surface under you**, read live from your offline maps |
| **Frontal area / aero** | typed by hand | from your **height + weight** (Bassett) and riding position |
| **Setup** | raw physics numbers | **Simple mode** (preset, height, tyre, weight) — or Advanced for full control |
| **Bikes** | several, switched by hand | **auto-selected per Karoo ride profile**, with export/import |
| **Calibration** | — | **power offset** (factor % / W) to match a real meter you trust |

Plus on-screen fields **Est. Power / 3s / NP**, and an optional *estimate vs real* log in the FIT to
check it against a meter. It is still an **estimate**, not a measurement — wind is the biggest unknown.

### 2 · Advanced cycling dynamics — new, with a real ANT+ meter

KPower reads your meter's **raw ANT+ broadcast** and writes into the ride FIT the dynamics the
Karoo **can't record on its own**:

- **Torque** (overall + per leg) — the Karoo shows it live but never writes it to the FIT
- **Power phase** & **peak power phase** (left/right)
- **Platform centre offset (PCO)** + rider position + **torque barycenter**
- **L/R balance**, **torque effectiveness**, **pedal smoothness** (also when the Karoo can't, e.g. with an offset applied)

Also: **auto-naming** (brand + model), **battery level & low-battery alert**, **zero-offset
calibration** from the Karoo, and a **power offset** applied to the recorded power.

> **Where to look:** [intervals.icu](https://intervals.icu) shows every field automatically. Garmin
> Connect shows native TE/PS/balance, but keeps KPower's own fields (torque, power phase, PCO,
> barycenter) as separate developer-data charts — no arc graphic (SDK limitation, not a bug). Strava
> ignores developer fields. Details: **[docs/real-meter-and-fit.md](docs/real-meter-and-fit.md#where-to-see-it-after-the-ride)**.

> Dynamics support is new and meters differ a lot in what they send. If something looks wrong, please
> **[send me the log](#found-a-problem-send-the-log)** — that's how it gets fixed.

## Quick start

New here? Pick your setup in the quick guide:
**🇬🇧 [Quick start](docs/quick-start.md)** · **🇪🇸 [Guía rápida](docs/guia-rapida.md)**

> **Heads-up — the ANT+ rule:** a meter **can't** be read over ANT+ by the Karoo and KPower at the same
> time (they share the one ANT+ radio; whichever locks it first keeps it). To use both, pair the meter to
> the Karoo over **BLE** (ANT+ stays free for KPower), or pick one. All valid pairing combinations —
> including how to get your **offset** into the recording — are in the quick guide and
> **[docs/real-meter-and-fit.md](docs/real-meter-and-fit.md#how-to-pair-the-power-source--and-the-ant-rule)**.

## Install

### Karoo Extensions store *(recommended)*

KPower is distributed by Hammerhead in the Karoo's built-in **Extensions store** — no APK, no
sideloading, no ADB, and updates arrive on their own.

1. On the Karoo, open the **Extensions store** from the main menu.
2. Find **KPower** and tap **Install**.
3. Open KPower to set up your bike (or your meter), then pair the source you want — see
   [Quick start](docs/quick-start.md).

### Sideloading *(advanced / pre-release builds)*

**Karoo 3 (v ≥ 1.527):** open the [latest APK](https://github.com/lockevod/Karoo-KPower/releases/latest/download/kpower.apk)
link on your phone → share to the **Hammerhead Companion** app → install.

**Karoo 2:** enable sideloading ([DC Rainmaker guide](https://www.dcrainmaker.com/2021/02/how-to-sideload-android-apps-on-your-hammerhead-karoo-1-karoo-2.html)),
then `adb install app-release.apk`.

### Upgrading from 1.9.x

- **From 1.9.5 / 1.9.6:** just update — from the store or by sideloading, no need to uninstall. Your
  bikes are kept and open in **Advanced** mode with exactly the values you had (weight, Crr, Cd, area,
  power loss, FTP, surface). The OpenWeather key is no longer used — weather now comes from Open-Meteo
  or Headwind automatically. Try **Simple** mode afterwards if you'd rather set the bike from tyre,
  height and position.
- **From < 1.9.5:** uninstall the old version first (incompatible data format).
- After updating, re-pair the **KPW** sensor in your ride profile if the Karoo doesn't pick it up.

## Settings

Two tabs: **Estimator** (your bikes — weight, tyres, aero, FTP, power offset; Simple or Advanced mode) and
**Real meter** (add/enable a real ANT+ meter, battery alert, “log estimate to FIT”, diagnostics).
Full reference: **[docs/configuration.md](docs/configuration.md)** ·
real meter & FIT: **[docs/real-meter-and-fit.md](docs/real-meter-and-fit.md)**.

## Known issues

- It's an estimate: the biggest unknown is **wind**. KPower pulls wind/temperature/pressure from
  Open-Meteo automatically (or from the [Headwind](https://github.com/timklge/karoo-headwind) extension
  if installed) — nothing to configure. Dial in Crr/CdA from references (Advanced mode).
- Grade lag is much smaller than in 1.9.x: on a loaded route KPower reads the grade from the route
  profile, otherwise from the altitude stream. A few seconds can still slip through on sharp
  transitions; it self-corrects.
- Use the **Power 3s** field for a steadier reading.
- Tested on Karoo 3 / metric. After updating, you may need to re-pair the virtual sensor.

## Found a problem? Send the log

1. *Real meter* tab → turn on **Diagnostic logging** (at the bottom). It's off by default.
2. Ride (or pair / calibrate) as usual and reproduce the problem.
3. If your Karoo was connected to the Companion app during the ride, the log is already on its way —
   it's sent automatically during and after the ride.
4. Otherwise, back home: *Real meter* tab → **Send last ride log**. Wait until it says **Log sent ✓**.
5. Open a [GitHub issue](https://github.com/lockevod/Karoo-KPower/issues) describing what you saw, the
   date/time of the ride and your meter model. Attaching the FIT helps a lot.

**Privacy:** the copy that is sent has GPS coordinates, sensor names, serial numbers / device IDs and
file paths removed. It goes only to the developer, over HTTPS through a write-only relay (a Cloudflare
Worker) that forwards it to a private Telegram chat; the bot token lives only on that relay, never in
the APK, so nobody can extract it to read the chat or other riders' logs. Turn diagnostic logging off again when you're done —
it uses some extra battery and storage.

## Credits

- Built on the work of **timklge** ([repos](https://github.com/timklge?tab=repositories)) — parts of his
  code make this extension possible.
- Power model: <https://www.gribble.org/cycling/power_v_speed.html>
- KPower has no account or backend. Weather lookup sends the current position to Open-Meteo; when the
  off-by-default diagnostic log is enabled, anonymized GPS-stripped logs are sent to the developer
  through a write-only Cloudflare relay to a private Telegram chat (see [Found a problem?](#found-a-problem-send-the-log)).

## Links

[karoo-ext](https://github.com/hammerheadnav/karoo-ext) ·
[Open-Meteo](https://open-meteo.com/) ·
[Headwind](https://github.com/timklge/karoo-headwind) ·
[bicyclerollingresistance.com](https://www.bicyclerollingresistance.com/)
