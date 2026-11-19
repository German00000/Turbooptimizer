# ScreenCast Pro: diferencias entre versiones de Android

Análisis previo al código. Cada decisión de diseño del proyecto se apoya en una de estas diferencias.
Los números de API son los de `Build.VERSION_CODES`. Antes de publicar, verifica cada punto contra la
documentación oficial en developer.android.com, porque el comportamiento de algunas versiones recientes
cambia entre actualizaciones.

## 1. Tabla resumen

| Tema | Android 5.0–7.1 (21–25) | Android 8.x (26–27) | Android 9 (28) | Android 10 (29) | Android 11–12 (30–31) | Android 13 (33) | Android 14 (34) | Android 15 (35) | Android 16 (36) |
|---|---|---|---|---|---|---|---|---|---|
| **MediaProjection** (`createScreenCaptureIntent`, `createVirtualDisplay`) | Disponible. El usuario acepta cada sesión. | Igual | Igual | Igual | Igual. Android 12 muestra un indicador de captura en la barra de estado. | Igual. Notificación de servicio en primer plano requerida. | **Servicio en primer plano de tipo `mediaProjection` obligatorio** tras el consentimiento. Hay que registrar `MediaProjection.Callback` antes de `createVirtualDisplay`. | Mismas reglas que 14. El consentimiento se pide en cada sesión; no se reutiliza el resultado. Verificar en la documentación los cambios de captura de esta versión. | Sin cambios específicos conocidos para esta app; verificar la documentación del target 36 antes de publicar. |
| **Servicio en primer plano** | No existe `startForegroundService`; `startService` basta. | `startForegroundService` y canal de notificación obligatorio (si no, la app cae). | Igual | Tipo de servicio opcional. | Igual | `POST_NOTIFICATIONS` en tiempo de ejecución; sin permiso la notificación no se ve (el servicio sigue). | `FOREGROUND_SERVICE_MEDIA_PROJECTION` obligatorio + `foregroundServiceType="mediaProjection"` en el manifiesto. | Restricciones al iniciar servicios de tipo mediaProjection desde `BOOT_COMPLETED` (no usado aquí). | Restricciones de inicio desde segundo plano. La app solo inicia el servicio desde la pantalla. |
| **Captura de audio interna** (`AudioPlaybackCapture`) | **No disponible.** Solo micrófono. | No disponible. | No disponible. | Disponible con `MediaProjection` (`AudioPlaybackCaptureConfiguration`). Una app puede prohibirlo con `allowAudioPlaybackCapture=false`. | Disponible | Disponible; requiere `RECORD_AUDIO` | Disponible | Disponible | Disponible |
| **Wi-Fi / multicast** (NSD, SSDP) | `NsdManager` disponible; el multicast puede descartarse sin `MulticastLock`. | Igual | Igual | Igual | Igual | Igual. Sin permiso de ubicación para NSD. | Igual | Igual | Igual. Google ha ido restringiendo el acceso a la red local; **verificar** si el target 36 requiere un permiso adicional de red local antes de publicar. |
| **VPN** (`VpnService`) | Disponible con consentimiento. | Igual | Igual | Igual | Igual | Igual | Igual | Igual | Igual |
| **Cleartext HTTP** (para hablar con TVs por HTTP) | Permitido | Permitido | **Bloqueado por defecto para clientes** → requiere `network_security_config` | Igual | Igual | Igual | Igual | Igual | Igual |
| **Térmica** (`PowerManager.getCurrentThermalStatus`) | No existe; usar temperatura de batería. | No existe | No existe | Disponible (API 29) | Igual | Igual | Igual | Igual | Igual |
| **Codificador H.264 con entrada de superficie** | Disponible (`createInputSurface`, API 18). Calidad de HW muy variable en gama baja. | Igual (códecs OMX). | Igual | Igual. `isSoftwareOnly` / `isHardwareAccelerated` disponibles desde API 29. | Igual | Igual | Igual | Igual | Igual |
| **Baja latencia en el codificador** (`KEY_LATENCY`) | No | No | No | No | Sí (API 30) | Sí | Sí | Sí | Sí |

## 2. Consecuencias para el diseño

### MediaProjection
- El consentimiento es **por sesión**. La app pide el permiso cada vez que el usuario pulsa «Transmitir pantalla»; no
  guarda el resultado.
- En Android 14+ el orden importa: primero `startForeground(..., FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)`,
  después `getMediaProjection()`, después `registerCallback()`, y solo entonces `createVirtualDisplay()`.
- `VirtualDisplay.setSurface(null)` detiene la producción de fotogramas sin destruir la proyección. Lo usamos para
  pausar el codificador cuando no hay ningún televisor pidiendo el flujo (ahorro de CPU y batería).
- `VirtualDisplay.resize()` y `setSurface()` existen desde API 20 y permiten adaptarse a rotaciones y a cambios de
  calidad sin recrear la proyección. Como la superficie del codificador tiene tamaño fijo, un cambio de
  resolución exige reiniciar el codificador (y el flujo H.264 se reanuda en el siguiente fotograma clave).

