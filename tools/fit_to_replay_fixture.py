#!/usr/bin/env python3
"""Convierte un FIT grabado por el Karoo en un fixture CSV para RideReplayTest.

Por qué existe: el replay puntúa el estimador contra un medidor real, así que el fixture
tiene que ser reproducible y auditable — si no, es un CSV mágico que nadie puede verificar.

Reconstruye además la meteo que el motor habría usado en marcha. KPower NO depende de la
extensión Headwind: pide su propia meteo a Open-Meteo (Extensions.kt:396) con
`wind_speed_unit=ms` y `surface_pressure`, y calcula el viento frontal en headwindFlow como
`ws * cos(wind_dir_from - rumbo)` cuantizado a 0.1 m/s. Aquí se replica esa fórmula usando
el rumbo derivado del GPS del propio FIT. Sin esto el replay corre a viento cero y aire ISA,
lo que sesga la estimación A LA BAJA y se confunde con un déficit de masa o de transmisión.

Uso:
    pip install fitdecode
    python3 tools/fit_to_replay_fixture.py <ride.fit> <salida.csv>

Los FIT del Karoo están en el dispositivo bajo /storage/emulated/0/FitFiles/<uuid>.fit
(accesible por adb sin root).

La meteo se pide para el CENTROIDE del track y se interpola linealmente en el tiempo (la
dirección, circularmente). Es una aproximación: un punto de rejilla y dato horario a 10 m
para toda la marcha. Suficiente para corregir el sesgo medio, demasiado grueso para el
instante — no esperes que mejore la correlación a 1 s.
"""
import json
import math
import sys
import urllib.request
import datetime as dt

import fitdecode

WX_URL = ("https://api.open-meteo.com/v1/forecast?latitude={lat:.4f}&longitude={lon:.4f}"
          "&hourly=wind_speed_10m,wind_direction_10m,temperature_2m,surface_pressure"
          "&wind_speed_unit=ms&timezone=UTC&past_days={past}&forecast_days=1")

HEADER = ("t_ms,speed_ms,alt_m,cadence_rpm,temp_c,karoo_grade_pct,real_power_w,"
          "headwind_ms,wx_temp_c,wx_pressure_hpa")
SEMICIRCLE = 180 / 2 ** 31


def read_records(path):
    rec = []
    with fitdecode.FitReader(path, check_crc=fitdecode.CrcCheck.DISABLED) as f:
        for fr in f:
            if isinstance(fr, fitdecode.FitDataMessage) and fr.name == "record":
                rec.append({x.name: x.value for x in fr.fields})
    rec.sort(key=lambda r: r["timestamp"])
    return rec


def fetch_weather(lat, lon, when):
    # past_days cubre la fecha de la marcha; el endpoint archive va con días de retraso.
    past = min(92, max(1, (dt.datetime.now(dt.timezone.utc) - when).days + 1))
    with urllib.request.urlopen(WX_URL.format(lat=lat, lon=lon, past=past), timeout=30) as r:
        return json.load(r)["hourly"]


def make_interpolators(hourly):
    ts = [dt.datetime.fromisoformat(t).replace(tzinfo=dt.timezone.utc).timestamp()
          for t in hourly["time"]]

    def at(key, t):
        vals = hourly[key]
        if t <= ts[0]:
            return vals[0]
        if t >= ts[-1]:
            return vals[-1]
        for i in range(len(ts) - 1):
            if ts[i] <= t <= ts[i + 1]:
                f = (t - ts[i]) / (ts[i + 1] - ts[i])
                return vals[i] + f * (vals[i + 1] - vals[i])
        return vals[-1]

    def direction_at(t):
        """Circular: interpolar 350°->10° linealmente daría 180°, justo el viento contrario."""
        vals = hourly["wind_direction_10m"]
        if t <= ts[0]:
            return vals[0]
        if t >= ts[-1]:
            return vals[-1]
        for i in range(len(ts) - 1):
            if ts[i] <= t <= ts[i + 1]:
                f = (t - ts[i]) / (ts[i + 1] - ts[i])
                a, b = math.radians(vals[i]), math.radians(vals[i + 1])
                x = (1 - f) * math.cos(a) + f * math.cos(b)
                y = (1 - f) * math.sin(a) + f * math.sin(b)
                return math.degrees(math.atan2(y, x)) % 360
        return vals[-1]

    return at, direction_at


def bearing_at(i, lat, lon, n, window=3):
    """Rumbo GPS sobre +-`window` segundos: un fix a fix es demasiado ruidoso para el coseno."""
    a, b = max(0, i - window), min(n - 1, i + window)
    while a < i and lat[a] is None:
        a += 1
    while b > i and lat[b] is None:
        b -= 1
    if lat[a] is None or lat[b] is None or a == b:
        return None
    p1, l1, p2, l2 = map(math.radians, (lat[a], lon[a], lat[b], lon[b]))
    dl = l2 - l1
    y = math.sin(dl) * math.cos(p2)
    x = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    if abs(y) < 1e-12 and abs(x) < 1e-12:
        return None
    return math.degrees(math.atan2(y, x)) % 360


def main(fit_path, out_path):
    rec = read_records(fit_path)
    if not rec:
        sys.exit("el FIT no tiene mensajes 'record'")
    n = len(rec)
    t0 = rec[0]["timestamp"]

    lat = [r["position_lat"] * SEMICIRCLE if r.get("position_lat") is not None else None for r in rec]
    lon = [r["position_long"] * SEMICIRCLE if r.get("position_long") is not None else None for r in rec]
    fixes = [(a, o) for a, o in zip(lat, lon) if a is not None]
    if not fixes:
        sys.exit("el FIT no tiene posiciones GPS; no se puede reconstruir el viento")
    clat = sum(a for a, _ in fixes) / len(fixes)
    clon = sum(o for _, o in fixes) / len(fixes)

    at, direction_at = make_interpolators(fetch_weather(clat, clon, t0))

    rows = [HEADER]
    headwind = 0.0            # production mantiene el último valor emitido si no hay rumbo
    for i, r in enumerate(rec):
        t = r["timestamp"].timestamp()
        speed = r.get("enhanced_speed", r.get("speed"))
        alt = r.get("enhanced_altitude", r.get("alt"))
        if alt is None:
            alt = r.get("altitude")
        brg = bearing_at(i, lat, lon, n)
        if brg is not None:
            ws, wd = at("wind_speed_10m", t), direction_at(t)
            headwind = round(ws * math.cos(math.radians(wd - brg)) * 10.0) / 10.0

        def fmt(v, k=2):
            return "" if v is None else (f"{v:.{k}f}" if isinstance(v, float) else str(v))

        rows.append(",".join([
            str(int((r["timestamp"] - t0).total_seconds() * 1000)),
            fmt(speed), fmt(alt, 1), fmt(r.get("cadence")), fmt(r.get("temperature")),
            fmt(r.get("grade")), fmt(r.get("power")), f"{headwind:.1f}",
            f"{at('temperature_2m', t):.1f}", f"{at('surface_pressure', t):.1f}",
        ]))

    with open(out_path, "w") as f:
        f.write("\n".join(rows) + "\n")

    hw = [float(r.split(",")[7]) for r in rows[1:]]
    print(f"{n} filas -> {out_path}")
    print(f"centroide {clat:.4f},{clon:.4f}  {t0:%Y-%m-%d %H:%M} UTC")
    print(f"headwind medio {sum(hw) / len(hw):+.2f} m/s (min {min(hw):+.1f}, max {max(hw):+.1f})")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
