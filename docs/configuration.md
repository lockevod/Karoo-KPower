# KPower — Configuration

How to set up the **Estimator** so the estimated power is accurate. Settings live under the
**Estimator** tab (your bikes) — Simple mode for most people, Advanced for manual control.

## Simple mode (recommended)

Pick what you know; the app derives the rest:

- **Bike preset / position** — Road (hoods/drops), Time trial, Gravel, MTB. Sets a sensible aero drag
  and default tyre/surface.
- **Rider height** (cm) — with your weight (from the Karoo profile) estimates your **frontal area**
  (Bassett et al.).
- **Tyre** — width (mm; inches for MTB e.g. `2.3`), pressure (bar), tread, tubeless. Derives **rolling
  resistance (Crr)**; the pressure penalty scales with your load and tyre width (Frank Berto tables),
  so wide tyres correctly want lower pressure. If front/rear differ, enter the **rear** tyre.
- **Surface** — asphalt / mixed / gravel / off-road; scales Crr.
- **FTP** — taken from your Karoo profile automatically (turn off to type it manually). Used to smooth
  the estimate and to scale the power cap.
- **Bike weight** (kg) — bike + gear.

## Power offset (both modes)

A manual correction of the estimated power, for when you've compared it against a real meter and want
to nudge it: **Corrected = P × (1 + Factor/100) + Offset**.

- **Factor (%)** — scales the whole curve. `-5` = 5 % lower everywhere; `0` = no scaling.
- **Offset (W)** — a flat shift. `+10` = 10 W added everywhere; `0` = no shift.

Leave both at **0** (the default) for no change. The result is floored at 0 W. Shown in Simple and
Advanced mode.

## Advanced mode

Type the physics values directly (preset/height/tyre inputs are hidden so nothing recalculates):

- **Crr** — rolling resistance (see <https://www.bicyclerollingresistance.com/>)
- **Cd** — aerodynamic drag coefficient (depends on position)
- **Frontal area** (m²)
- **Power loss** (%) — drivetrain
- **Use Karoo temperature sensor** — fallback air-density source when no weather data (the internal
  sensor reads a few °C high, so an offset is applied).

### Typical values

| Position | Cd / Area |
|---|---|
| Aerobars | 0.25 / 0.30 |
| Drops | 0.35 / 0.40 |
| Hoods | 0.45 / 0.55 |
| Tops | 0.60 / 0.75 |
| MTB | 0.80 / 0.90 |

| Tyres | Crr |
|---|---|
| Top road | 0.0045 |
| Mid road | 0.0065 |
| Low road | 0.0085 |
| MTB | 0.0095 |

Drivetrain loss: SRAM ceramic/Force 1.0% · Shimano Ultegra/Dura-Ace 1.3% · SRAM Eagle 2.0% ·
Shimano XTR 2.2% · other 3–4%.

No FTP? Use `0.95 × 20-min power`, or start at 150–200 W and adjust.

## Wind & weather

- **Open-Meteo, automatic** — temperature, pressure and wind, free, no API key, nothing to configure.
- **Headwind reuse** (on by default) — if the [Headwind](https://github.com/timklge/karoo-headwind)
  extension is installed, KPower reads weather from its stream instead of polling itself, with
  automatic fallback to Open-Meteo. The **Headwind wind unit** dropdown (Auto by default) tells KPower
  which unit Headwind sends wind in — set it if you changed Headwind to m/s or knots.

The virtual sensor gives 0 W when not pedalling (cadence gate: off < 20 rpm, on > 25 rpm); you can
force power at low cadence in the config.

## Multiple bikes & Karoo profiles

Keep one estimator bike per real bike and let KPower switch automatically:

1. *Estimator* → **+** per bike; fill its data.
2. Open a bike → **“Link to Karoo profile”** → the ride profile you use it with. (Missing profile?
   scroll to it once on the Karoo, or start a ride with it.)
3. Switching ride profile on the Karoo auto-selects the linked bike. If none is linked, KPower uses the
   bike marked **active**, or the first one.

Editing is **auto-saved** (no Save/Cancel): **+** creates and opens a bike, the back arrow returns,
**Delete** discards. Each bike has a **colour dot** for the list.

### Export / import

*Estimator* tab → **⋮** menu:
- **Export** → writes `kpower_bikes.json` to `Android/data/com.enderthor.kpower/files/` (a toast shows
  the path). Pull it with the Hammerhead Companion app or `adb`.
- **Import** → reads that same file back and **replaces** your bikes. Push the file into that folder
  first, then tap Import.

## Upgrading

Existing profiles keep working unchanged: stored values (Crr, Cd, area, power loss, FTP, surface) are
used as before; upgraded profiles open in **Advanced** mode and keep your configured FTP. Simple mode /
“FTP from profile” apply to **newly created** bikes. Editing a tyre/height field on a profile recomputes
Crr / frontal area from it (intended).
