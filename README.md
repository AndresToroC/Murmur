# Murmur

Murmur (antes NocheSonora) es una app Android que registra los sonidos relevantes de la noche (ronquidos, habla dormido, tos y otros), ignora el silencio y el ruido de fondo constante, y permite revisarlos y escucharlos a la mañana siguiente. Todo es local: el APK no declara `INTERNET`.

La especificación completa está en [`plan.md`](plan.md).

## Estado

| Qué | Estado |
|---|---|
| Fases F0–F8 de `plan.md` §9 | Código escrito |
| `./gradlew assembleDebug` | **BUILD SUCCESSFUL** (APK debug de ~125 MB, casi todo librerías nativas de MediaPipe para 3 ABI) |
| `./gradlew test` | **130 tests JVM en verde, incluida la sesión completa con Robolectric** (núcleo puro, `RetentionPolicy`, posición en la grabación completa) + 14 capturas de pantalla en español e inglés (`ScreenshotTest`, solo con `NS_SCREENSHOTS=1`) |
| Diseño (0.3.0, 2026-11-04) | Dirección **A "Murmur · Instrumento"** del prototipo: azul noche, Inter Tight + JetBrains Mono (incluidas en `res/font`, OFL), medidor de barras, casillas, onda del clip. Ver `plan.md` §12.5 |
| Nombre (0.3.1, 2026-11-04) | La app se llama **Murmur** (ícono, encabezado, Ajustes). El identificador interno sigue siendo `com.nochesonora` para que se instale encima y conserve las noches grabadas. Ver `plan.md` §12.6 |
| Calendario, calibración e idioma (0.4.0, 2026-11-04) | Historial como calendario mensual; aviso del minuto de calibración en Inicio; idioma español / inglés en Ajustes. Ver `plan.md` §12.7 |
| Noche fuera de la app (0.5.0, 2026-11-04) | Pausar / continuar desde la notificación (también en la pantalla de bloqueo), resumen al despertar, botón de ajustes rápidos, widget, tendencias en Historial, transcripción en el teléfono de lo que se dice dormido y 28 tipos de sonido. Base de datos v2. Ver `plan.md` §12.8 |
| Logo (0.5.1, 2026-11-04) | "Luna de barras": luna creciente hecha de barras de nivel, en el ícono (con capa monocroma), la notificación, el botón de ajustes rápidos, el widget y el encabezado. Ver `plan.md` §12.9 |
| Transcripción mejorada (0.5.2, 2026-11-04) | Antes de reconocer, cada clip de habla se recorta (sin el pre-roll ni los silencios), se le sube el volumen a nivel de conversación y se parte en frases que se reconocen una por una; los resultados parciales sirven de respaldo. Las transcripciones viejas se rehacen solas. Ver `plan.md` §12.10 |
| Ruidos repetidos y filtro (0.6.0, 2026-11-05) | Viento, ventilador, lluvia o ruido del cuarto se guardan una vez por tramo de la noche y las repeticiones solo se cuentan ("se repitió 123 veces más, hasta las 5:40"); nunca si a la vez se oye habla, ronquido o tos. Se puede apagar en Ajustes. En el detalle de la noche, filtro por categoría (Todas · Ronquido · Habla · Tos · Otro). Base de datos v3. Ver `plan.md` §12.11 |
| Sonidos ignorados (0.7.0, 2026-11-05) | En cualquier clip de "Otro", "Ignorar este sonido" (perro, aire acondicionado, puerta…): desde ahí no se guarda, salvo que a la vez se oiga habla, ronquido o tos. Al ignorarlo se pueden borrar los clips de ese sonido de todas las noches. Lista con "Dejar de ignorar" en Ajustes. Ver `plan.md` §12.12 |
| `./gradlew compileDebugAndroidTestKotlin` | Compila |
| `./gradlew lintDebug` | Sin errores; 2 avisos deliberados: `OldTargetApi` (targetSdk 36, A28) y `BatteryLife` (A16) |
| Permisos del APK (`aapt2 dump permissions`) | Sin `INTERNET` ni `ACCESS_NETWORK_STATE` |
| Tests instrumentados (`connectedAndroidTest`) | Sin ejecutar: necesitan dispositivo o emulador |
| Validación manual (checklist de abajo) | Primera prueba en teléfono el 2026-11-03: sin eventos; 7 problemas encontrados y corregidos (ver `plan.md` §12). Primera noche real con 0.2.0 el 2026-11-03: 17 clips, sesión completada. 0.3.0 solo cambia el diseño (se instala encima de 0.2.0) |

