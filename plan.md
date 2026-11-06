# Murmur (antes NocheSonora) — Plan de implementación del MVP

> App Android que registra los sonidos relevantes de la noche (ronquidos, habla dormido, tos, otros), ignora el silencio y el ruido de fondo constante, y permite revisar y escuchar todo a la mañana siguiente. 100 % local, sin red.

Fecha del plan: 2026-11-03.

---

## 0. Resumen ejecutivo

| Aspecto | Decisión |
|---|---|
| Plataforma | Android, minSdk 26, targetSdk 36, compileSdk 37 (al implementar: las bibliotecas de 2026 exigen API 37; ver README A28) |
| Lenguaje / UI | Kotlin 2.4 + Jetpack Compose (Material 3), tema oscuro por defecto |
| Arquitectura | MVVM + un repositorio, DI manual con un `AppContainer` |
| Captura | `AudioRecord` 16 kHz mono PCM16, bloques de 100 ms (1.600 muestras) |
| Detección | 3 capas: línea base adaptativa → detector de eventos por umbral → YAMNet solo sobre candidatos |
| Clasificación | MediaPipe Tasks Audio 1.2.1 + `yamnet.tflite` (float32) en `assets/` |
| Persistencia | Room (sesiones, eventos, episodios, pausas) + DataStore (ajustes) |
| Audio en disco | WAV por clip en `filesDir/sessions/{sessionId}/{eventId}.wav`; grabación completa opcional en AAC 32 kbps |
| Ejecución | Foreground Service tipo `microphone` + partial wake lock |
| Pruebas | Lógica de detección en Kotlin puro con tests JVM y señales sintéticas |

---

## 1. Análisis del documento de requisitos

### 1.1 Lo que está claro y se respeta tal cual
- Alcance (solo Android, sin backend/red/analítica/auth, interfaz en español).
- Stack (Compose, Room, Coroutines/Flow, DI manual, `AudioRecord`, MediaPipe + YAMNet).
- Las 3 capas de procesamiento, los parámetros numéricos y el mapeo de etiquetas.
- Las 5 pantallas y su contenido.

### 1.2 Huecos, ambigüedades y la decisión tomada
Todas estas decisiones van también al README (sección "Decisiones de diseño").

| # | Ambigüedad / problema | Decisión (la más simple que funciona) |
|---|---|---|
| A1 | El documento termina en "Estructura de paquetes sugerida" sin contenido. | Se propone la estructura de la sección 3. |
| A2 | MediaPipe en modo `AUDIO_CLIPS` trocea el clip en ventanas de 0,975 s **sin solapamiento**. El requisito pide 50 %. | Ventaneo manual: se llama a `classify()` con exactamente 15.600 muestras por ventana, avanzando 7.800 muestras (salto 0,4875 s). Cada llamada devuelve un solo resultado. |
| A3 | "Engine sostenido" no está definido. | Las etiquetas `Engine`, `Idling`, `Engine starting` cuentan como FONDO solo si aparecen (≥ 0,30) en ≥ 70 % de las ventanas del evento; si no, cuentan como OTRO. |
| A4 | "Breathing" va a RONQUIDO "solo si es periódico". ¿Y si no lo es? | Si la heurística de periodicidad no se cumple, `Breathing` vota como OTRO (se guarda la etiqueta original). |
| A5 | ¿Qué pasa si ninguna ventana supera 0,30? | Categoría OTRO con etiqueta `Desconocido`. No se descarta: el evento superó el umbral de energía y el usuario puede corregirlo o borrarlo. |
| A6 | Post-roll de 2 s vs. hangover de 2,5 s. | El "fin real" del evento es el último bloque sobre el umbral. El clip = [inicio − 3 s, fin real + 2 s]. Como el hangover (2,5 s) es mayor que el post-roll, el audio ya está en memoria al cerrar el evento; no hay que esperar más. |
| A7 | Corte a los 60 s: ¿el siguiente evento lleva pre-roll? | No. El evento cortado termina en el segundo 60 sin post-roll y el siguiente empieza en ese mismo instante sin pre-roll (audio contiguo, sin duplicar). |
| A8 | "Eventos consecutivos de la misma categoría" para episodios. | Se ordenan los eventos de la sesión por hora; un evento se une al episodio anterior si **el evento inmediatamente anterior** es de la misma categoría efectiva y el hueco es < 30 s. Un evento de otra categoría en medio rompe el episodio. Se usa `userCategory ?: category`. |
| A9 | ¿Cómo se registran las pausas? | Nueva entidad `PauseEntity` (sessionId, startedAt, endedAt, reason). |
| A10 | El servicio no puede reiniciarse solo en Android 14+: un FGS de micrófono no puede arrancarse desde segundo plano. | No se usa `START_STICKY` para reanudar. Si el proceso muere, la sesión queda INTERRUMPIDA (ver 6.6). |
| A11 | `full.m4a` (MP4) queda corrupto si el proceso muere antes de cerrar el `MediaMuxer`, que es justo el caso que interesa validar. | Se graba en **AAC ADTS** (`full.aac`), un flujo que sigue siendo reproducible aunque se corte de golpe. ExoPlayer lo reproduce y permite saltar con `FLAG_ENABLE_CONSTANT_BITRATE_SEEKING`. Se documenta como desviación deliberada del requisito. |
| A12 | El panel "En vivo" de Diagnóstico sin sesión activa. | Muestra los datos del servicio si hay sesión. Sin sesión muestra "Sin sesión activa" y solo está disponible "Probar 10 segundos". Ese botón se desactiva mientras haya sesión (el micrófono está ocupado). |
| A13 | Aviso de < 500 MB libres: ¿bloquea? | Diálogo con "Cancelar" / "Comenzar igualmente". |
| A14 | ¿Cuándo se aplica la retención? | Al abrir la app y al terminar cada sesión (sin WorkManager). |
| A15 | Fuente de audio: `MIC` aplica AGC/supresión de ruido en muchos equipos, lo que falsea la línea base y "borra" el ventilador de forma impredecible. | `UNPROCESSED` si `AudioManager.getProperty(PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)` es `true`; si no, `VOICE_RECOGNITION` (el CDD lo obliga a no tener AGC). |
| A16 | Exclusión de optimización de batería. | Se pide una vez, tras explicar el motivo, con `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (permiso `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`). Si falla, se abre `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`. Esta app no va a Play Store, así que la restricción de Play sobre ese permiso no aplica; queda anotado en el README. |
| A17 | Corrección manual de categoría a FONDO. | El selector solo ofrece RONQUIDO, HABLA, TOS, OTRO. Para quitar algo se usa "Borrar evento". |
| A18 | Categoría del episodio cuando el usuario corrige un evento. | Tras cualquier corrección o borrado se reagrupan los episodios de la sesión completa (función pura, barata). |

### 1.3 Riesgos principales
| Riesgo | Impacto | Mitigación |
|---|---|---|
| El fabricante mata el proceso (Xiaomi, Samsung, Huawei) | Se pierde la noche | FGS + wake lock + exclusión de batería + latido en BD y estado INTERRUMPIDA |
| AGC/NS del micrófono | Línea base inestable | Fuente `UNPROCESSED`/`VOICE_RECOGNITION` (A15) |
| YAMNet confunde ronquido con motor/respiración | Mala categoría | Heurística de periodicidad + corrección manual + top-3 guardado |
| Otra app o una llamada toma el micrófono | Silencio sin aviso (Android 10+ entrega ceros) | `AudioRecordingCallback.isClientSilenced` + chequeo de `AudioManager.mode` + PauseEntity |
| Memoria en 8 h | OOM | Solo búferes acotados (anillo de 5 s, evento ≤ 65 s ≈ 2 MB) y nada de listas que crezcan |
| Inferencia lenta bloquea la captura | Bloques perdidos | La captura nunca espera: clasificación en otra corrutina con cola acotada |
| Disco | Lleno a mitad de noche | Verificación de 500 MB al inicio; cada clip ocupa como mucho ~2 MB |
| Entorno: hoy no hay JDK ni Android SDK en el equipo | No se puede compilar aquí | Instalar Android Studio (incluye el JBR 21) y el SDK 36 antes de compilar |

---

## 2. Versiones (verificadas el 2026-11-03)

Comprobadas en Google Maven / Maven Central / services.gradle.org:

```toml
[versions]
agp = "9.5.0"
kotlin = "2.4.21"
ksp = "2.3.13"              # KSP2, numeración propia; comprobar compatibilidad al sincronizar
composeBom = "2026.09.00"
activityCompose = "1.13.0"
lifecycle = "2.11.0"          # corregido al implementar (2.10.2 no existe)
navigationCompose = "2.10.2"  # corregido al implementar (2.11.0 no existe)
room = "2.8.5"
datastore = "1.3.0"           # comprobar al sincronizar
coroutines = "1.11.0"
media3 = "1.11.1"
mediapipeTasksAudio = "1.2.1"
junit = "4.13.2"
```

- Gradle wrapper: **9.8.2**.
- Modelo YAMNet (URL verificada, HTTP 200, 4.126.810 bytes):
  `https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/1/yamnet.tflite`
  Se descarga una sola vez al montar el proyecto y se copia a `app/src/main/assets/yamnet.tflite`; la app nunca usa la red. Se agrega `androidResources { noCompress += "tflite" }`.
- `abiFilters`: `arm64-v8a`, `armeabi-v7a`, `x86_64` (emulador) para limitar el tamaño de las librerías nativas de MediaPipe.

---

## 3. Estructura del proyecto y de paquetes

```
Murmur/
├─ settings.gradle.kts
├─ build.gradle.kts
├─ gradle/libs.versions.toml
├─ gradle/wrapper/…
├─ README.md
├─ plan.md
└─ app/
   ├─ build.gradle.kts
   └─ src/
      ├─ main/
      │  ├─ AndroidManifest.xml
      │  ├─ assets/yamnet.tflite
      │  ├─ res/ (strings en español, íconos, tema)
      │  └─ java/com/nochesonora/
      │     ├─ MurmurApp.kt            # Application: crea AppContainer, marca sesiones huérfanas, aplica retención
      │     ├─ AppContainer.kt              # DI manual
      │     ├─ MainActivity.kt
      │     │
      │     ├─ core/                        # ⚠ Kotlin puro, sin imports de android.*
      │     │  ├─ audio/
      │     │  │  ├─ Pcm.kt                 # rms→dBFS, short→float
      │     │  │  ├─ RingBuffer.kt          # anillo de ShortArray (5 s)
      │     │  │  └─ WavWriter.kt           # escribe WAV a un OutputStream
      │     │  ├─ detection/
      │     │  │  ├─ DetectorConfig.kt      # todos los parámetros numéricos
      │     │  │  ├─ BaselineTracker.kt     # capa 1
      │     │  │  ├─ EventDetector.kt       # capa 2 (máquina de estados)
      │     │  │  └─ DetectorState.kt       # CALIBRANDO / ESCUCHANDO / EVENTO
      │     │  ├─ classification/
      │     │  │  ├─ Category.kt            # RONQUIDO, HABLA, TOS, OTRO, FONDO
      │     │  │  ├─ LabelMapper.kt         # etiqueta YAMNet → categoría
      │     │  │  ├─ SnoreHeuristic.kt      # periodicidad 2–6 s + energía < 500 Hz
      │     │  │  ├─ VotingClassifier.kt    # votación ponderada + top-3
      │     │  │  └─ Windowing.kt           # ventanas 15.600 / salto 7.800
      │     │  └─ episodes/
      │     │     └─ EpisodeGrouper.kt      # agrupación < 30 s
      │     │
      │     ├─ audio/                       # Android: captura y codificación
      │     │  ├─ AudioCapture.kt           # AudioRecord + Flow<ShortArray>, fuente de audio, reintentos
      │     │  ├─ YamnetClassifier.kt       # wrapper de MediaPipe AudioClassifier
      │     │  ├─ AacFullRecorder.kt        # MediaCodec → ADTS
      │     │  └─ InterruptionMonitor.kt    # micrófono silenciado / llamada
      │     │
      │     ├─ service/
      │     │  ├─ NightSessionService.kt    # FGS, wake lock, notificación
      │     │  ├─ SessionEngine.kt          # orquesta captura → detector → clasificador → repositorio
      │     │  ├─ LiveState.kt              # StateFlow con nivel, base, estado, contadores, top-3
      │     │  └─ SessionNotification.kt
      │     │
      │     ├─ data/
      │     │  ├─ db/ (AppDatabase, entidades, DAOs, Converters)
      │     │  ├─ settings/SettingsRepository.kt   # DataStore
      │     │  ├─ files/SessionFileStore.kt        # rutas, borrado, tamaño ocupado, espacio libre
      │     │  ├─ RetentionPolicy.kt               # lógica pura de qué borrar
      │     │  └─ NightRepository.kt               # el repositorio único
      │     │
      │     └─ ui/
      │        ├─ theme/ (Color.kt con colores por categoría, Theme.kt, Type.kt)
      │        ├─ navigation/NavGraph.kt
      │        ├─ common/ (ClipPlayer, LevelMeter, CategoryChip, ConfirmDialog)
      │        ├─ home/ (HomeScreen, HomeViewModel)
      │        ├─ history/ (HistoryScreen, HistoryViewModel)
      │        ├─ detail/ (NightDetailScreen, NightDetailViewModel, Timeline)
      │        ├─ settings/ (SettingsScreen, SettingsViewModel)
      │        └─ diagnostics/ (DiagnosticsScreen, DiagnosticsViewModel)
      │
      └─ test/java/com/nochesonora/core/…   # tests JVM de todo `core/`