### Audio
- **Android 5–9:** no hay forma oficial de capturar el audio que reproduce otra app. El fallback es transmitir
  **solo vídeo** y mostrar la limitación en la interfaz. No se usa el micrófono como sustituto, porque captaría la
  habitación en lugar del audio de la pantalla.
- **Android 10+:** `AudioRecord` con `AudioPlaybackCaptureConfiguration` capta el audio de apps que lo permitan.
  No se captura audio de llamadas, de contenido protegido ni de apps que lo prohíban.
- Si `AudioRecord` no se inicializa (permiso denegado, dispositivo sin soporte), el flujo sigue **solo con vídeo**. El
  PMT del flujo MPEG-TS se construye al iniciar, así que la decisión de audio se toma antes de empezar.

### Wi-Fi y descubrimiento
- Se usan dos mecanismos oficiales: **NsdManager** (`_googlecast._tcp`, para Chromecast, Google TV y Android TV con
  Cast integrado) y **SSDP** sobre UDP (búsqueda `MediaRenderer` para televisores DLNA/UPnP).
- SSDP necesita `WifiManager.MulticastLock` para recibir multicast de forma fiable en muchos teléfonos. Las respuestas
  de SSDP llegan por unicast, pero el lock evita pérdidas.
- Ninguna de las APIs anteriores atraviesa el aislamiento de clientes del router (AP isolation) ni las redes de
  invitados. Si el teléfono y la TV están en redes aisladas, **no hay conexión local posible** y la app lo indica.

### VPN
- **No se implementa.** `VpnService` crea una interfaz `tun` y enruta tráfico a través de ella. Para un flujo LAN
  local no aporta nada y puede romper el enrutamiento hacia la subred de la TV. Si en el futuro se añade, solo
  se usaría `VpnService` con rutas explícitas y consentimiento visible del usuario. No se usará para saltarse
  restricciones de red escolar, empresarial o pública.

### Servicios en primer plano y procesos en segundo plano
- El servicio solo existe mientras hay una transmisión activa. Se detiene al pulsar «Detener transmisión», al
  revocarse la proyección (`MediaProjection.Callback.onStop`) o desde la notificación.
- No hay `BOOT_COMPLETED`, ni alarmas, ni trabajos periódicos.

### Térmica y batería
- Android 10+: `PowerManager.getCurrentThermalStatus()` devuelve el estado térmico (none → shutdown).
- Android 5–9: no hay API térmica pública; se usa la **temperatura de batería** del broadcast `ACTION_BATTERY_CHANGED`
  como aproximación.

### Cleartext y Android 9+
- Desde Android 9 las apps no pueden usar HTTP en claro salvo que la `network_security_config` lo permita. El
  cliente de SOAP (DLNA) y las comprobaciones de estado hablan HTTP con la TV, así que la configuración lo permite.
  El servidor propio no tiene este problema.

## 3. Qué no es posible en todas las plataformas

| Función | Situación real | Fallback implementado |
|---|---|---|
| Miracast desde una app de terceros | Android no expone una API pública para iniciar una sesión Miracast como emisor desde apps normales. El descubrimiento y la conexión los gestiona el sistema. | Botón «Abrir ajustes de transmisión» (`Settings.ACTION_CAST_SETTINGS`, API 19+) y la URL HTTP para reproductores compatibles. |
| Chromecast sin Cast SDK | El protocolo CASTV2 es propietario y requiere cifrado y protobuf. | Google Cast SDK (`play-services-cast-framework`) con el receptor oficial *Default Media Receiver* (`CC1AD845`), que reproduce HLS. Requiere Google Play services. |
| RTSP | No hay servidor RTSP en Android SDK. Implementarlo es posible pero no es necesario para los destinos soportados. | Servidor HTTP con MPEG-TS (`/live.ts`) y HLS (`/live.m3u8`). |
| Baja latencia en Chromecast | HLS es inherentemente por segmentos: la latencia práctica es de unos segundos. | Segmentos de ~1 s y lista corta. |
| FPS exactos | `VirtualDisplay` entrega fotogramas cuando cambia el contenido. El codificador acepta el FPS objetivo como guía, pero un límite duro requeriría una etapa OpenGL intermedia. | El FPS configurado controla el codificador y el presupuesto de bitrate; la limitación dura queda como mejora futura. |
| Pantalla estática | Sin cambios en pantalla no hay fotogramas nuevos; el flujo puede quedarse en el último fotograma. | Se documenta; los reproductores HLS muestran el último segmento. |

## 4. Matriz de compatibilidad de la app

| Requisito | Decisión |
|---|---|
| `minSdk` | 21 (Android 5.0) |
| `targetSdk` / `compileSdk` | 36 (Android 16) |
| Arquitecturas | Código 100 % Java/Kotlin sin nativo: funciona en ARM64 y ARM32. El codificador lo proporciona el dispositivo. |
| Permisos | Internet/LAN, estado de red, multicast Wi-Fi, servicio en primer plano, notificaciones (33+), micrófono solo para audio interno (10+). Sin ubicación, sin contactos, sin almacenamiento, sin VPN. |
