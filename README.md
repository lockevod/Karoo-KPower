# Karoo Power Extension

This extension for Karoo devices adds a virtual power meter. You only need to add this power meter (settings -> sensors) and you can use all power fields.

Power is an **estimation** from a physics model: gravity (slope) + rolling resistance (surface and tyres) + aerodynamic drag (now using real air temperature and pressure) + acceleration/inertia, with drivetrain losses. It is not a real power meter, but with the right parameters it gets close.

Compatible with Karoo 2 and Karoo 3 devices running Karoo OS version 1.524.2003 and later (only tested with Karoo 3 if you detect issues with Karoo 2, please open an issue)

## What's new

- **Simple / Advanced configuration.** New profiles start in a **Simple** mode: pick a bike **preset** (road hoods/drops, TT, gravel, MTB), your **height**, and your **tyre** (width, pressure, tread), and the app derives the aerodynamic and rolling values for you. Advanced mode still lets you fine-tune Crr, Cd, frontal area and power loss by hand.
- **Better air drag.** Air density is now computed from real **temperature and pressure** (from Open-Meteo/OpenWeather, with the Karoo barometer/sensor as fallback) instead of altitude only — colder/denser air now correctly needs more power.
- **Acceleration/inertia term.** Accelerations and surges are accounted for, not just steady-state riding.
- **FTP from your Karoo profile.** New profiles can take your FTP automatically from the Karoo user profile.
- **Literature-calibrated coefficients** (Bassett frontal-area model, Crr from tyre/surface, drivetrain losses).
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
Please read the Help tab in configuration, there are some useful information because it's very important to configure with correct parameters.
Power is an estimation and you need this parameters correct to get a good estimation.

### Simple mode (recommended for most users)

Pick the things you actually know and let the app derive the rest:

- **Bike preset / position**: Road (hoods), Road (drops), Time trial, Gravel or MTB. Sets a sensible aerodynamic drag and a default tyre/surface.
- **Rider height** (cm): used together with your weight (from the Karoo profile) to estimate your **frontal area** (Bassett et al. regression).
- **Tyre**: width (mm), pressure (bar) and tread (slick / semi-slick / knobby). The app derives the **rolling resistance (Crr)** from these.
- **Surface**: the terrain you ride (asphalt, mix, gravel, off-road/sand). It scales the rolling resistance. Pick the one that matches your usual route.
- **FTP**: taken from your Karoo profile automatically (you can turn this off and type it).
- **Weight of Bike** (kg): bike plus any extra gear.

### Advanced mode

Toggle Advanced to set the physics values directly. The preset/height/tyre still pre-fill them, but you can override:

- **Rolling Resistance Coefficient (Crr)**: depends on tyres and surface — see https://www.bicyclerollingresistance.com/
- **Aerodynamic Drag Coefficient (Cd)**: depends on your position on the bike.
- **Frontal Area** (m²): the area of your body exposed to the wind.
- **Power Losses** (%): drivetrain losses (chain, pulleys).
- **Use Karoo temperature sensor**: fallback temperature source for air density when no weather data is available (the Karoo internal sensor reads a few °C high, so a small offset is applied).

### Wind and weather

- **Wind API Key**: you can use OpenWeatherMap for wind/temperature/pressure (free, requires an account and an API key). It is usually more accurate (nearby stations) than Open-Meteo. Otherwise KPower uses Open-Meteo automatically.

Kpower  will get the wind speed from openweathermap (you need to select openweather option also) or openmeteo automatically. 

Kpower virtual sensor gives 0.0 power when your cadence is below 22 rpm, but you can force to ignore it (configuration option).

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


## Upgrading from a previous version

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
- **FTP from the Karoo user profile** (optional, on by default for new profiles).
- **FTP-scaled power cap**: the maximum estimated power now scales with your FTP instead of a fixed ceiling — e.g. an FTP-200 rider no longer sees implausible 700 W+ spikes on a wrong (MTB/dirt) profile.
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
- KPower doesn't save or share any information for it's use, but it use firebase crashlytics service only for crashes in app (and firebase use this crash information). I only use this information to prevent new crashes in the app. Please if you isn't agree with Firebase use (this conditions are in firebase web and can change, please read it), please you cannot use kpower app. If you use kpower you are agree with all conditions and copyrights.

## Links

[karoo-ext source](https://github.com/hammerheadnav/karoo-ext)
[openmeteo](https://open-meteo.com/)
[openweathermap](https://openweathermap.org/)
[headwind](https://headwind.app/)
[rolling resistance](https://www.bicyclerollingresistance.com/)
https://sites.google.com/view/powerbikepro/configuration
```