```

Regla: `core/` no puede importar `android.*`, `androidx.*` ni MediaPipe. Así se testea en la JVM.

---

## 4. Pipeline de audio en detalle

```
AudioRecord (16 kHz, mono, PCM16, bloques de 1.600 muestras = 100 ms)
   │
   ├──► RingBuffer (5 s = 80.000 muestras) ─────────────┐ pre-roll
   ├──► AacFullRecorder (si está activado)              │
   ▼                                                    │
dBFS del bloque ──► BaselineTracker ──► EventDetector ──┘
                                            │ (evento cerrado: PCM del clip)
                                            ▼
                              Channel(capacidad 4) ──► worker de clasificación
                                                          │ Windowing → YAMNet → Voting + SnoreHeuristic
                                                          ▼
                                     FONDO: se descarta  |  otra: WAV + EventEntity + reagrupar episodios
```

### 4.1 Captura (`AudioCapture`)
- `AudioRecord.getMinBufferSize` × 4 como búfer interno; lectura bloqueante de 1.600 muestras en un hilo dedicado (`Dispatchers.IO.limitedParallelism(1)`).
- Fuente de audio según A15.
- Tiempo: cada tramo continuo de captura guarda un ancla `(wallClockMs, samplesRead = 0)`. La hora de un bloque = `ancla + samplesRead / 16`. Esto evita la deriva de llamar al reloj en cada bloque. Al reanudar tras una pausa se crea un ancla nueva.
- Si `read()` devuelve un error (`ERROR_DEAD_OBJECT`, `ERROR_INVALID_OPERATION`) se libera el `AudioRecord` y se reintenta con espera exponencial (1, 2, 4… hasta 30 s), registrando una pausa.

### 4.2 Nivel
- `dBFS = 20·log10(rms / 32768)`, con piso de −100 dB para bloques de ceros.

### 4.3 Capa 1 — `BaselineTracker`
Parámetros en `DetectorConfig`:
- `calibrationMs = 60_000`, `percentileWindowMs = 30_000`, `percentile = 0.20`, `emaTauMs = 120_000`.

Algoritmo, por cada bloque de 100 ms:
1. **Calibración (primeros 60 s):** se acumulan los niveles de los bloques que no son de un evento. Al terminar, `baseline = p20(niveles)` y `variabilidad = p80 − p20` (se guarda en la sesión como dato de diagnóstico). **Desde los 5 s (calentamiento) hay una base provisional** = p20 de lo acumulado hasta ese momento, y el detector ya abre eventos. La UI muestra "Escuchando · calibrando 0:42 / 1:00". *(Cambio tras la prueba en el teléfono, ver §12: con la versión anterior el detector no disparaba durante 60 s y una prueba de 1 minuto daba siempre 0 eventos.)*
2. **Seguimiento:** solo si el bloque **no** pertenece a un evento (estado ≠ EVENTO y ≠ ONSET), se agrega a una ventana deslizante de los últimos 300 niveles (30 s). Cada bloque:
   `objetivo = p20(ventana)`;
   `baseline += (objetivo − baseline) · (Δt / τ)` con Δt = 100 ms y τ = 120 s (α ≈ 0,00083).
3. Sale `baselineDb` y `thresholdDb = baselineDb + sensibilidad` (14 / 10 / 7 dB).

El percentil hace a la base robusta frente a picos; la EMA lenta hace que un ventilador que se enciende pase a ser el "nuevo silencio" en ~2–4 min. Mientras ese ventilador sube la base, puede abrirse un evento que el clasificador descartará como FONDO; es lo esperado.

Detalle: si un evento dura mucho (por ejemplo ronquido continuo), la base no se actualiza durante ese tiempo; está bien, porque son 60 s como máximo por evento y la base solo se congela en ese tramo.

### 4.4 Capa 2 — `EventDetector` (máquina de estados)
Parámetros: `onsetMs = 300`, `hangoverMs = 2_500`, `maxEventMs = 60_000`, `preRollMs = 3_000`, `postRollMs = 2_000`.

```
CALIBRANDO ──(fin del calentamiento, 5 s)──► ESCUCHANDO   (la calibración sigue de fondo hasta los 60 s)
ESCUCHANDO ──(nivel > umbral)──► ONSET (contador = 1)
ONSET ──(3 bloques seguidos > umbral)──► EVENTO   (inicio = primer bloque del onset)
ONSET ──(bloque ≤ umbral)──► ESCUCHANDO
EVENTO ──(bloque > umbral)──► actualiza últimoActivo, pico
EVENTO ──(25 bloques seguidos ≤ umbral)──► cierra: clip = [inicio − 3 s, últimoActivo + 2 s]
EVENTO ──(duración ≥ 60 s)──► cierra sin post-roll y abre otro EVENTO en el mismo instante
```

- Entrada: `onBlock(timestampMs, samples, levelDb)`. Salida: `DetectorOutput` (estado + `ClosedEvent?` con PCM, inicio, fin, pico dB).
- El PCM del evento se acumula en un `ShortArray` con crecimiento acotado (máximo (3 + 60 + 2,5) s × 16.000 ≈ 1,06 M muestras ≈ 2,1 MB).
- El pre-roll se copia del `RingBuffer` al entrar en EVENTO (los 3 s anteriores al inicio del onset).
- Al cerrar se recorta el PCM sobrante del hangover más allá de `últimoActivo + 2 s`.
- Una pausa (micrófono perdido) cierra el evento abierto inmediatamente con lo que haya.

### 4.5 Capa 3 — Clasificación
**`YamnetClassifier` (Android):**
- `AudioClassifier.createFromOptions(context, AudioClassifierOptions.builder().setBaseOptions(BaseOptions.builder().setModelAssetPath("yamnet.tflite").build()).setRunningMode(RunningMode.AUDIO_CLIPS).setMaxResults(10).setScoreThreshold(0.05f).build())`.
- Por ventana: `AudioData.create(AudioDataFormat.builder().setNumOfChannels(1).setSampleRate(16000f).build(), 15600)`, `load(FloatArray)` (PCM16 / 32768) y `classify(audioData)`.
- Una única instancia creada al iniciar la sesión y cerrada al final. Corre en un worker de un solo hilo.

**`Windowing` (puro):** ventanas de 15.600 muestras con salto de 7.800. Si el clip es más corto que una ventana o queda una cola, se rellena con ceros (YAMNet lo tolera). Un clip de 65 s da ~132 ventanas; en un teléfono medio son entre 1 y 3 s de inferencia, que corren fuera del hilo de captura.

**`LabelMapper` (puro):** coincidencia exacta con el nombre que devuelve MediaPipe (`category.categoryName()`):

| Categoría | Etiquetas |
|---|---|
| RONQUIDO | `Snoring`, `Gasp`, `Breathing` (solo si es periódico, A4) |
| HABLA | `Speech`, `Conversation`, `Whispering`, `Babbling`, `Narration, monologue`, `Shout`, `Laughter` |
| TOS | `Cough`, `Sneeze`, `Throat clearing` |
| FONDO | `Mechanical fan`, `Air conditioning`, `Hum`, `White noise`, `Pink noise`, `Silence`, `Static`, motor sostenido (A3) |
| OTRO | cualquier otra etiqueta ≥ 0,30 (se guarda el nombre original) |

**`SnoreHeuristic` (pura)** sobre el PCM del evento:
1. Envolvente de energía en bloques de 100 ms; suavizado de 3 bloques.
2. Picos: máximos locales ≥ mediana + 6 dB, con separación mínima de 1,5 s.
3. Periódico si hay ≥ 3 intervalos entre picos, todos en [2 s, 6 s], con coeficiente de variación < 0,35.
4. Baja frecuencia: energía < 500 Hz / energía total ≥ 0,6. Se calcula con un filtro paso bajo IIR de 2º orden (biquad, fc = 500 Hz) en lugar de una FFT, para mantenerlo simple.
5. `isSnoreLike = periódico && bajaFrecuencia`. También se expone `isPeriodic` (sirve para `Breathing`).

**`VotingClassifier` (puro):**
1. Por ventana, cada etiqueta con score ≥ 0,30 suma su score a su categoría (`Breathing` va a RONQUIDO solo si `isPeriodic`; el motor se resuelve con A3 después de contar ventanas).
2. Si `isSnoreLike`: `score[RONQUIDO] = score[RONQUIDO] · 1,5 + 0,3 · nVentanas`. Así se refuerza incluso si YAMNet no dijo "Snoring".
3. Categoría final = argmax. Sin votos → OTRO / `Desconocido` (A5).
4. `confidence = score[final] / nVentanas`, acotada a [0, 1].
5. `topLabels` = las 3 etiquetas con mayor score medio entre ventanas → JSON `[{"label":"Snoring","score":0.62},…]`.
6. Para OTRO, `otherLabel` = la etiqueta de OTRO con más peso.
7. **El fondo nunca le gana a un sonido real:** si RONQUIDO, HABLA o TOS tienen algún voto, el puntaje de FONDO se anula. FONDO solo puede ganarle a OTRO. *(Cambio tras la prueba en el teléfono, ver §12.)*
8. Si la final es FONDO → se descarta (no se escribe el WAV, nada en BD; en Diagnóstico y en Inicio se ve como "Ignorado").

### 4.6 Episodios — `EpisodeGrouper` (puro)
`fun group(events: List<EventLite>): List<EpisodeLite>`: ordena por `startedAt` y une un evento al episodio actual si la categoría efectiva es igual y `event.startedAt − episodio.endedAt < 30 s`. En el repositorio, tras insertar, corregir o borrar un evento, se recalculan los episodios de esa sesión dentro de una transacción (borrar episodios + insertar + actualizar `episodeId` de cada evento). Son unas pocas centenas de filas como mucho, así que el coste es despreciable.

### 4.7 Grabación completa (opcional) — `AacFullRecorder`
- `MediaCodec` AAC-LC, 16 kHz, mono, 32 kbps. Se alimenta con los mismos bloques PCM (no se abre otro `AudioRecord`).
- Cada frame de salida se escribe con una cabecera ADTS de 7 bytes en `full.aac` (A11). Se hace flush cada 10 s.
- Durante las pausas no hay audio, así que la hora real de un evento no coincide con el offset en el archivo. Se guarda el **offset en ms del archivo** al inicio de cada tramo de captura: `PauseEntity` lo guarda, y para saltar a un evento se calcula `offset = horaEvento − inicioSesión − Σ duración de las pausas anteriores`.
- Tamaño estimado: 32 kbps × 8 h ≈ 115 MB (mostrarlo en el diálogo antes de activarlo).

---

## 5. Modelo de datos (Room, versión 1, `exportSchema = true`)

```kotlin
enum class SessionStatus { EN_CURSO, COMPLETADA, INTERRUMPIDA }
enum class Category { RONQUIDO, HABLA, TOS, OTRO, FONDO }   // FONDO nunca se persiste

