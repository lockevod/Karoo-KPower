# Karoo Power Extension

This extension for Karoo devices adds a virtual power meter. You only need to add this power meter (settings -> sensors) and you can use all power fields.

Power is an **estimation** from a physics model: gravity (slope) + rolling resistance (surface and tyres) + aerodynamic drag (now using real air temperature and pressure) + acceleration/inertia, with drivetrain losses. It is not a real power meter, but with the right parameters it gets close.

Compatible with Karoo 2 and Karoo 3 devices running Karoo OS version 1.524.2003 and later (only tested with Karoo 3 if you detect issues with Karoo 2, please open an issue)

## What's new

- **Simple / Advanced configuration.** New profiles start in a **Simple** mode: pick a bike **preset** (road hoods/drops, TT, gravel, MTB), your **height**, and your **tyre** (width, pressure, tread), and the app derives the aerodynamic and rolling values for you. Advanced mode still lets you fine-tune Crr, Cd, frontal area and power loss by hand.
- **Better air drag.** Air density is now computed from real **temperature and pressure** (from Open-Meteo — free, no API key — with the Karoo barometer/sensor as fallback) instead of altitude only — colder/denser air now correctly needs more power.
- **Acceleration/inertia term.** Accelerations and surges are accounted for, not just steady-state riding.
- **FTP from your Karoo profile.** New profiles can take your FTP automatically from the Karoo user profile (the FTP field becomes read-only while this is on).
- **Headwind integration.** If the Headwind extension is installed, KPower reuses its temperature, pressure and wind instead of doing its own weather lookups (with automatic fallback, and a switch to opt out).
- **Literature-calibrated coefficients** (Bassett frontal-area model, Crr from tyre/surface, drivetrain losses).
- **Multiple bikes, linked to your Karoo profiles.** Create one estimator bike per real bike (each with its own weight, tyres, aero) and link it to a Karoo **ride profile**. When you switch profile on the Karoo, KPower automatically uses the matching bike — no manual changes. See *Multiple bikes & Karoo profiles* below.
- **Estimated power as data fields, always available.** Four estimated fields — **Est. Power** (instant), **Est. Power 3s**, **Est. NP**, **Est. Avg Power** — can be placed on any page at any time (the estimator runs only while a field is shown, so there's no cost if you don't use them). The estimated power source is shown as **“KPW Estim.”** when pairing.
- **Record a real ANT+ power meter + its cycling dynamics (optional).** Save **one or more** real ANT+ power meters under the *Real meter* tab (only **one active** at a time). The active meter is recorded to the ride FIT and exposed both as live data fields and as a pairable **“KPW &lt;name&gt;”** power/cadence sensor. KPower **auto-names** the meter with its brand and model (e.g. *Garmin Rally 200*), shows its **battery level** (per meter, as a coloured icon), and can pop a **low/critical battery alert** during the ride (opt-in, at most twice per ride). It also reads and records **Cycling Dynamics** the Karoo can't record natively — L/R balance, torque effectiveness, pedal smoothness, power phase and peak power phase — by reading the meter's raw ANT+ broadcast (works with Garmin Rally/Vector and similar; needs pedalling under load). See *Real meter & cycling dynamics* below.
- **Auto-saving bike editor + per-bike colour.** Editing an estimator bike now **saves automatically** — there are no Save/Cancel buttons. The “+” creates a bike straight away and opens it; the back arrow returns; the red **Delete** button discards one. Each bike also gets a **colour dot** you can pick in its editor, to tell bikes apart at a glance in the list.
- **Export / import your bikes.** From the *Estimator* tab’s **⋮ menu** you can **export** all your estimator bikes to a file and **import** them back — handy for backups or moving your setup to another Karoo. See *Export / import* below.
- **Optional: log the estimate to the FIT for comparison** (off by default). A single toggle writes the estimate into the ride's FIT as developer fields so you can overlay it against a real meter in intervals.icu.
- **Settings are split in two tabs:** *Estimator* (your estimator bikes/profiles) and *Real meter* (the real ANT+ meter + the “log estimate to FIT” toggle + diagnostic logging).
- **Existing profiles keep working unchanged** — see *Upgrading* below.


## Installation

You can sideload the app using the following steps for Karoo 2

