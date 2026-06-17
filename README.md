# Karoo Power Extension

This extension for Karoo devices adds a virtual power meter. You only need to add this power meter (settings -> sensors) and you can use all power fields.

Power is an **estimation** from a physics model: gravity (slope) + rolling resistance (surface and tyres) + aerodynamic drag (now using real air temperature and pressure) + acceleration/inertia, with drivetrain losses. It is not a real power meter, but with the right parameters it gets close.

Compatible with Karoo 2 and Karoo 3 devices running Karoo OS version 1.524.2003 and later (only tested with Karoo 3 if you detect issues with Karoo 2, please open an issue)

## What's new

- **Simple / Advanced configuration.** New profiles start in a **Simple** mode: pick a bike **preset** (road hoods/drops, TT, gravel, MTB), your **height**, and your **tyre** (width, pressure, tread), and the app derives the aerodynamic and rolling values for you. Advanced mode still lets you fine-tune Crr, Cd, frontal area and power loss by hand.
- **Better air drag.** Air density is now computed from real **temperature and pressure** (from Open-Meteo/OpenWeather, with the Karoo barometer/sensor as fallback) instead of altitude only — colder/denser air now correctly needs more power.
- **Acceleration/inertia term.** Accelerations and surges are accounted for, not just steady-state riding.
- **FTP from your Karoo profile.** New profiles can take your FTP automatically from the Karoo user profile (the FTP field becomes read-only while this is on).
- **Headwind integration.** If the Headwind extension is installed, KPower reuses its temperature, pressure and wind instead of doing its own weather lookups (with automatic fallback, and a switch to opt out).
- **Literature-calibrated coefficients** (Bassett frontal-area model, Crr from tyre/surface, drivetrain losses).
- **Multiple bikes, linked to your Karoo profiles.** Create one estimator bike per real bike (each with its own weight, tyres, aero) and link it to a Karoo **ride profile**. When you switch profile on the Karoo, KPower automatically uses the matching bike — no manual changes. See *Multiple bikes & Karoo profiles* below.
- **Compare against a real power meter (optional, off by default).** A new *Comparison mode* shows the estimate as extra data fields (instant, 3 s, normalized, average) and writes them into the ride's FIT as developer fields. You can also pair **one extra real ANT+ power meter** and record it alongside (also as its own data fields), so you can overlay KPower's estimate against a real meter in the same ride. See *Comparison & real power meters* below.
- **Settings are now split in two tabs:** *Bikes* (your estimator bikes) and *Comparison / Real meters* (the comparison toggle + ANT+ meters).
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

### Simple mode (recommended for most users)

Pick the things you actually know and let the app derive the rest:

- **Bike preset / position**: Road (hoods), Road (drops), Time trial, Gravel or MTB. Sets a sensible aerodynamic drag and a default tyre/surface.
- **Rider height** (cm): used together with your weight (from the Karoo profile) to estimate your **frontal area** (Bassett et al. regression).
- **Tyre**: width (mm for road/gravel, or inches for MTB — e.g. `2.3`), pressure (bar), tread (road slick / gravel / MTB knobby) and whether it is **tubeless**. The app derives the **rolling resistance (Crr)** from these (tubeless saving based on bicyclerollingresistance.com data). If your front and rear tyres differ (width or pressure), enter the **rear** one — it carries most of the weight and dominates rolling resistance.
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