@Entity("sessions")
data class SessionEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val startedAt: Long,
  val endedAt: Long?,                 // null mientras está EN_CURSO
  val status: SessionStatus,
  val baselineDbAtStart: Float?,      // se llena al terminar la calibración
  val lastHeartbeatAt: Long,          // latido cada 30 s (para INTERRUMPIDA)
  val sensitivityDb: Int,             // umbral usado en esa noche
  val fullRecordingPath: String?,
  val isFavorite: Boolean = false,
  val notes: String?
)

@Entity("events", foreignKeys = [FK(sessions, CASCADE)], indices = [sessionId, episodeId])
data class EventEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val sessionId: Long,
  val episodeId: Long?,
  val category: Category,
  val otherLabel: String?,            // etiqueta YAMNet original cuando es OTRO
  val startedAt: Long, val endedAt: Long,
  val peakDb: Float,
  val confidence: Float,
  val topLabelsJson: String,
  val filePath: String,
  val userCategory: Category?
)

@Entity("episodes", foreignKeys = [FK(sessions, CASCADE)], indices = [sessionId])
data class EpisodeEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val sessionId: Long, val category: Category,
  val startedAt: Long, val endedAt: Long, val eventCount: Int
)

@Entity("pauses", foreignKeys = [FK(sessions, CASCADE)], indices = [sessionId])
data class PauseEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val sessionId: Long, val startedAt: Long, val endedAt: Long?,
  val reason: String                 // "MICROFONO_OCUPADO", "LLAMADA", "ERROR_AUDIO"
)
```

Cambios frente al documento: `lastHeartbeatAt`, `sensitivityDb`, `isFavorite`, `otherLabel` y la tabla `pauses`. Son todos necesarios para cumplir otros requisitos (sesión interrumpida, favoritas, OTRO con etiqueta original, registro de pausas).

**Consultas clave:** `observeSessionsWithSummary()` (Flow con duración y conteos por categoría efectiva: minutos de ronquido = suma de la duración de los episodios RONQUIDO), `observeSessionDetail(id)`, `findOrphanSessions()` (EN_CURSO).

**Ajustes (DataStore):** `sensitivity` (BAJA/MEDIA/ALTA, por defecto MEDIA), `saveFullRecording` (false), `retention` (SIEMPRE/DIAS_7/DIAS_30, por defecto SIEMPRE), `batteryPromptShown` (false).

**Archivos (`SessionFileStore`):** `filesDir/sessions/{sessionId}/{eventId}.wav`. El evento se inserta primero para tener el `id`; luego se escribe el WAV a `.tmp` y se renombra (escritura atómica); luego se actualiza `filePath`. Si falla la escritura, se borra la fila. Borrar una sesión borra la carpeta completa y la fila (cascada).

**Retención (`RetentionPolicy`, pura):** dada `now`, la política y la lista `(id, endedAt, isFavorite, status)`, devuelve los ids a borrar. Nunca borra favoritas ni la sesión EN_CURSO.

---

## 6. Foreground Service — `NightSessionService`

### 6.1 Manifest
```xml
<uses-permission android:name="android.permission.RECORD_AUDIO"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.WAKE_LOCK"/>
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"/>

