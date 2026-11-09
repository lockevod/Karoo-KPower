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
- **Surface** — asphalt / concrete · smooth gravel / hardpack · gravel / dirt · sand / mud / soft;
  scales Crr.
- **Auto surface from offline maps** (on by default) — KPower reads the surface under you from your
  offline maps and adjusts Crr as you ride. With no maps for the area (or no storage permission) it
  falls back to the surface you picked above, which is then labelled **Default surface**.
- **FTP** — taken from your Karoo profile automatically (turn off to type it manually). Used to smooth
  the estimate and to scale the power cap.
- **Bike + gear weight** (kg) — the bike as you ride it (pedals, full bottles, tools) *plus* helmet,
  clothing, shoes and any pack. Your body weight comes from your Karoo profile, so this field is the
  only place your kit's weight enters the model.

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

These are the values **Simple mode already uses**, so switching Simple → Advanced won't jump.

**Cd and frontal area.** What the model actually uses is the product **Cd × area**. A rider+bike has a
Cd of roughly 0.7–0.9 in every position — getting aero shrinks your *area*, not your Cd. KPower
estimates the area from your height and weight (Bassett et al. 1999) and scales it per position:

| Position | Cd | Area scale | Area¹ | Cd × area¹ |
|---|---|---|---|---|
| Road – hoods | 0.80 | 1.09 | 0.42 | 0.34 |
| Road – drops | 0.80 | 1.00 | 0.39 | 0.31 |
| Time trial | 0.72 | 0.84 | 0.33 | 0.23 |
| Gravel | 0.85 | 1.12 | 0.43 | 0.37 |
| MTB | 0.90 | 1.45 | 0.56 | 0.51 |

¹ For a 1.78 m / 75 kg rider (base area 0.387 m²). **Yours will differ** — taller or heavier means a
bigger area. To get your own: area = (0.0293 × height_m × weight_kg^0.425 + 0.0604) × the scale above.

**Crr.** Simple mode derives it from tread, width, pressure and tubeless; the pressure penalty grows as
you move away from the optimum for your width and load (Frank Berto). Typical results:

| Tyre | With tube | Tubeless |
|---|---|---|
| Road slick, 28 mm @ 5.0 bar | 0.0052 | 0.0046 |
| Gravel semi-slick, 40 mm @ 3.0 bar | 0.0081 | 0.0070 |
| MTB knobby, 2.3" @ 2.0 bar | 0.0122 | 0.0097 |

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
force power at low cadence in the config. With **no cadence sensor paired**, sustained movement stands
in for pedalling, and a brief sensor dropout holds the last state instead of reading as "stopped".

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

**Coming from an earlier 3.0.0 build?** The weight field now asks for the bike **plus your gear**.
Existing bikes keep the number you typed before, so check it once — most riders are 2–5 kg short.

Existing profiles keep working unchanged: stored values (Crr, Cd, area, power loss, FTP, surface) are
used as before; upgraded profiles open in **Advanced** mode and keep your configured FTP. Simple mode /
“FTP from profile” apply to **newly created** bikes. Editing a tyre/height field on a profile recomputes
Crr / frontal area from it (intended).
