# seta Mobile

seta Mobile es una app Android que convierte el teléfono en un **backend de cámara vía HTTP** para el addon **seta motion** 

La app abre la cámara del dispositivo, mantiene un preview local, expone un servidor web en la red local y permite que seta motion  consulte estado, capacidades, preview y controles de cámara desde Blender.

En pocas palabras: **el teléfono actúa como cámara remota para seta motion**.

---

## capacidades

- levantar y detener un servidor HTTP local
- exponer estado de runtime de cámara y servidor
- exponer capacidades y settings soportados
- servir preview remoto
- recibir cambios de settings desde seta
- capturar imágenes desde el flujo del backend
- seleccionar lente cuando el dispositivo lo soporta
- usar controles fotográficos relevantes para stop motion

---

seta Mobile:

- inicializa CameraX / Camera2 interop
- abre la cámara activa del dispositivo
- mantiene preview local en el teléfono
- expone un preview remoto para seta
- levanta un servidor HTTP accesible por IP local
- publica estado, capacidades y settings
- acepta cambios de settings enviados por seta
- captura imágenes a petición del backend

---

## Controles soportados actualmente

Según disponibilidad real del dispositivo, la app puede exponer:

- `lens`
- `focus_mode`
- `focus_distance`
- `ae_lock`
- `awb_lock`
- `white_balance_mode`
- `iso`
- `exposure_time`
- `white_balance_temperature`