<service android:name=".service.NightSessionService"
         android:exported="false"
         android:foregroundServiceType="microphone"/>
```
Sin permiso `INTERNET`: es una garantía verificable de que nada sale del teléfono.

### 6.2 Inicio (siempre desde la UI en primer plano)
1. Permisos: `RECORD_AUDIO` (obligatorio) y `POST_NOTIFICATIONS` en 33+ (si se niega, la sesión funciona igual; se avisa).
2. Primera vez: diálogo explicando la optimización de batería → A16. Se guarda `batteryPromptShown`.
3. Espacio libre ≥ 500 MB (`StatFs(filesDir)`), si no → A13.
4. `ContextCompat.startForegroundService()`; en `onStartCommand`: `ServiceCompat.startForeground(id, notif, FOREGROUND_SERVICE_TYPE_MICROPHONE)` **antes** de cualquier otra cosa.
5. Crea la `SessionEntity` (EN_CURSO), adquiere `PARTIAL_WAKE_LOCK` (`"Murmur:session"`, sin timeout, liberado en `finally`) y arranca `SessionEngine` en un `CoroutineScope(SupervisorJob() + Dispatchers.Default)`.

### 6.3 Notificación
- Canal "Sesión nocturna", importancia LOW (sin sonido).
- Título "Escuchando la noche", `setUsesChronometer(true)` + `setWhen(startedAt)` para el tiempo transcurrido sin redibujar cada segundo.
- Texto "N eventos detectados", que se actualiza solo cuando cambia el contador.
- Acción "Detener" → `PendingIntent` al servicio con `ACTION_STOP`. Toque → abre `MainActivity`.
- `setOngoing(true)` y `setForegroundServiceBehavior(FOREGROUND_SERVICE_IMMEDIATE)`.

### 6.4 Detener
`ACTION_STOP` o botón en Inicio → cierra el evento abierto (se clasifica y guarda), vacía la cola de clasificación (espera máximo 10 s), cierra el AAC, marca COMPLETADA con `endedAt`, libera AudioRecord, MediaPipe y wake lock, aplica la retención, `stopForeground` + `stopSelf`.

### 6.5 Interrupciones (`InterruptionMonitor`)
- API 29+: `AudioManager.registerAudioRecordingCallback`; si la configuración de nuestra sesión tiene `isClientSilenced == true` → pausa `MICROFONO_OCUPADO`; al volver a `false` → reanudar.
- Llamadas: sondeo de `AudioManager.mode` cada 2 s (`MODE_IN_CALL`/`MODE_IN_COMMUNICATION` → pausa `LLAMADA`). No requiere `READ_PHONE_STATE`.
- Errores de `read()` → pausa `ERROR_AUDIO` + reintento (4.1).
- API 26–28: solo el camino de errores + sondeo de modo (no existe el callback).
- Al pausar: cerrar el evento abierto, insertar `PauseEntity` y mostrar "En pausa (micrófono ocupado)" en la UI y la notificación. Al reanudar: cerrar la pausa, nueva ancla de tiempo y **no** se recalibra (se mantiene la base).

### 6.6 Muerte del proceso
- Latido: `lastHeartbeatAt = now` cada 30 s y en cada evento guardado.
- En `MurmurApp.onCreate` (y antes de iniciar una sesión): toda sesión EN_CURSO cuyo servicio no esté vivo (`LiveState.activeSessionId != id`) pasa a INTERRUMPIDA con `endedAt = lastHeartbeatAt`. Los clips ya escritos se conservan. Con ADTS, `full.aac` sigue siendo reproducible.
- `onStartCommand` devuelve `START_NOT_STICKY` (A10). `onTaskRemoved` no detiene la sesión (deslizar la app en "recientes" no debe matarla).

### 6.7 Estado en vivo
`LiveState` es un singleton en `AppContainer` (mismo proceso, sin binding). Expone un `StateFlow<LiveSnapshot>` con `sessionId, startedAt, phase (CALIBRANDO/ESCUCHANDO/EVENTO/PAUSA), levelDb, baselineDb, thresholdDb, calibrationProgress, countsByCategory, lastTop3`. El nivel se publica a 10 Hz como máximo; la UI hace `collectAsStateWithLifecycle`, así que con la pantalla apagada no cuesta nada.

---

## 7. Pantallas (Compose, Navigation Compose, barra inferior Inicio / Historial / Ajustes)

Colores por categoría (en tema oscuro, contraste AA): RONQUIDO ámbar `#FFB74D`, HABLA azul `#64B5F6`, TOS rojo `#E57373`, OTRO gris `#B0BEC5`. Fondo `#0E1116`. Tema oscuro siempre por defecto (sin seguir al sistema, para que no deslumbre de noche).

