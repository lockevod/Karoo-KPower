# KPower — Quick start (English)

KPower is a power meter for **Karoo 2 / 3** that works **without a power meter**: it *estimates* your
watts from a physics model (slope + rolling resistance + real-air-density aero + acceleration, minus
drivetrain losses). And if you **do** own a real meter, KPower can read it to enrich the recorded FIT —
with one important **pairing catch** (below).

> **The ANT+ rule:** a meter **can't** be read over ANT+ by the Karoo and KPower at the same time (they
> share the one ANT+ radio; whichever locks it first keeps it, the other goes dark). To have KPower read it
> **alongside** the Karoo, pair the meter to the Karoo over **BLE** (leaving ANT+ free for KPower). If your
> meter is **ANT+-only**, pick one: either the Karoo reads it, or KPower does (the **KPW** virtual sensor
> as the Karoo's power source).

> Power is an **estimation**, not a measurement. The parameters matter — see
> **[configuration.md](configuration.md)**.

## Which setup is mine?

| Your case | Pair as Karoo power | KPower fields you'd add |
|---|---|---|
| **No power meter** | **KPW Estimated** | none (Karoo shows power/NP); optional Est. Power / 3s / NP |
| **Real meter + want the estimate (compare)** | the real meter (native — BLE or ANT+) | Est. Power / 3s / NP |
| **Two meters at once** | meter A (native) | Real Power / 3s / NP / Cadence (show the 2nd meter) |
| **Real meter + FIT extras** (torque, power phase, PCO) | the meter to the Karoo over **BLE**, **and enabled in KPower** (ANT+) | none on-screen; KPower enriches the FIT. ANT+-only → use the offset row |
| **Offset applied to the recording** | **KPW &lt;brand model&gt;** (meter in KPower only) | none; the Karoo records the corrected power |

## Steps

**1) No meter (estimate only)**
1. *Estimator* tab → **+** → fill the bike (Simple mode: preset, height, tyre, weight). FTP comes from your Karoo profile.
2. On the Karoo: **ride profile → sensors → pair “KPW Estimated”** as the power source.
3. Ride. Power, NP, TSS and zones work natively.

**2) Real meter + estimate (compare)**
1. *Real meter* → **Scan → Add → Enable**.
2. Pair the meter **natively** in your ride profile (the Karoo shows power, cadence, balance, TE, PS, torque).
3. Add **Est. Power / 3s / NP** to see the estimate next to native power.
4. Turn on **“Log estimated power (FIT)”** to compare later in [intervals.icu](https://intervals.icu).
5. See a constant bias vs the real meter? Nudge it in **Power offset** (Factor % and/or Offset W):
   `Corrected = P × (1 + Factor/100) + Offset`. 0 = no change. In Simple and Advanced mode.

**3) Two meters at once (A vs B)**
The Karoo records **one** power source: pair **A natively** and **enable B in KPower**. The
**Real Power / 3s / NP / Cadence** fields show **B** → so you see A (native) and B (KPower) at once. Rare.
(They're **different** meters, so no ANT+ conflict.)

**4) Real meter + FIT extras (torque, power phase, PCO…)**
1. *Real meter* → **Scan → Add → Enable**.
2. Pair the meter to the Karoo **over BLE** (so KPower reads it over ANT+ **without conflict** — see the
   ANT+ rule above). **ANT+-only meter:** you can't have both; use the **KPW** virtual sensor as the Karoo's
   source (KPower reads the meter over ANT+, applies your offset, and records every dynamic as dev fields).
3. The Karoo shows the native dynamics live; KPower adds to the FIT what the Karoo can't record (torque,
   power phase, PCO, barycenter).

**5) Offset applied to the recorded power**
The **split** (setup 4) records the meter **raw** on the Karoo, so the offset **never** reaches the recording.
To fix that: pair the meter **in KPower only** and set **“KPW &lt;brand model&gt;”** as the Karoo's source.
KPower applies the offset and re-broadcasts → the Karoo records the **corrected** power (no native dynamics;
KPower records them all as dev fields).

## Important

**The Karoo already shows balance / torque effectiveness / pedal smoothness / torque natively** with a
natively-paired meter, so KPower doesn't duplicate those on-screen — **except a Balance L/R field** (instant
+ power-weighted average), for the **KPW-virtual (offset)** case where the Karoo shows no native dynamics.
With a real meter, KPower's value is in the **FIT**: torque, power phase, PCO, barycenter (the Karoo doesn't record those). Details:
**[real-meter-and-fit.md](real-meter-and-fit.md)**.

**Where to see it afterwards?** Best: **intervals.icu** — every field shows up automatically. **Garmin
Connect** shows native TE/PS/balance (if you paired the meter natively), but KPower's own fields (torque,
power phase, PCO, barycenter) come through as separate "developer" charts, not inside its "Cycling
Dynamics" section — no power-phase arc (SDK limitation, not a bug). **Strava** ignores developer fields.

## Install (sideload)

**Karoo 3 (v ≥ 1.527):** open the [latest APK](https://github.com/lockevod/Karoo-KPower/releases/latest/download/kpower.apk)
on your phone → share to the **Hammerhead Companion** app → install.

**Karoo 2:** enable sideloading, then `adb install app-release.apk`.

> Upgrading from **< 1.9.5**? Uninstall the old version first (incompatible data format).