1. Download the APK from the releases .
2. Prepare your Karoo for sideloading by following the [step-by-step guide](https://www.dcrainmaker.com/2021/02/how-to-sideload-android-apps-on-your-hammerhead-karoo-1-karoo-2.html) by DC Rainmaker.
3. Install the app using the command `adb install app-release.apk`.


If you've Karoo 3 and v > 1.527 you can sideload the app using the following steps:

1. Link with apk (releases link) from your mobile (https://github.com/lockevod/Karoo-KPower/releases/latest/download/kpower.apk)
2. Share with Hammerhead companion app
3. Install the app using the Hammerhead companion app.


## Usage

1. After installing this app on your Karoo, you need to configure the power extension in the settings.
Power is an estimation and you need these parameters correct to get a good estimation, so it's very important to configure them well (this README is the reference; the in-app help tab has been removed).

### Examples — common setups

KPower has two independent things that both end up as “power”, and people mix them up. Quick map:

- **Estimator bike** = the *physics estimate* (no sensor). Shows up when pairing as **“KPW Estim.”** Lives under the **Estimator** tab.
- **Real meter** = a *real ANT+ power meter* KPower records directly. Shows up when pairing as **“KPW &lt;brand model&gt;”** (e.g. *KPW Garmin Rally 200*). Lives under the **Real meter** tab.

#### A) I just want estimated power (no power meter)

1. *Estimator* tab → **+** → fill in the bike (Simple mode: preset, height, tyre, bike weight). FTP comes from your Karoo profile.
2. On the Karoo: **ride profile → sensors → pair “KPW Estim.”** as the power source.
3. Ride. Power fields, NP, TSS, etc. all work as if it were a real meter. (You can also just drop the **Est. Power / 3s / NP / Avg** data fields on a page without pairing anything.)

#### B) I only want my real power meter (and its cycling dynamics)

You have a real ANT+ meter and don't care about the estimate. Use KPower (instead of pairing the meter natively) because it **records the cycling dynamics the Karoo can't** — L/R balance, torque effectiveness, pedal smoothness, power phase — plus auto-name and battery alert.

1. *Real meter* tab → **Scan** → **Add** your meter → toggle **Enable**. It auto-names (e.g. *Garmin Rally 200*) and shows battery.
2. On the Karoo: **ride profile → sensors → pair “KPW &lt;brand model&gt;”** (e.g. *KPW Garmin Rally 200*) as the power source. **Don't also pair the meter natively**, or power is recorded twice.
3. Ride. Power/cadence record as normal **and** the cycling dynamics go into the FIT automatically (open it in [intervals.icu](https://intervals.icu) to see them). You don't need any Estimator bike, the Est. fields, or the “Log estimated power” toggle for this.

> Don't enable an Estimator power source at the same time as the real one — pair only **one** “KPW …” sensor as the Karoo's power. (You can still drop Est. fields on a page to glance at the estimate without pairing it.)

#### C) I have a real meter AND I want the estimate too (e.g. to compare)

You can run **both at once** — one real, one estimated:

1. **Set up the estimator bike** as in (A).
2. **Add the real meter:** *Real meter* tab → **Scan** → **Add** your meter → toggle **Enable** (only one real meter active at a time). It auto-names itself (e.g. *Garmin Rally 200*) and shows battery.
3. **Pick which one the Karoo records as its main `power`** by choosing which sensor you pair in the **Karoo ride profile → sensors**:
   - Pair **“KPW &lt;brand model&gt;”** → the **real** meter is your main power (recommended when you have a meter).
   - Pair **“KPW Estim.”** → the **estimate** is your main power.
   - **Pair only ONE of them** as the power source — pairing both records power twice. (Don't pair the meter *natively* either; let KPower feed it as “KPW …”.)
4. **See both side by side (optional):** the **Real Power / Real 3s / Real NP / Real Avg** fields show the meter, and the **Est. Power / 3s / NP / Avg** fields show the estimate — put them on the same page to watch live. The real meter's **cycling dynamics** (L/R balance, TE, PS, power phase) are recorded to the FIT automatically too.
5. **Log both to the FIT for later:** turn on **“Log estimated power (FIT)”** (*Real meter* tab). The real meter is recorded as `pm1_power`; the estimate as `est_power`/`est_np`. Open the FIT in [intervals.icu](https://intervals.icu) to overlay them.

> In short: the *real meter* and the *estimate* always coexist as separate data fields; the only either/or choice is **which single “KPW …” sensor you pair** as the Karoo's recorded power source.

#### D) Use the real meter to calibrate the estimate

Do (C), ride with **varied speeds**, then open the bike in *Estimator* — KPower will offer measured **Crr/CdA** to apply. See *Field calibration* below.

### Simple mode (recommended for most users)

Pick the things you actually know and let the app derive the rest:

- **Bike preset / position**: Road (hoods), Road (drops), Time trial, Gravel or MTB. Sets a sensible aerodynamic drag and a default tyre/surface.
- **Rider height** (cm): used together with your weight (from the Karoo profile) to estimate your **frontal area** (Bassett et al. regression).
- **Tyre**: width (mm for road/gravel, or inches for MTB — e.g. `2.3`), pressure (bar), tread (road slick / gravel / MTB knobby) and whether it is **tubeless**. The app derives the **rolling resistance (Crr)** from these (tubeless saving based on bicyclerollingresistance.com data). The pressure penalty is relative to an **optimal pressure** that scales with your **load** (rider + bike weight) and falls with tyre width, following Frank Berto's 15%-drop tables — so wide tyres correctly want much lower pressure than narrow ones. If your front and rear tyres differ (width or pressure), enter the **rear** one — it carries most of the weight and dominates rolling resistance.
- **Surface**: the terrain you ride (asphalt, mix, gravel, off-road/sand). It scales the rolling resistance. Pick the one that matches your usual route.
- **FTP**: taken from your Karoo profile automatically. While "Use FTP from Karoo profile" is on, the FTP field is read-only and shows the value from your Karoo profile; turn the switch off to type a manual FTP.
- **Weight of Bike** (kg): bike plus any extra gear.

### Advanced mode

Toggle Advanced to set the physics values **directly by hand**. In Advanced the preset / height / tyre inputs are hidden, so nothing recalculates your values — what you type is what is used:

- **Rolling Resistance Coefficient (Crr)**: depends on tyres and surface — see https://www.bicyclerollingresistance.com/
- **Aerodynamic Drag Coefficient (Cd)**: depends on your position on the bike.
- **Frontal Area** (m²): the area of your body exposed to the wind.
- **Power Losses** (%): drivetrain losses (chain, pulleys).
- **Use Karoo temperature sensor**: fallback temperature source for air density when no weather data is available (the Karoo internal sensor reads a few °C high, so a small offset is applied).

> Simple mode derives these from the preset/height/tyre; Advanced mode is for typing them in manually. Switch to Advanced if you want full manual control with no recalculation.

### Wind and weather

- **Weather provider: Open-Meteo, automatic.** KPower gets temperature, pressure and wind from [Open-Meteo](https://open-meteo.com/) — **free, no account, no API key, nothing to configure**. (Earlier versions also supported OpenWeatherMap; that has been removed — Open-Meteo is now the only built-in provider.)
- **Use Headwind weather if installed** (on by default): if you also run the [Headwind](https://github.com/timklge/karoo-headwind) extension, KPower reads temperature, pressure and wind from Headwind's stream instead of polling Open-Meteo itself — no point in both extensions looking up the weather. If Headwind isn't installed (or it has no data yet), KPower falls back to Open-Meteo automatically. Turn this switch off to always use KPower's own Open-Meteo lookup even when Headwind is present.
  - **Headwind wind unit**: temperature and pressure come from Headwind exactly. For wind, KPower needs to know which unit Headwind is sending. The **Headwind wind unit** dropdown defaults to **Auto** (assume Headwind's default — km/h in metric, mph in imperial). If you changed Headwind's wind unit to **m/s** or **knots**, pick that here so the wind is converted correctly.

KPower's virtual sensor gives 0 W when you're not pedalling (cadence gate: off below 20 rpm, on above 25 rpm); you can force power even at low cadence in the config.

Here are some typical values for these parameters:

**Air Drag / Frontal Area**

0.25 / 0.30 AEROBARS COM BIKE

0.35 / 0.40 DROPS BIKE

0.45 / 0.55 HOODS BIKE

0.60 / 0.75 TOPS BIKE

0.80 / 0.90 MTB BIKE 

**Rolling Resistance**

0.0045 TOP RANGE ROAD TIRES

0.0065 MEDIUM RANGE ROAD TIRES 

0.0085 LOW RANGE ROAD TIRES 

0.0095 MTB TIRES 

Check https://www.bicyclerollingresistance.com/ for better values for your tires.

**Power Losses**

1.0% SRAM CERAMIC / FORCE

1.3% SHIMANO ULTEGRA - DURACE

2.0% SRAM EAGLE

2.2% SHIMANO XTR

3%-4% SHIMANO OTHER


FTP is necessary to smooth the power estimation. If you don't know your FTP, you can use the following formula to get an estimation:
FTP = 0.95 * 20 minutes power  or use a value between 150 and 200 watts and adjust it later.

Surfaces:

2- Kpower emulates a real power meter, then you need to add this power meter in the sensors configuration. 
Start scan and  you'll see a new category (looks like a puzzle piece), select the powermeter.

3- Kpower will show you the power estimation in the power fields. You can use the power fields in the data screens, in the workout builder, etc. It's like a real power meter.


## Multiple bikes & Karoo profiles

If you ride more than one bike, you can keep **one estimator bike per real bike** and have KPower switch between them automatically.

Settings have two tabs at the top: **Estimator** and **Real meter**. Everything about your estimator bikes/profiles lives under **Estimator**.

1. In **Estimator**, create one entry per bike (the "+" button) and fill in its data (weight, tyres, aero, FTP…) as described above.
2. Open a bike and set **"Link to Karoo profile"** to the Karoo ride profile you use with that bike (e.g. *Road*, *Gravel*, *MTB*). The list shows the profiles KPower has seen — if a profile is missing, just scroll to it once on the Karoo launcher (or start a ride with it) and it will appear.
3. That's it. When you switch ride profile on the Karoo, KPower automatically uses the bike linked to it — different weight/tyres/aero, all handled for you.

Fallback when nothing is linked: if the active profile isn't linked to any bike, KPower uses the bike you marked as **active**, or the first one. So a single-bike setup keeps working exactly as before.

> Tip: link each bike to the profile you actually ride it with. If the active Karoo profile is linked to the wrong bike, the estimate (and which power meter is treated as your main one) will follow that wrong bike.

> **Editing is auto-saved.** There are no Save/Cancel buttons: the **“+”** creates a bike immediately and opens it, every change is saved as you go, the **back arrow** returns, and the red **Delete** discards a bike (including one you just created by mistake). Each bike has a **colour dot** you can pick in its editor to tell them apart in the list.

### Export / import

From the **Estimator** tab, open the **⋮ menu** (top-right):

- **Export config** writes all your estimator bikes to `kpower_bikes.json` in the app's files folder (`Android/data/com.enderthor.kpower/files/`). A toast shows the exact path. Pull it off the Karoo with the Hammerhead **Companion app** or `adb` to back it up or copy it to another device.
- **Import config** reads that same `kpower_bikes.json` back and **replaces** your current bikes with it. Push the file into that folder first (Companion / `adb`), then tap Import. Malformed or missing files are ignored with a notice.

This is a simple file-based transfer (no document picker, which the Karoo may not have), matching how the diagnostic logs are pulled.

## Real meter & cycling dynamics (optional)

The **Real meter** tab lets you save **one or more** real ANT+ power meters (a small “garage”) so KPower can record the **active** one — and its **cycling dynamics** — alongside (or instead of) the estimate. Only **one meter is active at a time**. KPower reads the meter's **raw ANT+ broadcast** directly, so it sees data the Karoo's normal pairing doesn't expose (the cycling-dynamics pages), and it works with torque-based meters like the Garmin Rally/Vector (which report power as crank torque, not as a plain wattage page).

### Pair a meter

1. Open the **Real meter** tab and tap **Scan** (don't scan while recording a ride). Normally you add one meter at a time — **adding a meter stops the scan automatically** so its live data can come through; scan again to add another.
2. **Add** the meter you want. It appears under **Recorded meters** with:
   - an **Enable** switch (only **one** meter can be active at a time — enabling one disables the others);
   - a **battery icon** (green = OK, amber = low, red = critical, grey “?” until the meter reports it);
   - a **chevron** that expands a **detail** panel showing the **device** (brand + model, e.g. *Garmin Rally 200*), plus **rename** and **delete** actions (in-meter **calibration** will live here in a future release).
3. KPower **auto-names** each meter from its ANT+ manufacturer page (e.g. *Garmin Rally 200*) the first time it sees it — so once detected, it's remembered. You can still **rename** it by hand; a manual name is never overwritten.
4. In your Karoo **ride profile → sensors**, you can additionally pair **“KPW &lt;name&gt;”** (e.g. *KPW Garmin Rally 200*) as the power source to feed the meter into the Karoo's normal `power`/cadence (don't *also* pair the meter natively, or power is recorded twice).

> **Battery & brand take a moment.** ANT+ sends the battery and brand/model pages infrequently and only while the meter is **awake** (transmitting), so on the settings screen they can show “?” / “connecting…” for a few seconds after the channel opens — and won't appear at all while a scan is running (the radio can't do both). They fill in once the meter has been seen, and the detected name then sticks.

### Battery alert

Turn on **“Notify on low/critical battery”** (a toggle on the *Real meter* tab) to get a one-time in-ride popup when the active meter's battery goes **low**, and again if it turns **critical** — at most **two alerts per ride**, and only while recording. Off by default. (ANT+ reports a coarse level, not a percentage, so there's no battery %.)

### What gets recorded

While a meter is **enabled** and you are in an **active ride (recording or paused)**:
- **Power / cadence** stream as **Real Power / Real Power 3s / Real NP / Real Avg Power** data fields and to the FIT (`pm1_power`, `pm1_cad`).
- **Cycling dynamics** are written to the FIT automatically — **the Karoo can't record these natively** — and several are live data fields too: **L/R Balance**, **Torque Effectiveness**, **Pedal Smoothness**, **Power Phase L/R**, **Peak Power Phase L/R** (plus PCO and torque barycenter in the FIT). Dynamics only appear while pedalling under real load; a meter that doesn't send a given page just leaves that field empty.

> Live real-meter values appear once the ride is **recording or paused** (not on the pre-ride screen). This is deliberate — keeping the raw ANT channel open while idle would drain the radio and block the next scan. A field shows **“searching”** when a meter is configured but not yet streaming, and **“no device”** only when no meter is enabled.

### Calibration & crank length

KPower reads the power the meter has **already computed**, so calibration (zero-offset) and **crank length** live in the meter itself — set them in Garmin Connect / a head unit / the Karoo's native pairing once; they persist in the pedals. KPower doesn't need them to read power. (In-app calibration over ANT+ is a possible future addition.)

### Optionally log the estimate to the FIT (for comparison)

The four **Est. Power / 3s / NP / Avg** fields are **always** available on your screens (the estimator runs only while a field is placed). Writing the estimate **into the FIT** is a separate, **off-by-default** choice: switch on **“Log estimated power (FIT)”** at the bottom of the *Real meter* tab. It then writes `est_power` / `est_power_3s` (per second) and `est_np` / `est_avg` (ride summary). De-duplication is automatic: if KPower's estimate is the bound power source for the ride, it's already in the normal `power` field and isn't written twice (there is no “primary source” setting to configure anymore).

### How to compare afterwards

1. Turn on **“Log estimated power (FIT)”**, enable your real meter, and ride.
2. Optionally add the *Est.* and *Real* fields to a screen to watch live.
3. Open the FIT in a tool that shows developer fields — **[intervals.icu](https://intervals.icu)** is the easiest — and overlay `est_power`, `pm1_power`, the dynamics fields, etc. (Strava ignores developer fields, so use intervals.icu.)

**Why logging is off by default:** writing the estimate to the FIT adds extra columns to every ride. The estimate fields themselves cost nothing unless you place them; recording a real meter costs a little battery while you ride.

### Field calibration (let KPower measure your Crr & CdA)

The estimate is only as good as its inputs — and the two hardest to guess are your **rolling resistance (Crr)** and **aerodynamic drag (CdA = Cd × frontal area)**. If you have a real meter, KPower can **measure them from your own data** instead of you guessing:

1. **Enable a real meter** (*Real meter* tab) and turn on **“Log estimated power (FIT)”** (same tab). Both are needed — calibration runs while the comparison is active.
2. **Go for a ride with varied speeds** — include some slow stretches *and* some fast ones (and ride the surfaces you care about). Variety is what lets the maths separate Crr from CdA; a ride at one constant speed can't be calibrated. ~5+ minutes of moving data minimum, ~2+ minutes per surface.
3. **After the ride, open that bike in the *Estimator* tab.** If KPower gathered enough data, a **Field calibration** card appears showing, for this bike:
   - **CdA** (and the matching **Cd** for your frontal area), each with a **± uncertainty**;
   - **effective Crr per surface** (asphalt / standard / gravel / sand) you actually rode, each with its ± and sample count; surfaces you barely rode are shown as *not enough data*.
4. Tap **Apply to this bike** to write the measured values in (this switches the bike to Advanced so the measured Crr/Cd are used directly). **Apply is only enabled for results that are reliable** — i.e. well-identified by your ride. A “⚠ too uncertain” line means *ride more / more varied speeds* and try again; KPower never applies a guessed-looking number.

> The **±** is the honest part: a big ± means your ride didn't pin that number down (too constant a pace, too little data), so don't trust it. A small ± means the data really constrains it. This is why Apply is gated on reliability rather than just showing a single confident-looking value.

## Upgrading from a previous version

> Coming from a version **older than 1.9.5**? Uninstall the old version first and then install this one (older builds used an incompatible data format).

Your existing profiles keep working **without changes**:

- All your stored values (Crr, Cd, frontal area, power loss, FTP, surface) are preserved and used exactly as before.
- Upgraded profiles open in **Advanced** mode showing every field (nothing is hidden) and keep using the **FTP you configured** (not the Karoo profile one). The new Simple mode and "FTP from profile" apply only to **newly created** profiles.
- Surface factors are unchanged, and air density falls back to the previous behaviour when no temperature/pressure is available — so a steady ride estimates the same as before.

The new behaviour you will notice on existing profiles is only the genuine model improvements: the acceleration term during surges, real temperature/pressure when weather is available, and a power cap that now scales with your FTP (see below). If you want the new Simple mode / tyre-based Crr on an old profile, just create a new one.

> Note: if you manually edit the new tyre or height fields on a profile, the app will recompute Crr / frontal area from them and overwrite those values — that is intended.

## Features

New in this release:
- **Air density from real temperature and pressure** (ideal-gas law) using Open-Meteo (free, no API key), with Karoo barometer/sensor and altitude as fallbacks.
- **Acceleration / inertia term** (mass × acceleration), smoothed and clamped, so surges and stops are modelled more realistically.
- **Simple / Advanced configuration** with bike presets, height-based frontal area (Bassett et al.), and tyre-based rolling resistance (width / pressure / tread).
- **FTP from the Karoo user profile** (optional, on by default for new profiles; field is read-only while enabled).
- **Headwind weather reuse**: consumes temperature/pressure/wind from the Headwind extension when installed, avoiding duplicate weather polling, with automatic fallback to the own API.
- **FTP-scaled power cap**: the maximum estimated power scales with your FTP instead of a fixed ceiling — e.g. an FTP-200 rider no longer sees implausible 700 W+ spikes on a wrong (MTB/dirt) profile. The cap is now a smooth curve (no hard cut) anchored to realistic peak-over-FTP margins: roughly 1.2×FTP + 130 W (FTP 100 → 250 W, FTP 250 → 430 W, absolute ceiling 600 W), with progressive compression near the top so brief surges register without noise spikes.
- **3-second power smoothing**: the displayed power is time-smoothed like real power meters' "3s power", and the slope input is filtered too — GPS/barometer noise no longer produces watt spikes. Power still drops to 0 instantly when you stop pedalling.
- **Cadence gate with hysteresis**: coasting detection switches off below 20 rpm and back on above 25 rpm, so power no longer flickers between 0 and full value when cadence hovers around the cutoff.
- **Tailwind fix**: a tailwind stronger than your speed now correctly *reduces* the aero term instead of adding drag.
- **Estimated power data fields, always available**: Est. Power / Est. Power 3s / Est. NP / Est. Avg Power can be placed any time (estimator runs only while shown). Optionally **log the estimate to the FIT** (`est_power`, `est_power_3s` per second; `est_np`, `est_avg` summary) via an off-by-default toggle, with automatic de-duplication when the estimate is the bound power source.
- **Record real ANT+ power meters + cycling dynamics** (optional): save one or more under the *Real meter* tab, **one active** at a time (Enable switch, brand/model auto-name, battery icon). The active meter is recorded to the FIT as `pm1_power`/`pm1_cad` plus cycling-dynamics fields the Karoo can't record natively (L/R balance, torque effectiveness, pedal smoothness, power phase, peak power phase, PCO, torque barycenter), and exposed as live data fields and a pairable **“KPW &lt;brand model&gt;”** power/cadence sensor. Reads the raw ANT+ broadcast, so torque-based meters (Garmin Rally/Vector) work.
- **Battery status + low/critical alert**: each saved meter shows a coloured battery icon; an opt-in toggle pops a one-time in-ride alert when the active meter goes low, and again if critical (max two per ride).
- **Auto-naming with brand + model** from the meter's ANT+ manufacturer page (e.g. *Garmin Rally 200*); manual names are never overwritten.
- **Multiple bikes linked to Karoo ride profiles**: one estimator bike per real bike, auto-selected when you switch profile on the Karoo. Settings split into *Estimator* and *Real meter* tabs.
- **Auto-saving bike editor** (no Save/Cancel; create-on-“+”, delete-to-discard) with a **per-bike colour dot**.
- **Export / import** your estimator bikes to/from a JSON file (`kpower_bikes.json`) for backup or moving to another Karoo.
- Literature-calibrated coefficients (frontal area, Crr, drivetrain losses).

Previous features:
- Updated power estimation formula.
- Added wind speed parameter with openmeteo (from Timklge repository headwind).
- Added FTP to smooth the power estimation.
- Added wind speed using openweathermap (later removed — Open-Meteo is now the only provider).
- Added cadence to discard some power estimations (cadence lower than 22 rpm ). Cadence is better estimator than speed, but we cannot use directly because we need to know torque (and don't have this value) but we can use cadence to discard some bad estimations (when you go down a hill, for example, and you don't pedal). There is an option (v1.9.1) to force power calculation in any situation (with low cadences)

## Known issues

- Power meter is not 100% accurate, it is only an estimation based on the power formula. It is not possible to get real power data from the Karoo without a power meter.
The biggest unknown in the estimate is the **wind**, which strongly changes the power needed to hold a speed. KPower gets wind from Open-Meteo automatically (or from Headwind if installed) — there is nothing to configure. If you want the most accurate calibration of your bike's Crr/CdA, record a comparison ride with a real meter and let **field calibration** fit them from your data (see *Field calibration* below).

- Power meter use values from Karoo (real), sometimes Karoo has some "delays/lags" or Karoo expose bad information (for example, current slope grade) then Power Meter will estimate not accurate values. Most of times 5-10 seconds later all is fine ;)

- I recommend to use Power3s field.
  
- Tested only with Karoo 3 and Metric configuration, but can be used with Imperial configuration also (not tested)

- Sometimes it's necessary to rescan virtual power sensor when you update this extension. If you don't see the power meter active, remove current power meter and re-add.

- If your cadence sensor doesn't work fine or you want to have a power value always, check force power option.


## Credits

- Made possible by the generous usage terms of timklge. He has a great development and I use part of his code to create this extension.
  https://github.com/timklge?tab=repositories
- Power estimation https://www.gribble.org/cycling/power_v_speed.html
- SRAM and Hammerhead coypyright are describer in Karoo file.
- KPower doesn't save or share any information, only uses the Karoo sensors and weather APIs to get the information needed to estimate the power.

## Links

[karoo-ext source](https://github.com/hammerheadnav/karoo-ext)
[openmeteo](https://open-meteo.com/)
[headwind](https://headwind.app/)
[rolling resistance](https://www.bicyclerollingresistance.com/)
https://sites.google.com/view/powerbikepro/configuration
```