### 7.1 Inicio
- Sin sesión: botón grande circular "Comenzar noche"; tarjeta "🔌 Deja el teléfono conectado al cargador"; enlace a la última noche.
- Con sesión: botón "Detener" (con confirmación); cronómetro; estado ("Midiendo el silencio…" los primeros 5 s, "Escuchando · calibrando 0:42 / 1:00", "Escuchando", "Evento en curso", "En pausa"); medidor de nivel horizontal con la línea base y el umbral marcados; "Base: −52 dBFS · Umbral: −42 dBFS"; **"Fondo detectado: Ventilador · se ignora"** (YAMNet sobre 5 s de audio sin eventos, a los 5 s y al terminar la calibración); cuatro chips con contadores por categoría; tarjeta **"Lo último que se escuchó"** con los 6 clips más recientes (hora + categoría, o "Ignorado (fondo: …)") y el total ignorado como fondo.
- Mantener la pantalla encendida **no**: se puede apagar.

### 7.2 Historial
- `LazyColumn` de tarjetas: fecha ("Lun 2 nov · 23:14 → 06:51"), duración, insignia de estado, resumen ("Ronquido 42 min · Habló 3 veces · Tos 5 · Otros 7").
- Estrella para favorita; borrar desde un menú con `AlertDialog` de confirmación.
- Estado vacío: "Aún no hay noches registradas".

### 7.3 Detalle de la noche
- **Línea de tiempo** (`Canvas`): eje de la hora de inicio a la de fin, marcas de color por episodio (ancho proporcional, mínimo 3 dp), franjas grises para las pausas, marcas horarias cada hora. Al tocar una marca se hace scroll al episodio y se expande.
- **Resumen numérico**: mismos datos que en el historial, más la base inicial y la sensibilidad usada.
- **Episodios**: tarjeta expandible (categoría, hora, duración, n.º de clips). Al expandirla se ven los eventos: hora, duración, pico dB, confianza, top-3 (texto pequeño), reproductor, menú "Cambiar categoría" (RONQUIDO/HABLA/TOS/OTRO) y "Borrar" (con confirmación).
- **Reproductor**: un único `ExoPlayer` en el ViewModel (liberado en `onCleared`); el clip activo muestra play/pausa y un `Slider` de progreso. Al reproducir otro clip se detiene el anterior.
- **Grabación completa** (si existe): reproductor arriba; cada evento tiene "Ir a esta hora en la grabación completa" (offset según 4.7).

### 7.4 Ajustes
- Sensibilidad: segmented button Baja / Media / Alta (con "14 / 10 / 7 dB" debajo). Se aplica a la siguiente sesión.
- "Guardar grabación completa de la noche": switch; al activarlo, diálogo "Ocupa ~115 MB por cada 8 horas".
- Retención: Siempre / 7 días / 30 días, con la nota "Las favoritas nunca se borran".
- Almacenamiento: "La app ocupa 312 MB · Libre en el teléfono 21 GB"; botón "Borrar todo" (doble confirmación; deshabilitado durante una sesión).
- Entrada a "Diagnóstico".
- Versión de la app y "Todo se procesa en tu teléfono. Nada sale del dispositivo."

### 7.5 Diagnóstico
- En vivo (si hay sesión): nivel dBFS, base, umbral, estado del detector, top-3 de la última ventana clasificada (incluye los eventos descartados como FONDO, con la marca "descartado").
- "Probar 10 segundos" (sin sesión): pide el permiso si hace falta, graba 10 s en el ViewModel (`AudioCapture` directo, en primer plano), clasifica con ventanas al 50 %, y muestra el nivel medio, la categoría resultante, `isSnoreLike` y la lista de etiquetas con su score medio. No guarda nada.

---

## 8. Estrategia de pruebas

### 8.1 Tests JVM (`app/src/test`, JUnit 4), con un generador de señales sintéticas en `test/…/SignalGen.kt`
| Clase | Casos |
|---|---|
| `Pcm` | ceros → −100 dB; seno de escala completa → ≈ −3 dBFS |
| `RingBuffer` | escritura con vuelta, lectura de los últimos N ms |
| `WavWriter` | cabecera de 44 bytes correcta y tamaños |
| `BaselineTracker` | calibra a p20; un pico de 1 s no mueve la base; un escalón de +15 dB sostenido es absorbido en ~4 min; los bloques de evento no la actualizan |
| `EventDetector` | 200 ms de ruido no disparan; 300 ms sí; hangover de 2,4 s mantiene un solo evento y 2,6 s lo parte en dos; corte a 60 s; pre-roll de 3 s y post-roll de 2 s exactos; pausa cierra el evento |
| `Windowing` | número de ventanas, solapamiento y relleno |
| `LabelMapper` / `VotingClassifier` | mapeo de todas las etiquetas; votación ponderada; umbral 0,30; motor sostenido vs. puntual; sin votos → OTRO/Desconocido; FONDO; top-3 |
| `SnoreHeuristic` | pulsos de ruido filtrado a 150 Hz cada 3,5 s → snore-like; voz sintética aperiódica → no; pulsos cada 1 s → no |
| `EpisodeGrouper` | huecos de 29 s / 31 s; categoría intercalada rompe; `userCategory` manda |
| `RetentionPolicy` | 7/30 días, favoritas, EN_CURSO |

### 8.2 Tests instrumentados (mínimos)
- DAO de Room: cascada al borrar sesión, resumen por categoría.
- `YamnetClassifier`: carga del modelo desde assets y clasificación de 1 s de ceros (sin crash, devuelve resultados).

### 8.3 Validación manual del MVP (checklist del README)
1. Diagnóstico → "Probar 10 s" hablando, tosiendo, con el ventilador encendido.
2. Sesión de 15 min con el ventilador encendido todo el tiempo → 0 eventos FONDO guardados.
3. Encender el ventilador a mitad de la sesión → la base sube y no hay una avalancha de eventos.
4. Durante la sesión, abrir la grabadora de voz o recibir una llamada → pausa registrada y reanudación automática.
5. Forzar la detención desde Ajustes del sistema → al reabrir, la sesión aparece INTERRUMPIDA con la hora del último latido.
6. Noche real completa de 8 h con "Guardar grabación completa": batería consumida, memoria estable (sin crecimiento) y comparación de los eventos detectados con la grabación.
7. Probar en al menos un Samsung o Xiaomi con la optimización de batería por defecto.

---

## 9. Fases de implementación (orden recomendado)

Cada fase deja la app compilando.

> Estado (2026-11-03): código de F0–F8 escrito. `assembleDebug`, `test` (71 tests JVM en verde), `compileDebugAndroidTestKotlin` y `lintDebug` pasan (compilado con JDK 21 + SDK de Android dentro de Docker). Falta ejecutarlo en un teléfono: tests instrumentados y checklist §8.3. Ver README, sección "Estado".

- [x] **F0 — Esqueleto**: proyecto Gradle (KTS, version catalog, wrapper 9.8.2), `app` con Compose + tema oscuro + navegación con 3 pestañas vacías, `AppContainer`, `yamnet.tflite` en assets, README inicial.
- [x] **F1 — Núcleo puro + tests**: `core/audio`, `core/detection`, `core/classification`, `core/episodes`, `RetentionPolicy`, con todos los tests de 8.1 en verde.
- [x] **F2 — Datos**: entidades, DAOs, `AppDatabase`, `SettingsRepository`, `SessionFileStore`, `NightRepository` (incluye reagrupar episodios y borrado de sesión con archivos).
- [x] **F3 — Audio Android**: `AudioCapture`, `YamnetClassifier`, `InterruptionMonitor`, `AacFullRecorder`.
- [x] **F4 — Servicio**: `NightSessionService`, `SessionEngine`, `LiveState`, notificación, wake lock, latido, sesiones huérfanas.
- [x] **F5 — Inicio + permisos**: flujo completo de comenzar y detener, batería, espacio libre, UI en vivo.
- [x] **F6 — Historial + Detalle**: lista, favoritas, borrar, línea de tiempo, episodios, reproductor, corrección de categoría, grabación completa.
- [x] **F7 — Ajustes + Diagnóstico**: todas las opciones, almacenamiento, borrar todo, prueba de 10 s.
- [x] **F8 — Endurecimiento**: retención automática, revisión de fugas (wake lock, AudioRecord, ExoPlayer, MediaPipe), README final con decisiones y checklist de validación.

---

## 10. Criterios de aceptación del MVP

