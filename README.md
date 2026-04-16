# SETA Mobile

SETA Mobile es una app Android que convierte el teléfono en un **backend de cámara vía HTTP** para el addon **SETA** de Blender.

La app abre la cámara del dispositivo, mantiene un preview local, expone un servidor web en la red local y permite que SETA consulte estado, capacidades, preview y controles de cámara desde Blender.

En pocas palabras: **el teléfono actúa como cámara remota para SETA**.

---

## Estado actual

La app ya es **funcional** y usable como base real de integración con SETA.

Actualmente permite:

- levantar y detener un servidor HTTP local
- exponer estado de runtime de cámara y servidor
- exponer capacidades y settings soportados
- servir preview remoto
- recibir cambios de settings desde SETA
- capturar imágenes desde el flujo del backend
- seleccionar lente cuando el dispositivo lo soporta
- usar controles fotográficos relevantes para stop motion

La interfaz todavía está en evolución, pero el núcleo funcional ya existe.

---

## Objetivo

Esta app no está pensada como una app de cámara genérica para terceros.

Su objetivo es ser el **puente móvil oficial de SETA**, de forma que un dispositivo Android pueda integrarse con el addon de Blender como una cámara controlable por red.

---

## Qué hace

SETA Mobile:

- inicializa CameraX / Camera2 interop
- abre la cámara activa del dispositivo
- mantiene preview local en el teléfono
- expone un preview remoto para SETA
- levanta un servidor HTTP accesible por IP local
- publica estado, capacidades y settings
- acepta cambios de settings enviados por SETA
- captura imágenes a petición del backend

---

## Integración con Blender

La app está diseñada para trabajar con el driver móvil de SETA en Blender.

El flujo esperado es este:

1. abrir la app en Android
2. conceder permiso de cámara
3. arrancar el servidor
4. tomar la IP y puerto mostrados por la app
5. conectar SETA desde Blender usando el driver móvil HTTP
6. controlar preview, captura y settings desde SETA

La app también conserva un menú local para controles que conviene dejar del lado del hardware o del dispositivo, mientras SETA gestiona los parámetros realmente útiles dentro del flujo de animación.

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

No todos los teléfonos soportan exactamente lo mismo.  
La app publica capacidades y rangos reales, y SETA debe obedecer lo que el backend declare.

---

## Filosofía de control

El reparto de responsabilidades es este:

### En SETA
Van los controles que sí tiene sentido tocar desde Blender durante el flujo de stop motion, especialmente:

- lente
- distancia de foco
- ISO
- tiempo de exposición
- temperatura de balance de blancos

### En la app
Pueden quedarse controles más propios del hardware o de preparación del dispositivo, por ejemplo:

- modos de foco
- bloqueos automáticos
- comportamiento del balance de blancos
- ajustes locales de cámara que no hace falta exponer siempre en Blender

La idea es no llenar SETA de opciones que rara vez se tocan durante una secuencia.

---

## API HTTP

La app expone una API HTTP simple para integración con SETA.

La interfaz actual contempla, entre otras, rutas del estilo:

- estado del backend
- capacidades globales
- lectura de settings
- escritura de settings
- captura
- preview remoto

En la práctica, el driver móvil de SETA es quien debe consumir esta API.  
La app no pretende ser, por ahora, un estándar abierto para clientes arbitrarios.

---

## Preview remoto

El backend está pensado para servir preview remoto a SETA a través de la red local.

El objetivo del preview no es reemplazar una app de monitoreo cinematográfico dedicada, sino ofrecer una fuente de imagen suficientemente útil para:

- ver encuadre
- validar cambios de cámara
- trabajar desde Blender sin tocar físicamente el teléfono a cada rato

---

## Requisitos

- Android con cámara funcional
- permiso de cámara concedido
- teléfono y computadora en la misma red local
- SETA en Blender con soporte para driver móvil HTTP

Dependiendo del teléfono, algunas capacidades pueden variar.

---

## Limitaciones actuales

- el soporte depende de las capacidades reales del dispositivo Android
- algunos controles fotográficos finos varían entre fabricantes
- la interfaz aún está en desarrollo
- la app está optimizada para integrarse con SETA, no para cubrir todos los usos posibles de una app de cámara generalista

---

## Qué ya está resuelto

- app funcional con preview local
- servidor HTTP operativo
- integración base con SETA
- selección de lente
- publicación de metadata de settings
- soporte de rangos reales para controles numéricos
- controles relevantes para stop motion
- branding base, splash e icono launcher

---

## Dirección del proyecto

La prioridad no es convertir esto en una app “bonita” primero.

La prioridad es:

1. estabilidad
2. integración sólida con SETA
3. consistencia de controles de cámara
4. comportamiento útil para animación stop motion

La capa visual seguirá mejorando, pero el valor real está en que el teléfono pueda comportarse como una cámara remota útil dentro del ecosistema SETA.

---

## Roadmap cercano

- pulir la UI de la app
- seguir refinando la experiencia de preview y control
- consolidar el comportamiento entre distintos dispositivos Android
- mantener la integración con el driver móvil de SETA
- seguir delimitando qué controles viven en Blender y cuáles conviene dejar en el dispositivo

---

## Nota

SETA Mobile forma parte del ecosistema SETA y debe entenderse como una pieza del sistema, no como producto aislado.

Su razón de existir es permitir que dispositivos Android funcionen como backend de cámara para Blender dentro del flujo de stop motion de SETA.
