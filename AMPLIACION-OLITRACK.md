# Ampliación OLITRACK

## Rama

Los cambios locales se realizan en la rama
`feature/ampliacion-inventario-ventas`. No se hizo push.

## Implementado

### Gestión de usuarios

- El backend rechaza la creación administrativa de usuarios con rol `CLIENTE`.
- El registro público continúa creando únicamente usuarios `CLIENTE`.
- Cambiar un usuario existente al rol `CLIENTE` desde administración está bloqueado.
- El administrador no puede desactivar su propia cuenta activa.
- La interfaz administrativa ya no muestra `CLIENTE` al crear usuarios internos.
- Se agregó la acción de desactivación/reactivación con confirmación y auditoría.

### Pedido rápido

- Se agregó `POST /api/pedidos/rapido/{clienteId}` para administradores y supervisores.
- El frontend expone `crearPedidoRapido` mediante el servicio Angular existente.
- Se conserva la estructura actual de pedidos, detalles, precios históricos y notificaciones.

### Vehículos e inventario

Se agregaron las colecciones propuestas:

- `vehiculos`
- `inventarioVehiculos`
- `movimientosInventarioVehiculos`
- `reservasStock`
- `operacionesInventarioVehiculo`
- `ventasLibres`

Endpoints principales:

- `POST /api/vehiculos`
- `GET /api/vehiculos`
- `GET /api/vehiculos/{id}`
- `GET /api/vehiculos/{id}/stock`
- `POST /api/vehiculos/{id}/stock/carga`

La carga desde planta se realiza con una transacción de Firestore: descuenta planta,
incrementa el stock del vehículo y registra el movimiento y la operación idempotente.

### Ventas libres

Endpoints:

- `POST /api/ventas-libres`
- `GET /api/ventas-libres/mis-ventas`
- `GET /api/ventas-libres`

Una venta libre:

- Solo puede registrarla un repartidor con vehículo asignado.
- Toma el precio actual del catálogo al confirmarla.
- Rechaza cantidades superiores al stock disponible del vehículo.
- Descuenta el vehículo y registra movimiento en una operación idempotente.

### Reservas de pedidos

La asignación de pedidos intenta reservar los productos en el inventario del vehículo
asignado. Cancelar libera la reserva y entrega confirma la salida definitiva. Las
operaciones usan identificadores de pedido y colección de reservas para evitar
duplicados.

## Verificaciones locales

- Angular: 8 pruebas unitarias pasaron.
- Angular: compilación de producción exitosa.
- Java: compila con los nuevos modelos y controladores.
- Suite Java completa final: 30 pruebas pasaron.

## Pendientes

- Completar las interfaces visuales de pedido rápido, vehículos, ventas libres e
  historial administrativo/repartidor.
- Completar filtros y reportes que agrupen pedidos entregados y ventas libres
  sin doble contabilización.
- Sustituir por completo la selección automática lineal por evaluación vial OSRM
  con validación de stock por vehículo.
- Agregar pruebas reales de concurrencia sobre reservas y ventas libres.
- Ejecutar pruebas Firebase/Firestore con datos controlados.
- Ejecutar prueba real IAM backend → OSRM.
- Confirmar reglas de Firestore antes de publicar.

## Publicación

No se ejecutó `git push`, despliegue de Cloud Run ni despliegue de Firebase Hosting.

Antes de publicar:

1. Revisar y completar la rama.
2. Ejecutar la suite Java completa y las pruebas Angular.
3. Configurar `OSRM_BASE_URL` e `OSRM_IAM_ENABLED=true` en Cloud Run.
4. Mantener público solo el backend OLITRACK; OSRM debe seguir privado.
5. Probar con usuarios reales de cada rol.
6. Autorizar manualmente el despliegue frontend y backend.