1. Una sesión de 8 h con el cargador conectado termina COMPLETADA, sin crash y con memoria estable.
2. Con un ventilador constante, la app no guarda eventos de ventilador.
3. Ronquidos continuos aparecen como uno o pocos episodios, no como cientos de filas.
4. Cada evento tiene un WAV reproducible con 3 s de pre-roll y 2 s de post-roll.
5. Si se mata el proceso, la sesión queda INTERRUMPIDA con la hora del último dato.
6. Una llamada o el micrófono ocupado generan una pausa visible y la captura se reanuda sola.
7. Borrar una sesión elimina sus archivos; la retención respeta las favoritas.
8. El APK no declara `INTERNET`.
9. `./gradlew test` en verde.

---

## 11. Fuera de alcance (explícitamente)
iOS, backend, sincronización, cuentas, exportar o compartir clips, widgets, estadísticas de varias noches, alarma inteligente, entrenamiento de modelos propios, idiomas distintos del español.

---

## 12. Fase 2 — Validación en teléfono y siguientes pasos

### 12.1 Bitácora de la primera prueba en un teléfono (2026-11-03)
| # | Lo que se vio | Causa | Corrección | Estado |
|---|---|---|---|---|
| B1 | Sesión de ~1 min hablando y tosiendo: "0 eventos". | La calibración de 60 s bloqueaba la detección: todo el minuto caía dentro de ella. | Base provisional desde los 5 s; los bloques de evento no alimentan la calibración (§4.3). | Corregido, con tests `detectsEventsWhileStillCalibrating` y `SessionSimulationTest` |
| B2 | Toses y ronquidos se habrían borrado igual pasado el minuto. | Votación: "Silence"/ventilador aparece en todas las ventanas del clip (pre/post-roll) y la tos en 1–2; sumando ventanas ganaba FONDO y el evento se descartaba. Comprobado con YAMNet real (mismo `.tflite`) sobre clips de ESC-50: **6 de 6** toses/ronquidos en cuarto silencioso salían FONDO. | El fondo nunca le gana a RONQUIDO/HABLA/TOS (§4.5 paso 7). Con la regla nueva, 15 de 15 clips salen bien. | Corregido, con tests `shortCoughAmongSilenceIsCough`, `speechOverFanIsSpeech` |
| B3 | Durante la noche no se ve qué está oyendo la app (el Diagnóstico sí lo mostraba). | Inicio solo mostraba contadores. | "Fondo detectado" + "Lo último que se escuchó" + total ignorado (§7.1). | Hecho |
| **B4** | **Sesión de 2 min (pasada la calibración): sigue sin mostrar nada. Causa principal del "0 eventos".** | `SessionFileStore.writeWav` cerraba el archivo y **después** llamaba a `fd.sync()`, que sobre un archivo cerrado lanza `SyncFailedException` siempre. El repositorio lo tomaba como fallo de escritura, borraba la fila y seguía **sin avisar**: ningún evento se guardó jamás. Reproducido en la JVM con el mismo orden de llamadas. | Orden correcto flush → sync → close en `WavWriter.writeFileAtomic` (núcleo puro, con test `atomicWriteSucceedsAndLeavesNoTmp`). Además, todo fallo de guardado se muestra ahora en Inicio en rojo ("NO SE GUARDÓ" + contador de errores) en lugar de perderse en el log. | Corregido |
| **B5** | **Prueba de 3 min (14:21–14:25) tras "corregir" B1–B4: "Sin eventos · 0 clips"; base −47 dBFS.** | Cada compilación se hizo en un contenedor nuevo, y la clave de firma de depuración (`~/.android/debug.keystore`) no se guardaba: **cada APK salía firmado con una clave distinta**. Android no instala una actualización firmada con otra clave encima de la app existente, así que lo más probable es que el teléfono siguiera con la **primera** versión, que tenía B1–B4. Además todas decían "0.1.0": no había forma de saber cuál estaba instalada. | Clave de depuración fija dentro del proyecto (`app/debug.keystore`, credenciales estándar `android`/`androiddebugkey`). Versión **0.2.0** (versionCode 2) visible en Inicio y en Ajustes; se sube en cada APK que se entrega. **Hay que desinstalar la app una vez** para pasar a la clave nueva. | Corregido |
| B6 | No había forma de probar la sesión completa sin un teléfono. | `SessionEngine` creaba el micrófono y YAMNet por dentro. | Se inyectan (`AudioSource`, `WindowClassifier`) y `SessionEngineTest` (Robolectric) corre la sesión real — motor → detector → clasificador → Room → WAV — con audio grabado: 2,5 min a −47 dBFS con habla en la calibración, habla, tos y ronquidos ⇒ 5 eventos con su WAV, 0 errores. Corre en el contenedor de compilación (Docker, máx. 3 GB / 2 CPU). | Hecho |
| B7 | La primera corrida de `SessionEngineTest` guardó 4 de 5 sonidos. | La cola de clasificación tenía capacidad 4 y descartaba (`trySend`) si se llenaba: una ráfaga de eventos (ronquidos seguidos, clips largos) podía perder eventos. | Capacidad 16 (≈ 32 MB en el peor caso). El test ahora guarda 5 de 5. | Corregido |
| — | Diagnóstico "Probar 10 s" reconoce bien tos, ronquido, habla y ventilador. | — | Confirma que captura + YAMNet funcionan en el teléfono. | OK |

### 12.2 Cómo probar ahora en el teléfono (prueba corta, 3 min)
1. **Esta vez, desinstalar NocheSonora primero** (mantener presionado el ícono → Desinstalar) y luego instalar el APK nuevo. Abrir la app: abajo en Inicio debe decir **"Versión 0.2.0"**. Si dice otra cosa, el APK nuevo no se instaló. Desde ahora las actualizaciones se instalan encima sin desinstalar.
2. Ventilador encendido. "Comenzar noche" y dejar el teléfono quieto 10 s: debe aparecer "Fondo detectado: …".
3. Hablar 3–4 s, esperar 5 s, toser 2–3 veces, esperar 5 s, roncar/imitar ronquido 10 s.
4. Cada sonido debe aparecer en "Lo último que se escuchó" unos 3–5 s después de terminar (el hangover es 2,5 s), y sumar en los contadores. Si aparece algo en rojo ("NO SE GUARDÓ" o "Errores"), anotar el texto exacto.
5. Detener y abrir la noche en Historial: deben estar los clips y poder escucharse.

### 12.3 Cómo se compila y valida (sin instalar nada en el equipo)
Todo corre en contenedores Docker que se borran al terminar (`--rm`), con recursos acotados:
- **Compilación + tests** (`eclipse-temurin:21-jdk`, máx. **3 GB RAM / 2 CPU**): `testDebugUnitTest assembleDebug`. Volúmenes persistentes: SDK de Android, caché de Gradle, caché de Maven de Robolectric y copia de trabajo (`nochesonora-android-sdk`, `nochesonora-gradle-home`, `nochesonora-m2`, `nochesonora-work`). Robolectric baja su `android-all` (~150 MB) de Maven Central; si responde 429 (límite de descargas), usar el espejo de Google con `-e JAVA_TOOL_OPTIONS=-Drobolectric.dependency.repo.url=https://maven-central.storage-download.googleapis.com/maven2`.
- **Capturas de pantalla sin teléfono:** `-e NS_SCREENSHOTS=1` y `testDebugUnitTest --tests com.nochesonora.ui.ScreenshotTest` → PNG en `app/build/screenshots` (Inicio en vivo y en reposo, detalle de la noche, historial, medidor y onda). Limitación: capturan el primer cuadro, así que el medidor en vivo y la onda del clip (que se llenan después) se revisan aparte en `5_componentes.png`.
- **Si una prueba en el teléfono vuelve a fallar sin explicación:** contenedor mínimo con `adb` (Alpine + android-tools, ~15 MB, 256 MB RAM) conectado al teléfono por **depuración inalámbrica** (Opciones de desarrollador → Depuración inalámbrica → Vincular con código). Permite leer el log real de la app (`adb logcat`) e instalar el APK directo, sin cable ni instalar nada en Windows.