- **Use Headwind weather if installed** (on by default): if you also run the [Headwind](https://github.com/timklge/karoo-headwind) extension, KPower reads temperature, pressure and wind from Headwind's stream instead of polling its own weather API — no point in both extensions looking up the weather. If Headwind isn't installed (or it has no data yet), KPower falls back to its own lookup automatically. Turn this switch off to always use KPower's own weather even when Headwind is present.
  - Note on units: temperature and pressure come from Headwind exactly; for wind, KPower assumes Headwind's **default** wind unit (km/h in metric, mph in imperial) and converts it to m/s. If you changed Headwind's wind unit to m/s or knots, the wind will be wrong — either keep Headwind on its default unit or turn this switch off.
- **Wind API Key**: you can use OpenWeatherMap for wind/temperature/pressure (free, requires an account and an API key). It is usually more accurate (nearby stations) than Open-Meteo. Otherwise KPower uses Open-Meteo automatically. (Used when Headwind is not providing the data.)

Kpower  will get the wind speed from openweathermap (you need to select openweather option also) or openmeteo automatically. 

Kpower virtual sensor gives 0 W when you're not pedalling (cadence gate: off below 20 rpm, on above 25 rpm); you can force power even at low cadence in the config.

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

Settings now have two tabs at the top: **Bikes** and **Comparison / Real meters**. Everything about your bikes lives under **Bikes**.

1. In **Bikes**, create one entry per bike (the "+" button) and fill in its data (weight, tyres, aero, FTP…) as described above.
2. Open a bike and set **"Link to Karoo profile"** to the Karoo ride profile you use with that bike (e.g. *Road*, *Gravel*, *MTB*). The list shows the profiles KPower has seen — if a profile is missing, just scroll to it once on the Karoo launcher (or start a ride with it) and it will appear.
3. That's it. When you switch ride profile on the Karoo, KPower automatically uses the bike linked to it — different weight/tyres/aero, all handled for you.

Fallback when nothing is linked: if the active profile isn't linked to any bike, KPower uses the bike you marked as **active**, or the first one. So a single-bike setup keeps working exactly as before.

> Tip: link each bike to the profile you actually ride it with. If the active Karoo profile is linked to the wrong bike, the estimate (and which power meter is treated as your main one) will follow that wrong bike.

## Comparison & real power meters (optional)

This is for checking how close KPower's estimate is to a **real** power meter. The typical comparison setup is **three sources** on the same ride: KPower's **estimate**, your Karoo's **main power meter** (the normal `power` field), and **one extra ANT+ meter** recorded here. Everything here lives under the **Comparison / Real meters** tab and is **off by default** (it adds some battery/CPU work and extra columns to the FIT).

### The idea: one "main" source + extras

Your Karoo always records **one** power source into its normal `power` field (the one you pick in the Karoo profile's sensors). That's your **main** source — it can be a real meter, or KPower's estimate (if you select KPower's virtual meter as the profile's power sensor).

Everything else KPower records as **extra columns** ("developer fields") in the FIT, so nothing fights over the main field:
- the **estimate** (when it isn't your main source), and
- the **one extra real ANT+ power meter** you add here.

KPower never writes your main source twice — whatever is the main source is left to the Karoo's normal `power` field, and only the *others* become extra columns.

### Turn it on

Open **Comparison / Real meters** and switch on **Comparison mode**. While it's on:
- Four estimated-power **data fields** become available for your screens: **Est. Power** (instant), **Est. Power 3s**, **Est. NP** (normalized) and **Est. Avg Power** (they show `---` when comparison mode is off).
- The estimate is written to the FIT as `est_power` / `est_power_3s` (every second) and `est_np` / `est_avg` (ride summary) — *unless the estimate is your main source*, in which case it's already in the normal `power` field.

### Add the extra real ANT+ power meter

Open the *Comparison / Real meters* tab — it scans for ANT+ power meters automatically; **Add** the one you want and it appears under **Recorded meters**, where you can delete it anytime (even if the meter isn't switched on / broadcasting). The recorded meter is written as extra FIT columns `pm1_power`, `pm1_cad`, `pm1_balance`, `pm1_torque`, and is also exposed as **Real Power / Real Power 3s / Real NP / Real Avg Power** data fields (the same set as the estimate). ANT+ is broadcast, so KPower can listen to the meter at the same time as the Karoo — no need to unpair anything.

### How to compare afterwards

1. Comparison mode on; in the bike you're riding, set **Primary power source** (in the bike's settings) to whatever feeds the Karoo's normal `power` field — *Estimated*, a specific *real meter*, or *External* (a sensor KPower doesn't manage).
2. Ride. Optionally add the *Est.* / *Real* fields to a screen to watch live.
3. Open the FIT in a tool that shows developer fields — **[intervals.icu](https://intervals.icu)** is the easiest — and overlay `est_power` and `pm1_power`… against the normal power. (Strava ignores developer fields, so use intervals.icu.)

**Why it's off by default:** it keeps the estimator and the ANT+ meters running and adds extra columns to every FIT. Leave it off for normal rides; turn it on only when you want to compare.

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
- **Air density from real temperature and pressure** (ideal-gas law) using Open-Meteo / OpenWeather, with Karoo barometer/sensor and altitude as fallbacks.
- **Acceleration / inertia term** (mass × acceleration), smoothed and clamped, so surges and stops are modelled more realistically.
- **Simple / Advanced configuration** with bike presets, height-based frontal area (Bassett et al.), and tyre-based rolling resistance (width / pressure / tread).
- **FTP from the Karoo user profile** (optional, on by default for new profiles; field is read-only while enabled).
- **Headwind weather reuse**: consumes temperature/pressure/wind from the Headwind extension when installed, avoiding duplicate weather polling, with automatic fallback to the own API.
- **FTP-scaled power cap**: the maximum estimated power scales with your FTP instead of a fixed ceiling — e.g. an FTP-200 rider no longer sees implausible 700 W+ spikes on a wrong (MTB/dirt) profile. The cap is now a smooth curve (no hard cut) anchored to realistic peak-over-FTP margins: roughly 1.2×FTP + 130 W (FTP 100 → 250 W, FTP 250 → 430 W, absolute ceiling 600 W), with progressive compression near the top so brief surges register without noise spikes.
- **3-second power smoothing**: the displayed power is time-smoothed like real power meters' "3s power", and the slope input is filtered too — GPS/barometer noise no longer produces watt spikes. Power still drops to 0 instantly when you stop pedalling.
- **Cadence gate with hysteresis**: coasting detection switches off below 20 rpm and back on above 25 rpm, so power no longer flickers between 0 and full value when cadence hovers around the cutoff.
- **Tailwind fix**: a tailwind stronger than your speed now correctly *reduces* the aero term instead of adding drag.
- **Comparison mode** (optional, off by default): exposes the estimate as four extra data fields (Est. Power / Est. Power 3s / Est. NP / Est. Avg Power) and writes `est_power`, `est_power_3s` (per-second) plus `est_np`, `est_avg` (session summary) into the FIT, so you can compare the estimate side-by-side against a real power meter in intervals.icu.
- **Record one extra real ANT+ power meter** (optional): add/remove it under *Comparison / Real meters* and it's recorded as extra FIT columns (`pm1_power`/`pm1_cad`/`pm1_balance`/`pm1_torque`) plus its own **Real Power / Real Power 3s / Real NP / Real Avg Power** data fields, alongside the estimate — so you can compare the estimate, your Karoo's main meter and this extra meter on one ride.
- **Multiple bikes linked to Karoo ride profiles**: one estimator bike per real bike, auto-selected when you switch profile on the Karoo. Settings split into *Bikes* and *Comparison / Real meters* tabs.
- Literature-calibrated coefficients (frontal area, Crr, drivetrain losses).

Previous features:
- Updated power estimation formula.
- Added wind speed parameter with openmeteo (from Timklge repository headwind).
- Added FTP to smooth the power estimation.
- Added wind speed using openweathermap.
- Added cadence to discard some power estimations (cadence lower than 22 rpm ). Cadence is better estimator than speed, but we cannot use directly because we need to know torque (and don't have this value) but we can use cadence to discard some bad estimations (when you go down a hill, for example, and you don't pedal). There is an option (v1.9.1) to force power calculation in any situation (with low cadences)

## Known issues

- Power meter is not 100% accurate, it is only a estimation based in power formula. It is not possible to get the real power data from the Karoo without a power meter.
There is currently a big important parameter in the power estimation, the wind. The wind can change the power needed to maintain a speed. 
You can use openmeteo or openweathermap If you want to use openweathermap (better because they use near stations), you need to get an API key from openweathermap (free but you need to create an account) and introduce it in the configuration.

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
[openweathermap](https://openweathermap.org/)
[headwind](https://headwind.app/)
[rolling resistance](https://www.bicyclerollingresistance.com/)
https://sites.google.com/view/powerbikepro/configuration
```
