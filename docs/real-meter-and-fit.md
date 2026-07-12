# KPower — Real meter & the FIT

KPower can read a real ANT+ power meter over its **raw ANT+ broadcast** (it works alongside the meter,
not instead of it) to **enrich the recorded FIT** with data the Karoo can't record itself. This is
optional — if you only want the estimate, ignore this page.

## Add a meter

*Real meter* tab → **Scan** (not while recording a ride) → **Add** → toggle **Enable**. Notes:
- Only **one meter is active at a time** (enabling one disables the others).
- It **auto-names** from the meter's ANT+ manufacturer page (e.g. *Garmin Rally 200*); you can rename it.
- A **battery icon** shows level (green/amber/red, “?” until reported). Optional **low/critical battery
  alert** during the ride (off by default, max two per ride).
- Battery and brand arrive on slow ANT+ pages and only while the meter is awake — they can show “?” for
  a few seconds, and not at all while a scan is running.

## How to pair the power source — and the ANT+ rule

The Karoo records **one** power source. But read this constraint first, because it decides which setups
are even possible:

> **A meter can't be read over ANT+ by BOTH the Karoo and KPower at the same time.** They share the one
> ANT+ radio; whichever channel locks the meter first holds it, and the other silently never connects —
> you get one or the other, not both. This is a hardware limit (verified on-device), not a bug. The way
> around it is to put the two on **different radios**: the meter on the Karoo over **BLE**, and KPower
> over **ANT+**.

Pick the setup that matches what you want:

### 1. Power + the dynamics the Karoo understands (balance/TE/PS) — simplest
Pair the meter directly in your **ride profile → sensors** (BLE **or** ANT+ — either is fine, since only
the Karoo reads it). Leave it **disabled** in KPower. The Karoo records power + native dynamics; the
estimator can still compare (`est_power` vs the native `power`). No KPower ANT+ channel → no conflict.

### 2. Also want KPower's extra dynamics (torque, power phase, PCO, barycenter)? — the "split"
Pair the meter to the Karoo **over BLE**, **and** enable it in KPower (KPower reads over ANT+). BLE for
the Karoo + ANT+ for KPower = **different radios**, so they coexist: the Karoo records power + native
dynamics, and KPower adds torque / power-phase / PCO / barycenter as FIT developer fields.
**Needs a dual-band (ANT+ & BLE) meter.**

### 3. Want your **offset** (%/W) applied to the recorded power?
Pair the meter in **KPower only** (ANT+) and pair **“KPW &lt;brand model&gt;”** as the Karoo's power
source — do **not** pair the meter to the Karoo. KPower applies the offset and re-broadcasts, so the
Karoo records the **corrected** power. A virtual sensor carries only **power + cadence**, so the Karoo
gets **no native dynamics** — but KPower's dev fields then record **all** of them (including the exotics).
This is the one thing the split (setup 2) can't give you: in the split the Karoo records the meter **raw**,
so the offset never reaches the recording.

### 4. Estimate only
Pair **KPW Estimated**. No real meter involved.