Compilado el 2026-11-03 con JDK 21 (Temurin) y el SDK de Android (plataforma 37.0, build-tools 36.0.0) dentro de un contenedor Docker, porque el equipo no tiene JDK ni Android SDK instalados. Nada se ha ejecutado todavía en un teléfono.

## Cómo compilar

Requisitos: Android Studio reciente (trae el JBR 21) con el **SDK de Android 37** (plataforma `android-37.0`).

1. Abrir la carpeta `Murmur` en Android Studio y dejar que sincronice Gradle (wrapper 9.8.2, AGP 9.5.0).
2. Ejecutar en un dispositivo con Android 8.0 (API 26) o superior.

Desde la línea de comandos (con `JAVA_HOME` apuntando a un JDK 17+ y `ANDROID_HOME` al SDK, o un `local.properties` con `sdk.dir=...`):

```bash
./gradlew test
```

```bash
./gradlew assembleDebug
```

```bash
./gradlew connectedAndroidTest
```

El modelo `app/src/main/assets/yamnet.tflite` ya está en el repositorio (descargado una vez de `https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/1/yamnet.tflite`). La app nunca usa la red.

## Estructura

```
app/src/main/java/com/nochesonora/
├─ core/            Kotlin puro (sin android.*): PCM, WAV, ADTS, línea base, detector de eventos,
│                   ventaneo, mapeo de etiquetas, heurística de ronquido, votación, episodios
├─ audio/           AudioRecord, YAMNet (MediaPipe), grabación completa AAC/ADTS, interrupciones
├─ service/         Foreground Service, motor de la sesión, estado en vivo, notificación
├─ data/            Room, DataStore, archivos de audio, retención, repositorio único
└─ ui/              Compose: Inicio, Historial, Detalle, Ajustes, Diagnóstico
```

Arquitectura MVVM simple con un repositorio y DI manual (`AppContainer`), sin Hilt.

## Decisiones de diseño

Las de `plan.md` §1.2:

