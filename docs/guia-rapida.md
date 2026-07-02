# KPower — Guía rápida (español)

KPower es un medidor de potencia para **Karoo 2 / 3** que funciona **sin potenciómetro**: *estima* la
potencia con un modelo físico (pendiente + resistencia a la rodadura + aerodinámica con densidad real
del aire + aceleración, menos pérdidas de transmisión). Y si **tienes** un potenciómetro ANT+ real,
KPower puede funcionar en paralelo y enriquecer el FIT grabado.

> La potencia es una **estimación**, no una medición. Los parámetros importan — ver
> **[configuration.md](configuration.md)** (en inglés).

## ¿Cuál es mi caso?

| Tu caso | Empareja como potencia del Karoo | Campos KPower que añadirías |
|---|---|---|
| **Sin potenciómetro** | **KPW Estimated** | ninguno (el Karoo ya da potencia/NP); opcional Est. Power / 3s / NP |
| **Medidor real + quiero el estimado (comparar)** | el medidor real (nativo) | Est. Power / 3s / NP |
| **Dos medidores a la vez** | medidor A (nativo) | Real Power / 3s / NP / Cadencia (muestran el 2º medidor) |
| **Medidor real + extras al FIT** | el medidor real (nativo) | ninguno — el Karoo muestra las dinámicas; KPower solo enriquece el FIT |

## Pasos

**1) Sin potenciómetro (solo estimación)**
1. Pestaña *Estimador* → **+** → rellena la bici (modo simple: preset, altura, neumático, peso). El FTP sale de tu perfil del Karoo.
2. En el Karoo: **perfil de ruta → sensores → empareja “KPW Estimated”** como fuente de potencia.
3. Rueda. Potencia, NP, TSS y zonas funcionan de forma nativa.

**2) Medidor real + estimado (comparar)**
1. *Medidor real* → **Escanear → Añadir → Activar**.
2. Empareja el medidor **nativo** en tu perfil de ruta (el Karoo muestra potencia, cadencia, balance, eficacia del par, suavidad y par).
3. Añade **Est. Power / 3s / NP** para ver el estimado junto a la potencia nativa.
4. Activa **“Grabar estimada (FIT)”** para comparar luego en [intervals.icu](https://intervals.icu).

**3) Dos medidores a la vez (A vs B)**
El Karoo graba **una** fuente de potencia: empareja **A nativo** y **activa B en KPower**. Los campos
**Real Power / 3s / NP / Cadencia** muestran **B** → así ves A (nativo) y B (KPower) a la vez. Caso raro.

**4) Medidor real + extras al FIT (lo más habitual con medidor)**
1. *Medidor real* → **Escanear → Añadir → Activar**.
2. Empareja el medidor **nativo**.
3. Listo. El Karoo muestra las dinámicas en vivo; KPower añade al FIT lo que el Karoo no graba (par,
   power phase, PCO…). No necesitas campos KPower en pantalla.

## Importante

**El Karoo ya muestra balance / eficacia del par / suavidad / par de forma nativa** con un medidor
emparejado nativo, así que KPower **no** los duplica como campos en pantalla. Con medidor real, lo que
aporta KPower está en el **FIT**: par, power phase, PCO, barycenter (el Karoo no los graba). Detalle:
**[real-meter-and-fit.md](real-meter-and-fit.md)** (en inglés).

**¿Y dónde se ve luego?** Lo mejor: **intervals.icu**, ahí aparecen todos los campos solos. En **Garmin
Connect** verás la eficacia de par / fluidez de pedaleo / balance nativos (si emparejaste el medidor
nativo), pero lo propio de KPower (par, fase de potencia, desplazamiento del centro de la plataforma,
barycenter) sale como gráficos "developer" aparte, no dentro de su sección "Cycling Dynamics" — sin el
arco de fase de potencia (limitación del SDK, no un bug). **Strava** ignora los developer fields.

## Instalación

**Karoo 3 (v ≥ 1.527):** abre en el móvil el [APK más reciente](https://github.com/lockevod/Karoo-KPower/releases/latest/download/kpower.apk)
→ compartir a la app **Hammerhead Companion** → instalar.

**Karoo 2:** activa el sideload y `adb install app-release.apk`.

> ¿Vienes de una versión **< 1.9.5**? Desinstala la antigua primero (formato de datos incompatible).
