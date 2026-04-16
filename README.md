# SETA Mobile

SETA Mobile es una app Android de infraestructura para SETA.
No es una app de cámara de consumo.

## Requisitos de build (esta pasada)
- Android Studio (Koala o superior recomendado).
- JDK **17 o 21** para Gradle/AGP.
- Gradle instalado localmente (en este repo **no** se incluye `gradle-wrapper.jar` por restricción de PR web/Codex).

## Estado actual (honesto)
Este proyecto está en estado **laboratorio consolidado**:
- tiene una sola fuente de verdad en `app/src/main/java`;
- expone un servidor HTTP local con rutas base;
- abre cámara real con CameraX (`Preview` + `ImageCapture`);
- muestra preview local real en `PreviewView`;
- permite captura JPG real usando `ImageCapture`;
- permite descargar capturas por endpoint.

## Qué sí hace hoy
- `POST /api/v1/capture`
- `GET /api/v1/capture/{id}`
- `GET /api/v1/status`
- `GET /api/v1/capabilities`
- `GET /api/v1/settings`
- `POST /api/v1/preview/start`
- `POST /api/v1/preview/stop`
- `GET /api/v1/preview`

## Flujo mínimo validable hoy
1. Abrir la app.
2. Conceder permiso de cámara.
3. Abrir cámara y ver preview local.
4. Capturar JPG (`Capture`).
5. Consultar estado (`GET /api/v1/status`).
6. Disparar captura remota (`POST /api/v1/capture`).
7. Descargar captura (`GET /api/v1/capture/{id}`).
8. Activar preview remoto (`POST /api/v1/preview/start`) y leer `GET /api/v1/preview` (MJPEG de laboratorio).

## Qué NO está cerrado todavía
- Motor CameraX de producción (bind/lifecycle/captura nativa robusta).
- Streaming MJPEG continuo de producción (el endpoint actual sigue siendo base de laboratorio).
- Preview remoto sustentado principalmente en frames de `ImageAnalysis` (fallback a bitmap de preview si no hay frame de análisis).
- Fallback de captura desde frame de preview solo como contingencia si falla `ImageCapture`.
- Ajustes avanzados reales (zoom/torch y otros según hardware).
- Seguridad/autenticación para exposición de red más allá de LAN de pruebas.

## Estructura de referencia
- `app/src/main/java/com/seta/androidbridge/app`
- `app/src/main/java/com/seta/androidbridge/ui`
- `app/src/main/java/com/seta/androidbridge/camera`
- `app/src/main/java/com/seta/androidbridge/server`
- `app/src/main/java/com/seta/androidbridge/storage`
- `app/src/main/java/com/seta/androidbridge/domain`
- `app/src/main/java/com/seta/androidbridge/net`
- `app/src/main/java/com/seta/androidbridge/logging`
- `app/src/main/java/com/seta/androidbridge/util`

## Build rápido
```bash
cd seta_bridge
JAVA_HOME=/ruta/a/jdk-21 gradle :app:assembleDebug
```

Si usas Android Studio, apunta el Gradle JDK a 17 o 21.