| # | Ambigüedad / problema | Decisión |
|---|---|---|
| A1 | El documento no traía estructura de paquetes. | La de `plan.md` §3. |
| A2 | MediaPipe en `AUDIO_CLIPS` trocea en ventanas de 0,975 s sin solapamiento; el requisito pide 50 %. | Ventaneo manual: `classify()` con exactamente 15.600 muestras por ventana, avanzando 7.800. |
| A3 | "Engine sostenido" no estaba definido. | `Engine`, `Idling`, `Engine starting` cuentan como FONDO solo si aparecen (≥ 0,30) en ≥ 70 % de las ventanas; si no, como OTRO. |
| A4 | `Breathing` va a RONQUIDO "solo si es periódico". ¿Y si no? | Si no es periódico, vota como OTRO (con la etiqueta original). |
| A5 | Ninguna ventana supera 0,30. | OTRO con etiqueta `Desconocido`; no se descarta. |
| A6 | Post-roll de 2 s vs. hangover de 2,5 s. | Clip = [inicio − 3 s, último bloque activo + 2 s]. El audio ya está en memoria al cerrar. |
| A7 | Corte a los 60 s. | El evento cortado termina en el segundo 60 sin post-roll; el siguiente empieza en ese instante sin pre-roll (audio contiguo). |
| A8 | "Eventos consecutivos" para episodios. | Orden por hora; se une al episodio si el evento inmediatamente anterior es de la misma categoría efectiva (`userCategory ?: category`) y el hueco es < 30 s. |
| A9 | Registro de pausas. | Entidad `PauseEntity`. |
| A10 | Android 14+ no deja reiniciar solo un FGS de micrófono. | `START_NOT_STICKY`; si el proceso muere la sesión queda INTERRUMPIDA. |
| A11 | `full.m4a` queda corrupto si el proceso muere. | Se graba en AAC **ADTS** (`full.aac`), reproducible aunque se corte. ExoPlayer salta con *constant bitrate seeking*. Desviación deliberada del requisito. |
| A12 | Panel "En vivo" sin sesión. | "Sin sesión activa"; solo "Probar 10 segundos", desactivado durante una sesión. |
| A13 | Aviso de < 500 MB libres. | Diálogo "Cancelar" / "Comenzar igualmente". |
| A14 | Cuándo aplicar la retención. | Al abrir la app, al terminar cada sesión y al cambiar el ajuste (sin WorkManager). |
| A15 | `MIC` aplica AGC/NS y falsea la línea base. | `UNPROCESSED` si el equipo lo soporta; si no, `VOICE_RECOGNITION`. |
| A16 | Exclusión de optimización de batería. | Se pide una vez, tras explicarlo, con `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; si falla, la pantalla de ajustes. La app no va a Play Store, así que su restricción sobre ese permiso no aplica. |
| A17 | Corrección manual a FONDO. | El selector solo ofrece RONQUIDO / HABLA / TOS / OTRO; para quitar algo, "Borrar". |
| A18 | Categoría del episodio tras corregir. | Se reagrupan los episodios de la sesión tras cada corrección o borrado. |

Decisiones adicionales tomadas al implementar:

| # | Problema encontrado | Decisión |
|---|---|---|
| A19 | Si la línea base se congela durante todo evento (§4.3), un ventilador que se enciende genera un evento continuo que se corta cada 60 s para siempre y la base nunca sube: justo la "avalancha" que se quiere evitar. | La base se congela durante un evento **salvo** si es la continuación de un corte por 60 s (nivel sostenido). Con +15 dB de ventilador hay unos 3 eventos (descartados como FONDO) y en ~2 min queda absorbido. Lo cubre el test `fanTurningOnIsAbsorbedWithoutAvalanche`. |
| A20 | Picos de la heurística de ronquido: con "máximos locales separados ≥ 1,5 s", un pulso cada 1 s parece periódico cada 2 s, y un ronquido cada 2 s podía fundirse con el siguiente. | Un pico por ráfaga (tramo continuo sobre mediana + 6 dB); ráfagas a < 1,5 s de la anterior se funden con ella en cadena. |
| A21 | Carrera entre la limpieza de sesiones huérfanas al abrir la app y una sesión que se crea en ese momento. | Solo se marcan INTERRUMPIDA las sesiones EN_CURSO creadas antes de que arrancara el proceso actual. |
| A22 | Algunos equipos entregan ceros sin avisar por `isClientSilenced` cuando otra app toma el micrófono. | 2 s seguidos de ceros digitales exactos también abren una pausa `MICROFONO_OCUPADO`. Una habitación real nunca da ceros exactos. |
| A23 | Una continuación tras el corte de 60 s que no vuelve a superar el umbral daría un evento de duración 0. | Se descarta. |
| A24 | Si YAMNet no carga (modelo dañado, ABI sin soporte), la noche se perdería. | La sesión sigue y guarda los eventos como OTRO / `Sin clasificar`; Inicio lo avisa. |
| A25 | `plan.md` §2 tenía cruzadas dos versiones (lifecycle 2.10.2 y navigation 2.11.0 no existen). | lifecycle **2.11.0**, navigation-compose **2.10.2** (verificadas en Google Maven). |
| A26 | Fin de un evento en la BD. | `startedAt`/`endedAt` son el inicio y fin reales (sin pre/post-roll); el WAV incluye los rolls. |
| A27 | Seguridad del manifiesto. | `tools:node="remove"` para `INTERNET` y `ACCESS_NETWORK_STATE`, por si una dependencia los colara en el manifiesto final. Además, `data_extraction_rules.xml` excluye todo de la copia en la nube y de la transferencia entre dispositivos en Android 12+ (`allowBackup="false"` cubre las versiones anteriores). |
| A28 | El plan pedía compileSdk 36 como "última estable", pero ya está publicada la API 37 y las bibliotecas de 2026 (Compose 1.12, core 1.19, lifecycle 2.11, navigation 2.10) se niegan a compilar contra 36. | **compileSdk 37**. **targetSdk se queda en 36**, como dice el plan: subirlo activaría cambios de comportamiento de Android 17 que no se han probado en un teléfono. |

## Checklist de validación manual (plan §8.3)

1. [ ] Diagnóstico → "Probar 10 s" hablando, tosiendo y con el ventilador encendido.
2. [ ] Sesión de 15 min con el ventilador encendido todo el tiempo → 0 eventos FONDO guardados.
3. [ ] Encender el ventilador a mitad de la sesión → la base sube y no hay una avalancha de eventos.
4. [ ] Durante la sesión, abrir la grabadora de voz o recibir una llamada → pausa registrada y reanudación automática.
5. [ ] Forzar la detención desde Ajustes del sistema → al reabrir, la sesión aparece INTERRUMPIDA con la hora del último latido.
6. [ ] Noche real completa de 8 h con "Guardar grabación completa": batería consumida, memoria estable (sin crecimiento) y comparación de los eventos detectados con la grabación.
7. [ ] Probar en al menos un Samsung o Xiaomi con la optimización de batería por defecto.

Criterios de aceptación: `plan.md` §10. El APK no declara `INTERNET` (comprobable con `aapt dump permissions` o el *APK Analyzer* de Android Studio).