### 12.4 Siguientes pasos (en orden)
- [x] **P1 — Repetir la prueba con el APK corregido (0.2.0).** Primera noche real el 2026-11-03: 23:31 → 03:04 (3 h 33 min, detenida a mano), estado COMPLETADA (sin muerte del proceso), base −52 dBFS, sensibilidad 10 dB, **17 clips**: Habló 8 veces, Otros 9, sin ronquidos ni tos. La mayoría de los eventos entre 23:31 y 23:41 (al acostarse); después, sueltos hacia 00:00, 01:15, 02:00, 02:25 y 02:40. **Pendiente: saber si se perdió algo** (P2). Detalle visto: episodios cortos se muestran como "0 s"; deberían decir "< 1 s".
- [ ] **P2 — Prueba de 1 hora** con ventilador y la grabación completa activada: comparar los eventos contra el audio completo (¿se perdió algo?, ¿cuántos "Ignorado" hubo y eran fondo de verdad?).
- [ ] **P3 — Ajustar sensibilidad con datos**: si se pierden sonidos suaves (habla en susurro), probar Alta (7 dB); si hay demasiado OTRO, revisar qué etiquetas YAMNet lo causan (top-3 guardado en cada evento).
- [ ] **P4 — Noche completa (8 h)** con cargador: batería, memoria estable, sin muerte del proceso. Repetir en un Samsung o Xiaomi.
- [ ] **P5 — Tests instrumentados** (`connectedAndroidTest`) con el teléfono conectado por USB.
- [ ] **P6 — APK más liviano** (opcional): hoy pesa ~125 MB porque incluye MediaPipe para 3 arquitecturas; un APK solo `arm64-v8a` en release bajaría mucho.
- [x] **P7 — Nuevo diseño (0.3.0).** Se eligió la dirección A del prototipo (§12.5). Falta verlo en el teléfono.

### 12.5 Diseño "Murmur · Instrumento" (0.3.0, 2026-11-04)
Prototipo de 3 direcciones en Claude (A Murmur, B Hush, C Nocturne); se eligió **A**. Solo cambia la interfaz: detección, clasificación y datos son los mismos.
- **Tema** (`ui/theme`): fondo `#0A0F1C`, superficies `#111829` con borde `#1E2940`, acento `#7CC4FF`; categorías ronquido `#F2B44B`, habla `#7CC4FF`, tos `#FF8A80`, otro `#9AA6B8`. Textos en Inter Tight; horas, dB y cifras en JetBrains Mono (fuentes variables en `res/font`, licencia OFL, sin red).
- **Inicio en vivo:** reloj de sesión grande `01:12:40`, "Escuchando · desde las 23:31", tarjeta "Nivel en vivo" con medidor de barras (las que pasan el umbral en azul, umbral punteado) y `base / umbral / fondo`, 4 casillas por categoría, "Lo último que se escuchó" con hora en mono, botón Detener abajo.
- **Inicio en reposo:** hora actual, consejos "Antes de dormir", tarjeta de la última noche con casillas, botón "Comenzar noche".
- **Detalle de la noche:** botones cuadrados volver/favorita (estrella), casillas (duración + categorías con algo), línea de tiempo con barras por episodio y horas debajo, episodios en tarjetas; al abrir: reproductor con la **onda real del WAV** (tocar para saltar), pico/confianza/top-3, "Cambiar categoría" y "Borrar".
- **Historial, Ajustes, Diagnóstico y barra inferior** con el mismo estilo.
- Episodios cortos ahora dicen "< 1 s" (antes "0 s").
- El nombre de la app pasó a ser Murmur en 0.3.1 (ver §12.6).

### 12.6 Cambio de nombre a Murmur (0.3.1, 2026-11-04)

- Visible: `app_name` (ícono y notificación), encabezado de Inicio, línea de versión en Ajustes y el texto que pide ignorar la optimización de batería.
- Código: `NocheSonoraApp` → `MurmurApp`, `NocheSonoraTheme` → `MurmurTheme`, `Theme.NocheSonora` → `Theme.Murmur`, etiqueta del wake lock `Murmur:session`, nombres de los hilos, `rootProject.name`.
- Carpeta del proyecto: `Desktop/Personal/Murmur` (antes `Desktop/Personal/NocheSonora`). En los comandos de Docker, el volumen `/src` debe apuntar a la carpeta nueva. APK: `Murmur-debug.apk`.
- **Se mantienen a propósito:** `applicationId` y paquete Kotlin `com.nochesonora`, y la base de datos `nochesonora.db`. Cambiar el `applicationId` haría que Android la trate como otra app: no se instalaría encima y las noches grabadas quedarían en la app vieja. Los volúmenes de Docker (`nochesonora-*`) también conservan su nombre para no volver a descargar el SDK y las cachés.

### 12.7 Calendario, calibración visible e idioma (0.4.0, 2026-11-04)

- **Historial = calendario.** Mes con flechas (desde el mes de la noche más antigua hasta el actual). Cada día con grabaciones lleva hasta 3 puntos del color de lo que se oyó (ronquido, habla, tos, otros; un punto azul si la noche no tuvo eventos). Al tocar un día se listan sus noches debajo; al abrir, queda seleccionado el día de la noche más reciente. Una noche pertenece al día en que **empezó** (23:31 del 3 → día 3). Lógica pura en `ui/history/CalendarModel.kt` con `CalendarModelTest`. Semana desde el lunes en español y desde el domingo en inglés.
- **Minuto de calibración.** Mientras `calibrationProgress < 1`: píldora "◌ MIDIENDO", estado "Midiendo el entorno" y una tarjeta con barra de progreso (0:23 / 1:00, "faltan 37 s"), qué está pasando y el paso siguiente. Al terminar, 12 s de aviso "Entorno medido" con el fondo detectado y la base; después "● GRABANDO". La detección no cambió: desde los 5 s ya hay umbral provisional y lo que suene en ese minuto se registra.
- **"Última noche" en Inicio** es la sesión terminada más reciente (completada o interrumpida); la que está en curso no cuenta. Es la misma primera fila del Historial.
- **Idioma.** Ajustes → Idioma: Español / English (español por defecto). Textos en `res/values*/strings.xml` y `strings_screens.xml`. Se guarda en SharedPreferences (`AppLanguage`) porque hay que leerlo de forma síncrona en `attachBaseContext` de la actividad, la `Application` y el servicio; al cambiarlo se recrea la actividad. Las fechas siguen el idioma ("Mar 3 nov" / "Tue, Nov 3"). En inglés las etiquetas de YAMNet se muestran tal cual. Si se cambia durante una sesión, la notificación cambia en la noche siguiente. Los mensajes técnicos de error del motor siguen en español.

### 12.8 La noche fuera de la app, tendencias, transcripción y más sonidos (0.5.0, 2026-11-04)

- **Pausar / continuar.** Desde la notificación (visible con el teléfono bloqueado: canal `night_session_v2` con importancia DEFAULT sin sonido, `VISIBILITY_PUBLIC`; el canal viejo se borra) y desde Inicio (Pausar / Continuar junto a Detener). La pausa manual (`PauseReason.USUARIO`) cierra el evento abierto, **suelta el micrófono** (se apaga el indicador verde de Android) y se registra como pausa en la línea de tiempo; al continuar se reabre el micrófono y se sigue con la misma base, sin recalibrar. Test `userPauseReleasesTheMicrophoneAndResumes`.
- **Resumen al despertar.** Al detener la sesión, notificación "Tu noche · Mar 3 nov" con duración, veces que habló, ronquido, tos y otros; tocarla abre esa noche. Canal propio sin sonido. Se puede apagar en Ajustes. Test `NightSummaryTest`.
- **Botón de ajustes rápidos** (`MurmurTileService`). Sin sesión: abre la app y empieza la noche por el mismo camino que el botón de Inicio (permisos, batería, espacio); así Android permite arrancar el micrófono, que no se puede iniciar desde segundo plano. Con sesión: la detiene. Muestra "Grabando" / "En pausa".
- **Widget** (`MurmurWidget`, RemoteViews sin dependencias nuevas). En reposo: la última noche y "Comenzar noche". Grabando: cronómetro y "Detener". Lo refrescan el servicio (empezar, pausar, continuar, terminar) y la app al abrirse; no se actualiza por tiempo.
- **Tendencias** (Historial → Tendencias): 7 o 30 días; noches, ronquido y habla por noche y tos total, con la comparación contra el periodo anterior; gráficas por día; noches destacadas. Lógica pura en `TrendsModel` con tests.
- **Transcripción** (Android 13+): solo el reconocedor **en el teléfono** (`createOnDeviceSpeechRecognizer`), leyendo el WAV del clip; nunca el de red, y la app sigue sin `INTERNET`. Si falta el idioma, el detalle ofrece "Descargar idioma" (lo descarga el sistema). Se transcriben solos los clips de habla al abrir la noche (se puede apagar en Ajustes). `events.transcript`: null = no intentado, "" = no se entendió. **Base de datos v2** con migración `MIGRATION_1_2` (no se pierden noches).
- **Más sonidos.** Los eventos "Otro" muestran uno de 28 tipos (perro, gato, bebé, puerta, pasos, alarma, tráfico, lluvia, música/TV, estornudo, ruido del cuarto…), a partir de 503 de las 521 etiquetas de YAMNet. Solo cambia cómo se muestra: la clasificación en RONQUIDO / HABLA / TOS / OTRO es la misma. Sección "Sonidos identificados" en el detalle.

