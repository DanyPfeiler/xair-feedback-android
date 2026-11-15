# XAir Feedback (Android)

Supresor automático de feedback para la consola Behringer XR12 / X AIR. Lee el analizador (RTA)
de la consola por Wi-Fi, detecta feedback y lo corta con el EQ de la consola.

## Descargar la app
El APK más reciente está en **Releases → ultima → XAirFeedback.apk**. Se genera solo con cada cambio.
Para instalarlo hay que permitir "instalar apps de orígenes desconocidos" en el teléfono (Android 8 o superior).

## Uso
1. Conectá el teléfono al Wi-Fi de la consola y poné su IP en "1. Conexión" → "Probar conexión".
2. Elegí qué analizar (canal del micrófono y/o Main LR) y dónde cortar.
3. Empezá en "Solo avisar". Cuando marque bien, pasá a "Cortar automáticamente".
4. Para verlo trabajar sin consola: tildá "Usar simulador", INICIAR y "Generar".

La app sigue trabajando con la pantalla apagada (notificación fija con botón Detener). En
Ajustes → Batería conviene excluirla de la optimización de batería.

Cuando se acaban las bandas del canal sigue cortando en Main LR y, como último recurso, baja el
volumen del micrófono. Al detener, el EQ y el volumen vuelven a como estaban.

## Estado
- El motor se prueba automáticamente contra un simulador en cada compilación.
- No está validado todavía contra una XR12 real: el formato del RTA (`/meters/4`, 100 bandas),
  los números de fuente del analizador y las escalas del EQ salen de documentación.

## Código
- `app/src/main/java/.../core`: motor sin dependencias de Android (OSC, detector, cortes, simulador).
- `FeedbackService`: servicio en primer plano. `MainActivity`: interfaz.
- Compilación: `gradle assembleDebug` (JDK 17, Gradle 8.7).