### ANT+-only meter (no BLE)? You must pick one
You can't have the Karoo **and** KPower on it (that's the conflict above). Choose:
- **Karoo reads it** (setup 1): native power + native dynamics; **no** KPower extras, **no** offset.
- **KPower reads it** (setup 3, KPW virtual as the Karoo's source): power **with offset** + KPower's
  **full** dynamics as dev fields; but the Karoo's power is the virtual and there are **no** native dynamics.

**Never** pair the meter natively **and** enable it in KPower over ANT+ at once — one channel will
silently win and the other goes dark.

## What the Karoo records natively (native pairing)

Verified from a Karoo-recorded FIT. With a natively-paired dual-sided meter the Karoo shows these
**live** and writes the **standard** FIT fields:

- power, cadence
- **left/right balance** (`left_right_balance`)
- **left/right torque effectiveness** (`left/right_torque_effectiveness`)
- **left/right pedal smoothness** (`left/right_pedal_smoothness`)

It shows **torque / avg torque / max torque** live too — but does **not** write torque to the FIT
(there's no standard FIT torque record field). It does **not** record power phase, PCO or torque
barycenter at all.

So with a natively-paired meter KPower needs **no on-screen field** for TE/PS/torque — the Karoo shows
them; its value is in the **FIT**. The **exception is a Balance L/R field** (instant + a power-weighted
session average): it's provided on-screen because with a **KPW-virtual (offset) source** the Karoo shows
**no** native dynamics, so KPower's field is the only live balance view. With a native meter it's
redundant (just don't add it). The instant field blanks (`--`) when you're not producing power.

## What KPower writes to the FIT

All as **developer fields** (so they never clash with the Karoo's native standard fields), except where
noted. Open the FIT in **[intervals.icu](https://intervals.icu)** to see them (Strava ignores developer
fields).

| Data | FIT field | Unique to KPower? |
|---|---|---|
| Power, cadence (the active meter) | `pm1_power`, `pm1_cad` (dev) | overlaps native power; useful to compare a 2nd meter |
| **Torque** (instant + per side) | `pm1_torque`, `dyn_torque_l/r` (dev) | **yes** — the Karoo can't put torque in the FIT |
| **Power phase** start/end + peak, L/R | `dyn_pp_*`, `dyn_peak_*` (dev) | **yes** |
| **PCO** left/right | `left/right_pco` (standard) | **yes** (Karoo doesn't record PCO) |
| Pedal centre offset rider position, **torque barycenter** | `dyn_rider_pos`, `dyn_baryc` (dev) | **yes** |
| Balance, TE, PS | `dyn_balance_l/r`, `dyn_te_l/r`, `dyn_ps_l/r` (dev) | redundant when native; the only copy when KPW-virtual |
| Estimate (when “Log estimated power” on) | `est_power`, `est_power_3s` (record); `est_np`, `est_avg` (session) | yes |

Why developer fields for balance/TE/PS: the Karoo writes the **standard** `left_right_balance` /
torque-effectiveness / pedal-smoothness for a natively-paired meter. If KPower also wrote those standard
fields they'd double-write/conflict (balance even uses a different bit7 convention). Developer fields sit
in their own columns, so they coexist — present for the KPW-virtual case, a harmless duplicate when
native.

## Where to see it after the ride

| Tool | What you get |
|---|---|
| **[intervals.icu](https://intervals.icu)** (recommended) | Every KPower developer field shows up automatically as a selectable chart/stream, with its name and units — torque, power phase, peak phase, PCO, barycenter, balance/TE/PS, all of it. No setup needed. |
| **Garmin Connect** | Torque effectiveness, pedal smoothness and balance appear **natively** when the meter was **natively paired** (the Karoo wrote those as standard FIT fields). KPower's own `dyn_*` developer fields (power phase, peak phase, torque, barycenter, rider position) also come through, but only as extra **developer-data charts at the bottom of the activity** — not inside Garmin's native "Cycling Dynamics" section, and the circular power-phase arc graphic will **not** render. |
| **Strava** | Ignores developer fields entirely — none of KPower's FIT extras show up. |

Why the arc never renders on Garmin Connect: that graphic needs the native FIT array fields
(`left/right_power_phase`, etc.), and the karoo-ext SDK can only write **scalar** developer fields — a
platform limitation, not a KPower bug.

Coverage matches what Garmin Connect's own Cycling Dynamics shows, **except** the session aggregates
Garmin computes on its side (time standing/seated, stand count, avg power seated vs standing) — KPower
writes per-record data, not those ride-level rollups.

## What ends up in the FIT — by setup

Two rules decide it: **dynamics** (`pm_*`, `dyn_*`, PCO) are written whenever a **meter is enabled and
recording** — independent of the estimate. The **estimate** dev fields (`est_*`) are written whenever
**“Log estimated power (FIT)” (comparison mode) is ON** — that is the only condition. It does NOT depend
on whether *KPW Estimated* is paired/connected: if the estimate also happens to be the bound power
source, `est_*` simply duplicate the standard `power` (a harmless extra column) — far better than the
old behaviour, which suppressed `est_*` whenever the virtual sensor was connected and could silently
drop the estimate when a real meter was the recorded power.

| Your setup | Standard `power` in the FIT | Estimate `est_*` dev fields | Dynamics (`dyn_*` / `pm_torque` / PCO) |
|---|---|---|---|
| **Estimator only** — pair *KPW Estimated* | the **estimate** (Karoo records it natively, with NP/avg/zones) | only if “Log estimate” **ON** — then duplicates the standard `power` (harmless) | no meter → none |
| **Real meter, dynamics only** — pair the meter **natively**, “Log estimate” **OFF**, no Est. fields | the **real meter** (native) | no (estimator doesn't even run) | **yes** — native balance/TE/PS + KPower's torque / power phase / PCO / barycenter |
| **Real + estimate (compare)** — meter native, “Log estimate” **ON** | the **real meter** (native) | **yes** (`est_power/3s/np/avg`) | yes |

Notes:
- “Estimator only” gives you the estimate **as the standard power field** — no developer fields needed,
  works in every analysis tool.
- “Dynamics only” writes **no estimator data at all**, and the estimation engine stays off (zero cost).
- `pm1_power` / `pm1_cad` are the **real meter's** data (developer duplicates of the native power/cadence,
  handy for overlays) — they are not estimator data.

## Compare estimate vs real

1. Enable your real meter, turn on **“Log estimated power (FIT)”** (*Real meter* tab), and ride.
2. Optionally add the **Est. Power / 3s / NP** fields to a page to watch live next to native power.
3. Open the FIT in intervals.icu and overlay `est_power` vs `pm1_power`.

`est_*` are written whenever “Log estimated power (FIT)” is ON, regardless of what the Karoo's bound
power source is. If the estimate happens to be that source, `est_power` will equal the standard `power`
(a harmless duplicate column); when you're comparing against a real meter — the normal case — `est_*`
and `pm1_power` are the two series you overlay.

## Calibration & crank length

KPower reads the power the meter has **already computed**, so zero-offset calibration and crank length
live **in the meter** — set them once (Garmin Connect / a head unit / native pairing); they persist in
the pedals. KPower doesn't need them to read power.

## Field calibration (diagnostic only)

KPower can fit your effective **Crr per surface** and **CdA** from a real-meter ride by least squares
(Martin power balance) as a tuning aid — **not** a user setting (no “apply” button). With a real meter
enabled, **“Log estimated power (FIT)”** on, and **diagnostic logging** on, the fitted values (each with
a ± std error) go to the diagnostic log (`CALIB …`) for offline analysis. A small ± means the ride
pinned the value down; a large ± means it didn't.

## FAQ

**I added a real meter but it gets no signal — does it write garbage to the FIT?**
No. Every per-record write is guarded: power/torque/balance are skipped when the value is `NaN`, and the
dynamics (`tePs`, force-angle…) are skipped when `null`. If nothing is valid, the record carries no
KPower columns at all. Stale values also expire to `NaN`/`null` after ~5 s with no event, so a frozen
last reading is never recorded. Enabling a meter writes nothing by itself — only data that actually
arrives is recorded.

**Is the estimator always running (even if I only want dynamics)?**
No. The estimation engine is **reference-counted** — it starts on the first consumer and **stops when
there are none**. It only runs while one of these is true: an **Est.** field is on a page, the **KPW
Estimated** sensor is paired, or **“Log estimated power (FIT)”** is on. So for a dynamics-only setup
(real meter native, no Est. fields, no KPW Estimated, logging off) the engine is **completely stopped**
— zero CPU/battery — and no estimator data is written.
