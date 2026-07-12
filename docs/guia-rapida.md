# KPower — Guía rápida (español)

KPower es un medidor de potencia para **Karoo 2 / 3** que funciona **sin potenciómetro**: *estima* la
potencia con un modelo físico (pendiente + resistencia a la rodadura + aerodinámica con densidad real
del aire + aceleración, menos pérdidas de transmisión). Y si **tienes** un potenciómetro real, KPower
puede leerlo para enriquecer el FIT grabado — con un **matiz de emparejamiento** importante (ver abajo).

> **Regla ANT+:** un medidor **no** puede leerse por ANT+ desde el Karoo y desde KPower a la vez (comparten
> la radio ANT+; el primero que lo engancha se lo queda, el otro se queda sin señal). Para que KPower lo lea
> **junto** al Karoo, empareja el medidor al Karoo por **BLE** (deja el ANT+ libre para KPower). Si tu medidor
> es **solo ANT+**, elige uno: o lo lee el Karoo, o lo lee KPower (sensor virtual **KPW** como fuente).

> La potencia es una **estimación**, no una medición. Los parámetros importan — ver
> **[configuration.md](configuration.md)** (en inglés).

## ¿Cuál es mi caso?

| Tu caso | Empareja como potencia del Karoo | Campos KPower que añadirías |
|---|---|---|
| **Sin potenciómetro** | **KPW Estimated** | ninguno (el Karoo ya da potencia/NP); opcional Est. Power / 3s / NP |
| **Medidor real + quiero el estimado (comparar)** | el medidor real (nativo, BLE o ANT+) | Est. Power / 3s / NP |
| **Dos medidores a la vez** | medidor A (nativo) | Real Power / 3s / NP / Cadencia (muestran el 2º medidor) |
| **Medidor real + extras al FIT** (par, power phase, PCO) | el medidor real por **BLE** al Karoo, y **activo en KPower** (ANT+) | ninguno en pantalla; KPower enriquece el FIT. Solo ANT+ → usa la fila de offset |
| **Offset aplicado a lo grabado** | **KPW &lt;marca modelo&gt;** (medidor solo en KPower) | ninguno; el Karoo graba la potencia corregida |

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
5. ¿Ves un sesgo constante frente al medidor real? Ajústalo en **Ajuste de potencia** (Factor % y/o
   Offset W): `Corregida = P × (1 + Factor/100) + Offset`. A 0 no toca nada. Está en modo simple y avanzado.

**3) Dos medidores a la vez (A vs B)**
El Karoo graba **una** fuente de potencia: empareja **A nativo** y **activa B en KPower**. Los campos
**Real Power / 3s / NP / Cadencia** muestran **B** → así ves A (nativo) y B (KPower) a la vez. Caso raro.
(Son medidores **distintos**, así que no hay conflicto ANT+.)

**4) Medidor real + extras al FIT (par, power phase, PCO…)**
1. *Medidor real* → **Escanear → Añadir → Activar**.
2. Empareja el medidor al Karoo **por BLE** (así KPower lo lee por ANT+ **sin conflicto** — ver la regla
   ANT+ de arriba). Medidor **solo ANT+**: no puedes tener ambos; usa el sensor virtual **KPW** como fuente
   del Karoo (KPower lee el medidor por ANT+, aplica tu offset y graba todas las dinámicas como dev fields).
3. El Karoo muestra las dinámicas nativas en vivo; KPower añade al FIT lo que el Karoo no graba (par,
   power phase, PCO, barycenter).

**5) Offset aplicado a la potencia grabada**
El **split** (setup 4) graba el medidor **crudo** en el Karoo, así que el offset **no** llega a la grabación.
Para que sí: empareja el medidor **solo en KPower** y pon **“KPW &lt;marca modelo&gt;”** como fuente del Karoo.
KPower aplica el offset y reemite → el Karoo graba la potencia **corregida** (sin dinámicas nativas; KPower
las graba todas como dev fields).

## Importante

**El Karoo ya muestra balance / eficacia del par / suavidad / par de forma nativa** con un medidor
emparejado nativo, así que KPower **no** los duplica en pantalla — **salvo un campo Balance I/D** (instant
+ media ponderada por potencia), para el caso **KPW-virtual (offset)** donde el Karoo no muestra dinámicas
nativas. Con medidor real, lo que aporta KPower está en el **FIT**: par, power phase, PCO, barycenter (el Karoo no los graba). Detalle:
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
