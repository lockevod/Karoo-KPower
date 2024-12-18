# Karoo Power Extension

> [!WARNING]  
> This app is currently in beta stage and its main features might not work fine.

This extension for Karoo devices adds a device simulates a virtual power meter. You only need to add this power meter (settings -> sensors) and you can use all power fields.

Compatible with Karoo 2 and Karoo 3 devices running Karoo OS version 1.524.2003 and later.

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

To calculate cycling wattage, you need to provide the following parameters:

- **Weight of Bike**: Include the weight of your bike along with any additional gear (in kg).
- **Rolling Resistance Coefficient**: Depends on the type of surface and the tires you are using. You can use info from here https://www.bicyclerollingresistance.com/ 
- **Aerodynamic Drag Coefficient**: Depends on your position on the bike and your frontal area
- **Frontal Area**: The area of your body that is exposed to the wind (m2)
- **Power Losses**: Includes losses due to chain resistance and derailleur pulleys.
- **Headwind**: The wind speed in the opposite direction of your movement. You can insert a constant headwind or check automatic option.
- **FTP**: Your Functional Threshold Power (in watts). If you don't know your FTP, you can use the default value of 200 watts.
- **Wind API Key**: You can use openweathermap to get the wind speed. You need to get an API key from openweathermap (free but you need to create an account) and introduce it in the configuration. Openweathermap is most acurate than Openmeteo

If you select automatic option, the app will get the wind speed from openweathermap (you need to select openweather option also) or openmeteo. 

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

Check https://www.bicyclerollingresistance.com/

**Power Losses**

1.0% SRAM CERAMIC / FORCE

1.3% SHIMANO ULTEGRA - DURACE

2.0% SRAM EAGLE

2.2% SHIMANO XTR

3%-4% SHIMANO OTHER

FTP is necessary to smooth the power estimation. If you don't know your FTP, you can use the following formula to get an estimation:
FTP = 0.95 * 20 minutes power  or use a value between 150 and 200 watts and adjust it later.

2- Kpower emulates a real power meter, then you need to add this power meter in the sensors configuration. 
Start scan and  you'll see a new category (looks like a puzzle piece), select the powermeter.

3- Kpower will show you the power estimation in the power fields. You can use the power fields in the data screens, in the workout builder, etc. It's like a real power meter.


## Features

This release has the following new features:
- Updated power estimation formula.
- Added wind speed parameter with openmeteo (from Timklge repository headwind).
- Added FTP to smooth the power estimation.
- Added wind speed using openweathermap.
- Added cadence to discard some power estimations (cadence lower than 10 rpm when you don't go uphill ). Cadence is better estimator than speed, but we cannot use directly because we need to know torque (and don't have this value)
But we can use cadence to discard some bad estimations (when you go down a hill, for example, and you don't pedal).
  
## Known issues

- Power meter is not 100% accurate, it is only a estimation based in power formula. It is not possible to get the real power data from the Karoo without a power meter.
There is currently a big important parameter in the power estimation, the wind. The wind can change the power needed to maintain a speed. 
Now you can use openmeteo, openweathermap and you can introduce, also, the headwind speed manually in the configuration.
A not very good estimation for this is take the value from this https://headwind.app/ or use 0.0 as default value.
Wind is a very important parameter to get a good estimation. You can use openmeteo, openweathermap or introduce the headwind speed manually.
If you want to use openweathermap (better because they use near stations), you need to get an API key from openweathermap (free but you need to create an account) and introduce it in the configuration.

- Power meter use values from Karoo (real), sometimes Karoo has some "delays/lags" or Karoo expose bad information (for example, current slope grade) then Power Meter will estimate not accurate values. Most of times 5-10 seconds later all is fine ;)
  
- Tested only with Karoo 3 and Metric configuration, but can be used with Imperial configuration also (not tested)

- Sometimes it's necessary to rescan virtual power sensor when you update this extension. If you don't see the power meter active, remove current power meter and re-add.

## Credits

- Made possible by the generous usage terms of timklge. He has a great development and I use part of his code to create this extension.
  https://github.com/timklge?tab=repositories
- Power estimation https://www.gribble.org/cycling/power_v_speed.html

## Links

[karoo-ext source](https://github.com/hammerheadnav/karoo-ext)
[openmeteo](https://open-meteo.com/)
[openweathermap](https://openweathermap.org/)
