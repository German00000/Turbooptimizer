# TurboOptimizer

Herramienta de optimización para Android 13–16 (ARM64), pensada para gama baja y media.
**Sin root, sin exploits, sin Internet.** Usa APIs públicas de Android y, de forma opcional,
[Shizuku](https://shizuku.rikka.app/) para un conjunto cerrado de comandos seguros.

- Kotlin + Jetpack Compose (Material 3, modo oscuro, animaciones)
- `minSdk 33` (Android 13) · `targetSdk 36` (Android 16) · edge-to-edge
- Modo limitado completo cuando Shizuku no está disponible

---

## Funciones

| Sección | Qué hace | Sin Shizuku | Con Shizuku |
|---|---|---|---|
| **Panel principal** | RAM, almacenamiento, batería, temperatura, CPU, GPU, red, IP local (oculta por defecto), modelo, SoC, capa del fabricante (One UI, HyperOS, ColorOS…), pantalla, gráfico en tiempo real | ✔ (CPU% y frecuencias según el dispositivo) | ✔ + uso de CPU, frecuencias, carga GPU, temperatura CPU/GPU |
| **Optimizar ahora** | 3 perfiles (Equilibrado, Rendimiento, Extremo con advertencia). Antes de aplicar se muestra cada acción, su explicación y el comando exacto; cada una se puede desmarcar | `killBackgroundProcesses` de tu lista negra | Detener apps, cerrar procesos en 2º plano, restringir 2º plano, desactivar temporalmente, Game Mode, animaciones, `trim-caches` |
| **Limpiador** | Caché, temporales, miniaturas, descargas antiguas, residuos de apps desinstaladas, archivos grandes, APK, carpetas vacías, ocultos, duplicados de fotos/vídeos con vista previa | Caché propia + duplicados (permiso de fotos/vídeos). Con «Acceso a todos los archivos», análisis completo | + liberar caché de todas las apps |
| **Aplicaciones** | Lista con icono, tamaño, RAM, último uso, sistema/usuario; abrir, detener, desinstalar (solo apps de usuario), selección múltiple | ✔ | + RAM por app, detención real |
| **Game Booster** | Elegir juego, perfil automático al iniciar, overlay flotante (FPS, temp., RAM, CPU, batería, tiempo) ocultable, detección automática, restauración al terminar | ✔ (sin FPS) | + FPS (SurfaceFlinger), Game Mode |
| **RAM Boost** | Solo detiene apps de tu lista negra, nunca el juego ni servicios del sistema, intervalo ≥ 5 min y solo si la RAM libre baja del umbral. Muestra antes/después y explica cómo gestiona Android la RAM | ✔ (limitado) | ✔ |
| **Grabador** | MediaProjection oficial, audio interno (AudioPlaybackCapture) y/o micrófono mezclados, 720p30 / 720p60 (si el códec y la pantalla lo admiten) / 1080p30 / personalizado, bitrate, pausa/reanudar, carpeta seleccionable, comprobación de temperatura, batería y espacio | ✔ | ✔ |
| **Información del dispositivo** | SoC, clústeres y frecuencias, ABI, RAM/ZRAM, almacenamiento, pantalla (Hz, HDR, DPI), Android/API/Build/kernel, batería (ciclos en Android 14+), Treble, A/B, Virtual A/B, particiones dinámicas, sensores, cámaras, Wi-Fi (estándares/bandas), Bluetooth, NFC, USB, Vulkan/OpenGL ES, características de hardware | ✔ | + temperaturas HAL |
| **Seguridad y registro** | Lista blanca, lista negra, registro de operaciones con **deshacer**, historial de limpieza, modo simulación, permisos, lista de comandos permitidos | ✔ | ✔ |

### Lo que la app NO hace (por diseño)

- No usa root ni exploits, no modifica particiones ni archivos del sistema, no hace overclock.
- No promete más FPS: libera recursos y mide; los resultados dependen del dispositivo.
- No limpia RAM en bucle ni mata procesos críticos.
- No borra automáticamente fotos, vídeos, documentos ni partidas guardadas: las fotos/vídeos van a la
  **papelera del sistema** (recuperables 30 días) con confirmación de Android.
- No toca `/Android/data`, `/Android/obb` ni carpetas estándar (DCIM, Download, …).
- No recopila datos personales ni tiene permiso de Internet (se elimina del manifiesto con `tools:node="remove"`).

---

## Compilar e instalar

Requisitos: **Android Studio Narwhal (2025.1) o superior**, JDK 17 (incluido en Android Studio), SDK de Android 36.

1. Abre la carpeta `TurboOptimizer` en Android Studio (*File → Open*). Android Studio descargará
   Gradle 8.14.3 según `gradle/wrapper/gradle-wrapper.properties`.
   - Si prefieres la terminal y tienes Gradle instalado, genera el wrapper una vez: `gradle wrapper`
     y después usa `./gradlew`.
2. Compila e instala:
   ```bash
   ./gradlew assembleDebug           # app/build/outputs/apk/debug/app-debug.apk
   ./gradlew installDebug            # con el móvil conectado por ADB
   ./gradlew testDebugUnitTest       # tests de la política de comandos
   ```
3. `assembleRelease` genera un APK minificado firmado con la clave de depuración para poder
   instalarlo directamente. Para distribuirlo, configura tu propia keystore en `app/build.gradle.kts`.

> Nota: la app usa `QUERY_ALL_PACKAGES`, `PACKAGE_USAGE_STATS` y `MANAGE_EXTERNAL_STORAGE`, que
> Google Play restringe. Está pensada para instalación directa (sideload) o tiendas alternativas.

---

## Configurar Shizuku (sin root)

Shizuku permite que una app ejecute operaciones con la identidad de **ADB (shell)**, la misma que
tienes al conectar el móvil al PC. No es root: Android sigue aplicando sus restricciones.

### 1. Instalar Shizuku
Descárgalo desde [Google Play](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api)
o desde [GitHub](https://github.com/RikkaApps/Shizuku/releases).

### 2. Iniciarlo con depuración inalámbrica (Android 11+, sin PC)
1. *Ajustes → Acerca del teléfono → Información de software* → toca 7 veces **Número de compilación**
   para activar las Opciones de desarrollador.
2. *Opciones de desarrollador* → activa **Depuración inalámbrica**.
3. Abre Shizuku → **Emparejamiento** → sigue las instrucciones (aparece una notificación donde
   escribes el código de emparejamiento que muestra *Depuración inalámbrica → Vincular dispositivo con código*).
4. En Shizuku pulsa **Iniciar**. Debe mostrar «Shizuku se está ejecutando».

> En Samsung (One UI) y Xiaomi (HyperOS/MIUI) puede que tengas que desactivar la optimización de
> batería de Shizuku. En Xiaomi activa también «Depuración USB (ajustes de seguridad)».
> Tras reiniciar el móvil hay que volver a iniciar Shizuku (paso 4).

### Alternativa: con PC
```bash
adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
```

### 3. Conceder el permiso a TurboOptimizer
Abre TurboOptimizer: el panel muestra el estado de Shizuku.

| Indicador | Significado | Acción |
|---|---|---|
| 🟢 Shizuku funcionando | Listo, funciones avanzadas activas | — |
| 🟡 Shizuku instalado pero no iniciado | La app está instalada pero el servicio no corre | Pulsa «Abrir Shizuku» e inícialo |
| 🟡 Falta conceder permiso | El servicio corre pero TurboOptimizer no tiene permiso | Pulsa «Conceder permiso» |
| 🔴 Shizuku no disponible | No instalado o versión < 11 | La app funciona en modo limitado |

---

## Comandos que puede ejecutar con Shizuku

Lista cerrada definida en `core/shizuku/CommandPolicy.kt`. Cada comando se ejecuta como lista de
argumentos con `ProcessBuilder` (sin `sh -c`: no hay tuberías, redirecciones ni encadenamiento) y se
valida **dos veces**: en la app y dentro del proceso de Shizuku (`ShellUserService`).

| Comando | Uso | Reversible |
|---|---|---|
| `am force-stop <app>` | Detener una app de tu lista negra | Se reabre al usarla |
| `am kill <app>` | Cerrar sus procesos en segundo plano | — |
| `am kill-all` | Cerrar procesos en segundo plano (modo extremo) | — |
| `pm disable-user --user N <app>` | Desactivar temporalmente (desmarcado por defecto) | ✔ `pm enable` |
| `pm enable --user N <app>` | Reactivar | — |
| `pm trim-caches 999G` | Pedir a Android que libere caché de apps | — |
| `cmd appops get/set <app> RUN_ANY_IN_BACKGROUND ignore` | Restringir segundo plano | ✔ valor anterior |
| `cmd game mode performance <app>` | Game Mode del juego | ✔ `standard` |
| `settings get/put global *_animation_scale` | Escala de animaciones (0, 0.5, 1, 1.5, 2) | ✔ valor anterior |
| `dumpsys meminfo -c` · `dumpsys thermalservice` | Lectura de RAM por app y temperaturas | solo lectura |
| `cat` de `/proc/stat`, cpufreq, carga GPU, zonas térmicas | Lectura de métricas | solo lectura |
| `service call SurfaceFlinger 1013` | Contador de fotogramas para FPS | solo lectura |

Siempre están protegidos (no se pueden detener ni desactivar): componentes críticos de Android y
Google Play Services, launcher, teclado, apps de teléfono/SMS predeterminadas, servicios de
accesibilidad activos, fondo animado, Shizuku, TurboOptimizer, procesos persistentes, tu lista blanca
y el juego en curso.

---

## Permisos

Todos opcionales; sin ellos la función correspondiente queda en modo limitado.

| Permiso | Para qué |
|---|---|
| Notificaciones | Servicios en primer plano (Game Booster, grabador) |
| Acceso de uso | Último uso, tamaño de apps, detección automática de juegos, caché de otras apps |
| Mostrar sobre otras apps | Overlay de estadísticas |
| Acceso a todos los archivos | Limpiador completo |
| Fotos y vídeos | Duplicados |
| Micrófono | Audio interno y micrófono en grabaciones |

---

## Arquitectura

```
app/src/main/java/com/turbooptimizer/
├── TurboApp.kt / AppContainer.kt   Inyección de dependencias manual
├── MainActivity.kt
├── core/
│   ├── shizuku/   ShizukuManager (estado, permiso, UserService), CommandPolicy (lista blanca),
│   │              ShellUserService (proceso shell), ShellCommands, ShellResult
│   └── safety/    SafetyGuard (protección dinámica de apps)
├── data/          SettingsRepository (DataStore), OperationLog (registro + UndoAction)
├── monitor/       SystemMonitor (métricas en tiempo real), FpsMonitor
├── device/        DeviceInfoProvider
├── optimizer/     OptimizationEngine (plan → ejecución), UndoManager, RamBoostManager
├── apps/          AppRepository
├── cleaner/       StorageScanner, DuplicateFinder, CleanerExecutor, PathSafety
├── game/          GameBoostController, GameSessionService (FGS specialUse), OverlayController
├── recorder/      RecorderEngine (MediaCodec + MediaMuxer), ScreenRecorderService (FGS mediaProjection)
├── util/          Formato, permisos, lectura de /proc y getprop, canales de notificación
└── ui/            theme, components, navigation, screens (una pantalla + ViewModel por archivo)
```

Flujo de cualquier operación: **plan** (qué se hará, con comando visible) → **confirmación** →
revalidación de seguridad justo antes de actuar → ejecución (o simulación) → **registro** con
acciones de deshacer.

### Compatibilidad Android 13–16
- Servicios en primer plano con tipo (`specialUse`, `mediaProjection`, `microphone`) — Android 14+.
- El servicio de grabación pasa a primer plano **antes** de `getMediaProjection()` y registra el
  callback antes de crear el `VirtualDisplay` — requisito de Android 14+.
- Edge-to-edge (`enableEdgeToEdge`) — obligatorio desde Android 15.
- Predictive back (`enableOnBackInvokedCallback`).
- Acceso parcial a fotos (`READ_MEDIA_VISUAL_USER_SELECTED`) — Android 14+.
- Medidor de FPS: mismo código en Android 13–16. Se verificó la lógica con tests unitarios; en
  Android 15 y 16 el resultado depende del fabricante (ver «Medidor de FPS»).

### Medidor de FPS (Game Booster)
- **Actualización**: cada 500 ms (si una lectura tarda más, la siguiente espera solo lo necesario).
- **Valores**: FPS actual (media móvil suave), mínimo, máximo y promedio de la sesión. Mín/máx/promedio
  usan los valores sin suavizar y se reinician al iniciar una sesión.
- **Estado de medición**: se muestra en la tarjeta de la sesión y en el overlay («SurfaceFlinger»,
  «contador global», «sin lectura», «no disponible»…).
- **Overlay**: se puede mover arrastrándolo, ocultar con «×» y cambiar el tamaño arrastrando el tirador «⇲».

**Método utilizado** (todo vía Shizuku, solo lectura, sin root):
1. **Principal — marcas de presentación de SurfaceFlinger.** `dumpsys SurfaceFlinger --list` localiza
   las capas del paquete en primer plano y `dumpsys SurfaceFlinger --latency <capa>` devuelve el instante
   real en que el sistema presentó cada fotograma. Los FPS son fotogramas presentados en la última ventana
   de 2 s. Se usa la capa que presentó más recientemente.
2. **Alternativo — contador global.** `service call SurfaceFlinger 1013` devuelve el número total de
   composiciones; los FPS son la diferencia entre muestras. Mezcla todas las capas, así que es aproximado.
3. Si ninguno da datos válidos se muestra «N/D». Nunca se estima un valor.

Limitaciones: mide fotogramas presentados por el sistema, no los que dibuja el juego. Si la pantalla no
cambia, no se presentan fotogramas y el valor baja a 0. Los FPS no pueden superar la frecuencia de la
pantalla; las muestras que lo hacen se descartan.

### Limitaciones conocidas
- **FPS**: depende de que el fabricante permita `dumpsys SurfaceFlinger` (o `service call SurfaceFlinger 1013`);
  si no, se muestra «N/D».
- **Temperatura de CPU/GPU**: requiere Shizuku y que la HAL térmica la exponga; sin ella se usa la de la batería.
- **Uso de CPU sin Shizuku**: Android 8+ bloquea `/proc/stat` a las apps.
- **Audio interno**: las apps que desactivan la captura (`allowAudioPlaybackCapture=false`) se graban en silencio.
- **Caché de otras apps**: desde Android 11 solo el sistema puede borrarla (se ofrece `pm trim-caches` con Shizuku).
- Los textos de la UI están en el código (español); para traducirla habría que moverlos a `strings.xml`.
