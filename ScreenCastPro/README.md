# ScreenCast Pro

Aplicación Android (Kotlin, Android 5.0 → 16) que transmite la pantalla del teléfono a la red local:
Chromecast / Google TV / Android TV mediante Google Cast, televisores DLNA/UPnP y cualquier reproductor
que abra una URL HTTP (VLC, etc.).

> **Estado.** El código Android está escrito y revisado, pero **no se ha compilado ni probado en un
> dispositivo real**. En este entorno no hay Android SDK (el dominio `dl.google.com` está bloqueado),
> así que el APK no se ha generado aquí. La lógica pura (muxer MPEG-TS, HLS, control adaptativo,
> SOAP/UPnP, servidor HTTP) sí tiene **31 tests JVM que pasan** (ver [Tests](#tests)).

Antes de escribir código se analizaron las diferencias entre versiones de Android que afectan a
MediaProjection, audio, Wi-Fi, VPN y servicios en primer plano:
**[docs/COMPATIBILIDAD_ANDROID.md](docs/COMPATIBILIDAD_ANDROID.md)**.

---

## Funciones

| Área | Implementación |
|---|---|
| Captura | `MediaProjection` + `VirtualDisplay` con superficie del codificador. Consentimiento en cada sesión. |
| Codificación | `MediaCodec` H.264 (Baseline, CBR, con entrada de superficie). Prefiere codificador por hardware. |
| Resoluciones | 480p, 720p, 1080p (lado corto). Se reduce automáticamente si el codificador no admite el tamaño. |
| Relación de aspecto | Se calcula desde el tamaño real de la pantalla; dimensiones pares, sin deformación. Rotación gestionada. |
| FPS | 24, 30, 60. Se elige el primero que admita el codificador. |
| Bitrate | Automático (según píxeles × FPS), bajo / medio / alto, y control adaptativo en tiempo real. |
| Calidad adaptativa | Baja bitrate ante clientes lentos o escrituras lentas; baja de escalón si ya está en el mínimo. Recupera calidad al estabilizarse. |
| Temperatura | Android 10+: `PowerManager.getCurrentThermalStatus()`. Android 5–9: temperatura de batería. Reduce calidad al calentarse. |
| Baja latencia | Keyframe cada 1 s, perfil Baseline (sin B-frames), `KEY_LATENCY` en Android 11+. |
| Ahorro de batería | Máximo 720p/30, bitrate ×0.6, keyframe cada 2 s. Sin espectadores durante 20 s, el codificador se pausa. |
| Audio interno | `AudioPlaybackCapture` + AAC-LC en Android 10+. En Android 5–9: solo vídeo (ver limitaciones). |
| Salida | MPEG-TS continuo (`/live.ts`) y HLS en vivo (`/live.m3u8`, segmentos de ~1 s). |
| Descubrimiento | NSD `_googlecast._tcp` (Cast) + SSDP `MediaRenderer` (DLNA). Fusionado por IP. |
| Conexión Cast | Google Cast SDK con el receptor oficial *Default Media Receiver* (`CC1AD845`), cargando la URL HLS. |
| Conexión DLNA | SOAP `SetAVTransportURI` + `Play` sobre AVTransport. Vigilancia de estado cada 10 s. |
| Reconexión | Hasta 5 intentos con 3 s de espera; DLNA se recarga si el televisor se para. Configurable. |
| Seguridad de acceso | Cada sesión genera un token aleatorio que forma parte de la URL; sin él, el servidor responde 404. |

### Qué funciona en cada destino

| Destino | Protocolo | Estado | Latencia aproximada |
|---|---|---|---|
| Chromecast / Google TV | Google Cast (HLS) | Implementado; requiere Google Play services | 3–6 s estimados (segmentado) |
| Android TV sin Cast | DLNA si expone AVTransport; si no, URL HTTP en un reproductor | Depende del televisor | 1–2 s estimados en VLC |
| Televisor DLNA/UPnP | SOAP AVTransport con `video/mp2t` | Depende del televisor (muchos prefieren HLS) | 1–3 s |
| Reproductor HTTP (VLC, MX Player…) | `http://IP:8765/TOKEN/live.ts` | Implementado | 1–2 s |
| Miracast | Solo ajustes del sistema | **No es posible desde una app de terceros** (ver abajo) | — |
| RTSP | No implementado | — | — |

---

## Arquitectura

```
 MainActivity ──► ScreenCaptureService (primer plano, tipo mediaProjection)
                    │
                    ├─ MediaProjection ─► VirtualDisplay ─► VideoEncoder (MediaCodec H.264)
                    │                                          │ Annex B + SPS/PPS en keyframes
                    ├─ AudioCapture (Android 10+) ─► AAC+ADTS ─┤
                    │                                          ▼
                    │                                     TsMuxer (PAT/PMT/PES/PCR)
                    │                                          │
                    │                                          ▼
                    │                                     StreamHub ──► colas por cliente (resync por keyframe)
                    │                                       │   └──► HlsSegmenter (segmentos de ~1 s)
                    │                                       ▼
                    │                                     StreamServer (HTTP :8765, token en la ruta)
                    │
                    └─ AdaptiveController (cada 2 s): congestión + temperatura ─► bitrate / escalón

 DeviceDiscovery ─┬─ NsdManager `_googlecast._tcp` ─┐
                  └─ SsdpDiscovery (M-SEARCH)       ├─► lista fusionada por IP
                                                   │
 TvConnection ◄────────────────────────────────────┘
     ├─ CastConnector  → MediaRouter → CastSession → RemoteMediaClient.load(HLS)
     └─ DlnaConnector  → SOAP SetAVTransportURI / Play / GetTransportInfo
```

Principios aplicados:

- **Orden de arranque de Android 14+:** `startForeground(tipo mediaProjection)` → `getMediaProjection` →
  `registerCallback` → `createVirtualDisplay`.
- **Sin fotogramas huérfanos:** cada cliente se sincroniza en un keyframe. Si su cola se llena, se vacía y
  espera al siguiente keyframe; el codificador nunca se bloquea por un televisor lento.
- **Sin APIs de Java 8 que requieran API 24:** no se usan `Map.merge`, `getOrDefault` ni similares, porque
  minSdk es 21.
- **Liberación de recursos:** `VirtualDisplay`, `MediaCodec`, `AudioRecord`, `ServerSocket`, sockets y
  hilos se liberan en `stopCapture()`. Los hilos son `daemon` y tienen `join` con tiempo límite.

### Estructura

```
ScreenCastPro/
├── docs/COMPATIBILIDAD_ANDROID.md        Análisis por versión de Android (previo al código)
├── app/
│   ├── build.gradle.kts                  minSdk 21 · targetSdk 36 · firma release opcional
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── res/                          layouts, strings, tema Material 3, network_security_config
│       └── java/com/screencastpro/
│           ├── MainActivity.kt           pantalla principal y ajustes
│           ├── DeviceAdapter.kt          lista «Dispositivos encontrados»
│           ├── AppSettings.kt            preferencias locales
│           ├── SessionState.kt           estado observable de la transmisión
│           ├── core/                     lógica pura (sin Android; probada en JVM)
│           │   ├── TsMuxer.kt            MPEG-TS: PAT/PMT, PES, PCR, ADTS, CRC-32
│           │   ├── HlsSegmenter.kt       segmentos HLS alineados a keyframes
│           │   ├── StreamHub.kt          fan-out, colas acotadas, congestión
│           │   ├── StreamServer.kt       HTTP con token: /live.ts, /live.m3u8, /seg, /status
│           │   ├── AdaptiveController.kt control de bitrate/escalón
│           │   ├── StreamProfile.kt      resoluciones, escalera de calidad, bitrate, latencia
│           │   └── Upnp.kt               SSDP, descripción UPnP, SOAP AVTransport
│           ├── capture/
│           │   ├── ScreenCaptureService.kt
│           │   ├── VideoEncoder.kt
│           │   ├── AudioCapture.kt       AudioPlaybackCapture + AAC (Android 10+)
│           │   ├── Capabilities.kt       MediaCodecList: hardware, tamaño y FPS admitidos
│           │   └── Thermal.kt
│           ├── discovery/
│           │   ├── DeviceDiscovery.kt    NSD + SSDP, cola de resolución
│           │   ├── SsdpDiscovery.kt
│           │   └── TvDevice.kt
│           ├── connect/
│           │   ├── TvConnection.kt       orquestación, reintentos, vigilancia DLNA
│           │   ├── CastConnector.kt
│           │   ├── CastOptionsProvider.kt
│           │   └── DlnaConnector.kt
│           └── net/                      Lan (IP local), Http (HttpURLConnection + SOAP)
├── core-tests/                           tests JVM de la lógica pura (compila los mismos fuentes)
├── gradle/libs.versions.toml             versiones de dependencias
├── keystore.properties.example           plantilla de firma release
└── gradlew, gradlew.bat                  wrapper de Gradle 8.14.3
```

---

## Compilar el APK

### Requisitos

- Android Studio Narwhal (2025.1) o superior, o JDK 17+ y Android SDK con la plataforma **36**.
- Dispositivo Android 5.0 o superior con depuración USB, o emulador.
- Google Play services en el teléfono solo si se quiere usar Chromecast.

### Opción A: Android Studio

1. *File → Open* → selecciona la carpeta `ScreenCastPro`.
2. Espera a la sincronización de Gradle.
3. *Build → Build Bundle(s) / APK(s) → Build APK(s)*. Resultado:
   `app/build/outputs/apk/debug/app-debug.apk`.
4. *Run ▶* para instalar y ejecutar en el dispositivo conectado.

### Opción B: línea de comandos

```bash
cd ScreenCastPro
./gradlew assembleDebug                      # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug                       # con el móvil conectado (adb devices)
adb shell am start -n com.screencastpro/.MainActivity
```

En Windows usa `gradlew.bat` en lugar de `./gradlew`.

---

## Generar APK release firmado

### 1. Crear la clave (una sola vez)

```bash
keytool -genkeypair -v \
  -keystore screencastpro-release.jks \
  -alias screencastpro \
  -keyalg RSA -keysize 4096 -validity 10000
```

Guarda el archivo `.jks` **fuera del repositorio** y haz copia de seguridad. Si lo pierdes, no podrás
publicar actualizaciones con la misma identidad.

### 2. Configurar la firma

```bash
cp keystore.properties.example keystore.properties
```

Edita `keystore.properties`. `storeFile` es relativo a la carpeta `ScreenCastPro`:

```properties
storeFile=../screencastpro-release.jks
storePassword=TU_CONTRASEÑA
keyAlias=screencastpro
keyPassword=TU_CONTRASEÑA
```

`keystore.properties` y `*.jks` están en `.gitignore`.

### 3. Compilar y verificar

```bash
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk  (minificado y reducido)

apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

`apksigner` viene con el Build-Tools del SDK (`$ANDROID_HOME/build-tools/<versión>/apksigner`).
Si no existe `keystore.properties`, el APK release se firma con la clave *debug* para poder probarlo; no lo
distribuyas así.

### 4. Para un bundle de Google Play (opcional)

```bash
./gradlew bundleRelease     # app/build/outputs/bundle/release/app-release.aab
```

---

## Uso

1. Conecta el teléfono y la TV a la **misma red Wi-Fi** (sin «aislamiento de clientes» en el router).
2. Abre la app y ajusta resolución, FPS, bitrate y modos.
3. Pulsa **Transmitir pantalla** y acepta el aviso de captura de Android.
4. Espera a que aparezca el televisor en **Dispositivos encontrados** y pulsa **Conectar**.
5. Para detener: **Detener transmisión** (o el botón *Detener* de la notificación).

Si el televisor no aparece:

- Comprueba que estén en la misma subred (`192.168.x.x`).
- Pulsa **Buscar de nuevo**.
- Para reproductores HTTP (VLC): copia la URL con **Copiar URL para reproductor HTTP** y ábrela en
  «Abrir red»/«Abrir URL».

### Ajustes

| Ajuste | Efecto |
|---|---|
| Resolución | Lado corto objetivo: 480p, 720p o 1080p. Si el codificador no lo admite, baja automáticamente. |
| FPS | 24, 30 o 60. Es el objetivo del codificador y del presupuesto de bitrate. |
| Bitrate | Automático, bajo (×0.5), medio (×0.75) o alto (×1.25) sobre el valor por defecto. |
| Baja latencia | Keyframe cada 1 s y `KEY_LATENCY` (Android 11+). Desactivado: keyframe cada 2 s. |
| Ahorro de batería | Máx. 720p/30, bitrate ×0.6. |
| Reconexión automática | Reintenta Cast/DLNA si la sesión se corta. |
| Audio interno | Solo Android 10+. Pide el permiso de micrófono porque Android lo exige para AudioPlaybackCapture; no graba el micrófono. |

---

## Tests

La lógica pura se prueba en JVM sin Android SDK, compilando directamente los fuentes de
`app/src/main/java/com/screencastpro/core`:

```bash
cd core-tests
gradle test          # o ../gradlew desde una copia del wrapper; requiere JDK 17+
```

Resultado en el momento de la entrega: **31 tests, 0 fallos**. Cubren:

- **Muxer MPEG-TS:** CRC-32 de PAT/PMT (residuo 0), PAT/PMT antes de cada keyframe, reensamblado de PES,
  decodificación de PTS, PCR = PTS, continuidad de contadores por PID, cabecera ADTS, división de keyframes
  grandes.
- **HLS:** segmentos que empiezan en keyframes, duración objetivo, descarte de segmentos antiguos.
- **Control adaptativo:** congestión baja bitrate antes que resolución, calor crítico va al mínimo, recuperación
  sin superar el escalón solicitado.
- **Perfiles:** aspecto conservado (1080×2400 → 480 px → 480×1066), sin ampliar, dimensiones pares, escalera
  de calidad, bitrate acotado.
- **Hub:** cliente lento se resincroniza en el siguiente keyframe; cliente nuevo pide keyframe.
- **UPnP:** cabeceras SSDP, descripción del dispositivo con URL de control resuelta, escape XML del DIDL.
- **Servidor HTTP real (sockets):** token incorrecto → 404; `/status` → 200; `/live.ts` envía datos
  desde un keyframe; `/live.m3u8` devuelve la lista.

### Lo que **no** está probado aquí

- Compilación del módulo Android (no hay SDK en el entorno de desarrollo).
- Captura real, codificadores de cada fabricante, audio interno, Cast y DLNA con televisores reales.
- Comportamiento en 2, 3 y 4 GB de RAM, consumo de batería y temperatura reales.

Antes de distribuir, prueba en al menos: un Android 5–6 con 2 GB, un Android 10 con audio interno, un
Android 14 y un Android 16, con un Chromecast y una TV DLNA.

---

## Limitaciones honestas

1. **Miracast:** Android no ofrece a apps de terceros una API pública para iniciar una sesión Miracast como
   emisor. La app abre los **ajustes de transmisión del sistema** (`Settings.ACTION_CAST_SETTINGS`).
2. **Chromecast con latencia:** HLS trabaja por segmentos, así que la latencia es de varios segundos.
   Para menos latencia, usa un reproductor HTTP (`live.ts`) en un dispositivo que lo soporte.
3. **FPS:** `VirtualDisplay` entrega fotogramas cuando cambia el contenido. El FPS configurado es el objetivo
   del codificador y del control de bitrate, pero **no es un límite duro**; eso requiere una etapa OpenGL
   intermedia, que no está incluida.
4. **Pantalla estática:** si la pantalla no cambia, no hay fotogramas nuevos. Un reproductor HLS puede mostrar
   el último segmento o detectar un corte; es un comportamiento normal de la captura por superficie.
5. **Sincronía audio/vídeo:** aproximada. Audio y vídeo usan líneas temporales independientes; puede haber
   decenas de milisegundos de desfase.
6. **Android 5–9 sin audio interno:** no hay API oficial para capturar el audio de otras apps en esas
   versiones. Se transmite solo vídeo; no se usa el micrófono como sustituto.
7. **Audio protegido o de apps que lo bloquean:** no se captura (contenido DRM, llamadas, apps con
   `allowAudioPlaybackCapture=false`).
8. **Redes aisladas:** si el router aísla los clientes Wi-Fi o el teléfono y la TV están en redes distintas,
   no hay conexión local posible. La app no intenta saltarse ese aislamiento.
9. **Sin RTSP:** no se implementa un servidor RTSP.
10. **VPN:** **no se implementa.** `VpnService` enruta tráfico por una interfaz virtual y no mejora la
    conexión dentro de la LAN; además podría cortar el acceso a la subred de la TV. Si se añade en el futuro,
    usaría solo `android.net.VpnService` con rutas explícitas y consentimiento visible.
11. **Acceso de red local en Android 16:** Google ha ido endureciendo el acceso a la red local en versiones
    recientes. **Antes de publicar, verifica en developer.android.com si el targetSdk 36 requiere un permiso
    adicional de red local.**
12. **Versiones de dependencias:** están fijadas en `gradle/libs.versions.toml`, pero no pude verificarlas
    contra Google Maven desde el entorno de desarrollo. Compila una vez y actualiza a la última estable si
    Gradle avisa de un artefacto inexistente.

---

## Seguridad y privacidad

- **Sin root, sin exploits, sin modificar el sistema, sin instalar certificados.**
- **Sin servidores externos:** la transmisión va solo por la red local. No se envía nada a Internet.
- **Token por sesión:** la URL incluye un identificador aleatorio de 128 bits. Otro dispositivo de la misma
  Wi-Fi no puede ver la pantalla sin esa URL. Al detener la transmisión, el token deja de servir.
- **Sin recopilación de datos:** no hay analítica, ni cuentas, ni identificadores publicitarios.
- **Permisos mínimos:** red local, estado de red, multicast Wi-Fi (descubrimiento), servicio en primer plano,
  notificación (Android 13+) y micrófono **solo** para audio interno (Android 10+, activable). No se piden
  ubicación, contactos, almacenamiento ni VPN.
- **Tráfico HTTP en claro en la LAN:** los televisores hablan HTTP sin TLS. La configuración de red permite
  tráfico en claro **solo** para hacer esas llamadas locales.
- **Permiso de Internet:** el manifiesto incluye `INTERNET` porque Android lo exige para abrir sockets
  locales; la app no usa Internet.
- **Consentimiento de captura:** Android muestra el aviso «Empezar a grabar o transmitir» en cada sesión.

---

## Versiones de Android

Resumen (detalle en [docs/COMPATIBILIDAD_ANDROID.md](docs/COMPATIBILIDAD_ANDROID.md)):

| Android | Qué cambia para la app |
|---|---|
| 5.0–7.1 | Sin audio interno; sin servicio en primer plano con tipo; MediaCodec de calidad variable. |
| 8.x | `startForegroundService` y canal de notificación obligatorios. |
| 9 | Cleartext bloqueado por defecto → `network_security_config`. |
| 10 | `AudioPlaybackCapture` disponible; API térmica (`getCurrentThermalStatus`). |
| 11–12 | `KEY_LATENCY` en codificadores; aviso de captura en Android 12. |
| 13 | `POST_NOTIFICATIONS` en tiempo de ejecución. |
| 14 | `FOREGROUND_SERVICE_MEDIA_PROJECTION` y tipo `mediaProjection`; `registerCallback` antes de `createVirtualDisplay`. |
| 15 | Restricciones de servicios en primer plano al arrancar desde segundo plano; el consentimiento de captura se pide en cada sesión. |
| 16 | targetSdk 36: verificar antes de publicar si se exige un permiso adicional de red local (ver limitaciones). |

Los niveles de API de cada función están en los comentarios del código y en la documentación.