**Pendiente de comprobar en el teléfono:** notificación en la pantalla de bloqueo y sus botones; que al pausar se apague el indicador del micrófono; el resumen al detener; añadir el botón de ajustes rápidos y el widget; la transcripción (depende de que el teléfono tenga reconocimiento en el dispositivo para español).

### 12.9 Logo "Luna de barras" (0.5.1, 2026-11-04)

- Elegido entre las 7 propuestas de `design/logo/murmur-logo.html`. Luna creciente recortada en barras verticales: dice noche y sonido a la vez, sin micrófono ni estrellas, y sigue el lenguaje de barras de la app.
- **Afinado respecto a la propuesta:** con 6 barras y el corte arriba a la derecha se leía como una gráfica de barras descendente. Ahora son 9 barras más finas y el corte es el de una luna clásica (una "C" abierta a la derecha), así se ven las dos puntas. Se eligió entre 4 variantes renderizadas.
- **Dónde está:** ícono adaptativo (`ic_launcher_foreground` sobre un fondo azul noche con brillo suave, dentro de la zona segura de 66 dp) con capa monocroma propia para los íconos temáticos de Android 13+ (`ic_launcher_monochrome`); versión pequeña de 5 barras con huecos más anchos para la notificación (`ic_stat_moon`), el botón de ajustes rápidos y el widget (`ic_tile`) y el encabezado de la app (`BarsIcon`).
- Las geometrías salen de recortar barras verticales con dos círculos (luna = C1 − C2); captura `13_logo` en `ScreenshotTest`.

### 12.10 Transcripción más fiable (0.5.2, 2026-11-04)

- **Problema en el teléfono:** clips en los que se oye claro lo que se dijo salían como "No se entendió" o con solo un trozo del texto.
- **Causas:** (1) la voz dormida es baja y el reconocedor la descartaba; (2) el clip empieza con ~3 s de pre-roll en silencio; (3) el reconocedor cortaba en la primera pausa, y la sesión segmentada no la respetan todos los reconocedores.
- **Arreglo (`transcription/SpeechPrep.kt`, lógica pura con pruebas):** filtro paso alto a ~80 Hz; detección de voz por energía relativa al ruido del propio clip (tramas de 30 ms, umbral = 2× el percentil 20, mínimo -66 dBFS); une tramos con pausas < 0,5 s en frases, descarta golpes sueltos < 150 ms, parte frases > 20 s por su punto más silencioso y como mucho 12 frases por clip; sube cada frase a -20 dBFS (máximo +30 dB, nunca baja) con limitador suave; 0,3 s de silencio antes y 1,2 s después.
- `SpeechTranscriber` reconoce cada frase con su propio reconocedor (sin sesión segmentada), con resultados parciales como respaldo si el final llega vacío o se acaba el tiempo, y un reintento ante errores pasajeros del servicio. Una frase que falla no tumba las demás.
- Si el reconocedor falla, el clip lo dice ("El reconocedor del teléfono no pudo con este clip") en vez de quedarse en blanco.
- Al actualizar, las transcripciones hechas con 0.5.0–0.5.1 se borran una vez (`MurmurApp`, preferencia `maintenance/transcript_pipeline`) y se rehacen solas al abrir cada noche.

### 12.11 Ruidos repetidos y filtro por categoría (0.6.0, 2026-11-05)

- **Problema en el teléfono:** durmiendo con el ventilador prendido, una noche guardó 124 clips de "viento" (el aire del ventilador sobre el micrófono da ráfagas que superan la base), y los 5 clips de habla quedaban perdidos entre ellos.
- **Ruidos repetidos (`core/classification/RepeatFilter.kt`, lógica pura con pruebas):** si un clip queda en OTRO con un tipo ambiental (viento, ventilador, lluvia o ruido del cuarto), el primero se guarda normal y los siguientes del mismo tipo se suman a él como repeticiones (sin WAV ni fila nueva) mientras no pase 1 hora sin oírlo; la hora cuenta desde la última repetición. Nunca se pliega un clip en el que YAMNet también oyó habla, ronquido o tos (score medio ≥ 0,04): ese se guarda igual. Si el clip de referencia se borró, se guarda uno nuevo.
- Base de datos v3: `events.repeats` (NOT NULL DEFAULT 0) y `events.lastRepeatAt`; migración `MIGRATION_2_3`, las noches existentes se conservan.
- Ajustes: "Guardar una sola vez los ruidos repetidos" (activado por defecto). Inicio muestra "Repetido · viento (ya guardado)" en lo último que se escuchó y cuántos se contaron sin guardar.
- **Detalle de la noche:** filtro de episodios por categoría con el número de clips de cada una (Todas · Ronquido · Habla · Tos · Otro; solo las que hay). Tocar en la línea de tiempo un episodio de otra categoría quita el filtro. El clip agrupado dice "Se repitió N veces más, hasta las HH:MM. Solo se guardó este clip." y "Sonidos identificados" cuenta todas las repeticiones.
- Pruebas: `RepeatFilterTest` y dos sesiones completas en `SessionEngineTest` (4 ráfagas de viento → 1 clip con 3 repeticiones; con el ajuste apagado → 4 clips). Captura `14_detalle_filtro`.
- Las noches ya grabadas no se compactan (sus clips siguen ahí); el filtro sirve para verlas.

### 12.12 Sonidos ignorados (0.7.0, 2026-11-05)

- **Pedido:** poder enseñarle a Murmur qué sonidos no guardar (el perro, el aire acondicionado, la nevera…), como se hizo con el ventilador pero elegido por la persona y para cualquier tipo de sonido.
- **En cada clip OTRO**, botón "Ignorar este sonido" (no se ofrece para ronquido ni tos, ni si ese tipo ya está ignorado). Abre un diálogo "¿Ignorar «Perro»?" con la casilla "Borrar también los N clips de este sonido que ya están guardados, de todas las noches" (desmarcada por defecto). Así se pueden limpiar noches viejas, como los 124 clips de viento.
- **Filtro (`core/classification/IgnoreFilter.kt`, lógica pura con pruebas):** un clip OTRO cuyo tipo (`SoundTypes.ofEvent`, el mismo que muestra el detalle) está en la lista no se guarda (ni WAV ni fila), salvo que YAMNet también oyera habla, ronquido o tos (`RepeatFilter.hasRealSound`). En voces, TV y risas la habla no protege (esos sonidos ya suenan a habla); ronquido y tos sí. Va antes del agrupado de repeticiones.
- La lista se guarda en DataStore (`ignoredSounds`, nombres de `SoundType`). El servicio la sigue en vivo: marcar un sonido a mitad de la noche cuenta desde ese momento.
- Ajustes → "Sonidos ignorados": la lista con "Dejar de ignorar", o cómo agregar uno si está vacía. Inicio muestra "Ignorado · Perro (está en tu lista)" y cuántos se ignoraron en la noche.
- Repositorio: `clipsOfType` (clips OTRO de todas las noches de un tipo) y `deleteEvents` (borra filas y WAV y rehace los episodios una vez por noche). Sin cambio de base de datos (sigue v3).
- Pruebas: `IgnoreFilterTest` (4) y tres en `SessionEngineTest` (viento ignorado → 0 clips; ignorar otro tipo no afecta; borrar los clips de un tipo). Captura `15_sonidos_ignorados`.
