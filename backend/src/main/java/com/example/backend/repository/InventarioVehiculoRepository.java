package com.example.backend.repository;

import com.example.backend.model.Inventario;
import com.example.backend.model.InventarioVehiculo;
import com.example.backend.model.MovimientoInventarioVehiculo;
import com.example.backend.model.Kardex;
import com.example.backend.model.TipoMovimiento;
import com.example.backend.model.TipoMovimientoVehiculo;
import com.example.backend.util.Colecciones;
import com.example.backend.util.ReactiveFirebase;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QuerySnapshot;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Date;

@Repository
public class InventarioVehiculoRepository extends FirestoreRepository<InventarioVehiculo> {
    private final Firestore firestore;

    public InventarioVehiculoRepository(Firestore firestore) {
        super(firestore, Colecciones.INVENTARIO_VEHICULOS, InventarioVehiculo.class);
        this.firestore = firestore;
    }

    public Flux<InventarioVehiculo> listarPorVehiculo(String vehiculoId) {
        return findByField("vehiculoId", vehiculoId);
    }

    public Mono<Void> reservarParaPedido(
            String pedidoId,
            String vehiculoId,
            String repartidorId,
            java.util.Map<String, Integer> cantidades
    ) {
        DocumentReference operacionRef = firestore.collection(Colecciones.RESERVAS_STOCK)
                .document(pedidoId);
        return ReactiveFirebase.defer(() -> firestore.runTransaction(transaction -> {
            DocumentSnapshot operacion = transaction.get(operacionRef).get();
            if (operacion.exists()) {
                return null;
            }
            java.util.List<java.util.Map.Entry<String, Integer>> entradas =
                    new java.util.ArrayList<>(cantidades.entrySet());
            for (java.util.Map.Entry<String, Integer> entrada : entradas) {
                if (entrada.getValue() == null || entrada.getValue() <= 0) {
                    throw new IllegalArgumentException("La cantidad reservada debe ser mayor que cero.");
                }
                DocumentReference stockRef = collection().document(vehiculoId + "_" + entrada.getKey());
                DocumentSnapshot stockSnapshot = transaction.get(stockRef).get();
                if (!stockSnapshot.exists()) {
                    throw new IllegalStateException("El vehículo no tiene stock de un producto solicitado.");
                }
                InventarioVehiculo stock = stockSnapshot.toObject(InventarioVehiculo.class);
                if (stock == null || stock.getDisponible() < entrada.getValue()) {
                    throw new IllegalStateException("Stock insuficiente en el vehículo para el pedido.");
                }
            }
            for (java.util.Map.Entry<String, Integer> entrada : entradas) {
                DocumentReference stockRef = collection().document(vehiculoId + "_" + entrada.getKey());
                InventarioVehiculo stock = transaction.get(stockRef).get().toObject(InventarioVehiculo.class);
                stock.setReservado(stock.getReservado() + entrada.getValue());
                stock.setRepartidorId(repartidorId);
                stock.setActualizadoEn(new Date());
                transaction.set(stockRef, stock);
            }
            transaction.set(operacionRef, java.util.Map.of(
                    "pedidoId", pedidoId,
                    "vehiculoId", vehiculoId,
                    "repartidorId", repartidorId,
                    "estado", "RESERVADA",
                    "cantidades", cantidades,
                    "creadoEn", new Date()
            ));
            return null;
        })).then();
    }

    public Mono<Void> liberarReserva(String pedidoId) {
        DocumentReference operacionRef = firestore.collection(Colecciones.RESERVAS_STOCK)
                .document(pedidoId);
        return ReactiveFirebase.defer(() -> firestore.runTransaction(transaction -> {
            DocumentSnapshot operacion = transaction.get(operacionRef).get();
            if (!operacion.exists()) {
                return null;
            }
            String estado = operacion.getString("estado");
            if ("LIBERADA".equals(estado) || "ENTREGADA".equals(estado)) {
                return null;
            }
            String vehiculoId = operacion.getString("vehiculoId");
            Object cantidadesValor = operacion.get("cantidades");
            if (!(cantidadesValor instanceof java.util.Map<?, ?> cantidades)) {
                throw new IllegalStateException("La reserva no contiene cantidades válidas.");
            }
            java.util.List<InventarioVehiculo> stocksLiberar = new java.util.ArrayList<>();
            for (java.util.Map.Entry<?, ?> entrada : cantidades.entrySet()) {
                String productoId = String.valueOf(entrada.getKey());
                int cantidad = ((Number) entrada.getValue()).intValue();
                DocumentReference stockRef = collection().document(vehiculoId + "_" + productoId);
                DocumentSnapshot stockSnapshot = transaction.get(stockRef).get();
                if (!stockSnapshot.exists()) {
                    continue;
                }
                InventarioVehiculo stock = stockSnapshot.toObject(InventarioVehiculo.class);
                stock.setReservado(Math.max(0, stock.getReservado() - cantidad));
                stock.setActualizadoEn(new Date());
                stocksLiberar.add(stock);
            }
            for (InventarioVehiculo stock : stocksLiberar) {
                DocumentReference stockRef = collection().document(stock.getVehiculoId() + "_" + stock.getProductoId());
                transaction.set(stockRef, stock);
            }
            transaction.update(operacionRef, "estado", "LIBERADA", "actualizadoEn", new Date());
            return null;
        })).then();
    }

    public Mono<Void> confirmarEntregaPedido(String pedidoId) {
        DocumentReference operacionRef = firestore.collection(Colecciones.RESERVAS_STOCK)
                .document(pedidoId);
        return ReactiveFirebase.defer(() -> firestore.runTransaction(transaction -> {
            DocumentSnapshot operacion = transaction.get(operacionRef).get();
            if (!operacion.exists()) {
                return null;
            }
            String estado = operacion.getString("estado");
            if ("ENTREGADA".equals(estado)) {
                return null;
            }
            String vehiculoId = operacion.getString("vehiculoId");
            Object cantidadesValor = operacion.get("cantidades");
            if (!(cantidadesValor instanceof java.util.Map<?, ?> cantidades)) {
                throw new IllegalStateException("La reserva no contiene cantidades válidas.");
            }
            java.util.List<InventarioVehiculo> stocksEntregar = new java.util.ArrayList<>();
            for (java.util.Map.Entry<?, ?> entrada : cantidades.entrySet()) {
                String productoId = String.valueOf(entrada.getKey());
                int cantidad = ((Number) entrada.getValue()).intValue();
                DocumentReference stockRef = collection().document(vehiculoId + "_" + productoId);
                DocumentSnapshot stockSnapshot = transaction.get(stockRef).get();
                if (!stockSnapshot.exists()) {
                    throw new IllegalStateException("No existe el stock reservado del pedido.");
                }
                InventarioVehiculo stock = stockSnapshot.toObject(InventarioVehiculo.class);
                stock.setReservado(Math.max(0, stock.getReservado() - cantidad));
                stock.setEntregado(stock.getEntregado() + cantidad);
                stock.setActualizadoEn(new Date());
                stocksEntregar.add(stock);
            }
            for (InventarioVehiculo stock : stocksEntregar) {
                DocumentReference stockRef = collection().document(stock.getVehiculoId() + "_" + stock.getProductoId());
                transaction.set(stockRef, stock);
            }
            transaction.update(operacionRef, "estado", "ENTREGADA", "actualizadoEn", new Date());
            return null;
        })).then();
    }

    public Mono<InventarioVehiculo> cargarDesdePlanta(
            String operacionId,
            String vehiculoId,
            String repartidorId,
            java.util.Map<String, Integer> cantidades,
            String motivo,
            String usuarioResponsableId
    ) {
        DocumentReference operacionRef = firestore.collection(Colecciones.OPERACIONES_VEHICULO)
                .document(operacionId);
        DocumentReference vehiculoRef = firestore.collection(Colecciones.VEHICULOS).document(vehiculoId);
        return ReactiveFirebase.defer(() -> firestore.runTransaction(transaction -> {
            DocumentSnapshot operacion = transaction.get(operacionRef).get();
            if (operacion.exists()) {
                String productoExistente = operacion.getString("productoPrincipalId");
                return productoExistente == null ? null : transaction.get(
                        collection().document(vehiculoId + "_" + productoExistente)).get()
                        .toObject(InventarioVehiculo.class);
            }
            if (cantidades == null || cantidades.isEmpty()) {
                throw new IllegalArgumentException("Indica al menos un producto para transferir.");
            }
            DocumentSnapshot vehiculo = transaction.get(vehiculoRef).get();
            if (!vehiculo.exists()) {
                throw new IllegalStateException("No existe el vehículo seleccionado.");
            }

            java.util.List<MovimientoCarga> movimientos = new java.util.ArrayList<>();
            for (var entrada : cantidades.entrySet()) {
                String productoId = entrada.getKey();
                int cantidad = entrada.getValue() == null ? 0 : entrada.getValue();
                if (cantidad <= 0) throw new IllegalArgumentException("La cantidad debe ser mayor que cero.");
                QuerySnapshot plantaResult = transaction.get(firestore.collection(Colecciones.INVENTARIOS)
                        .whereEqualTo("productoId", productoId)).get();
                if (plantaResult.size() != 1) throw new IllegalStateException("Inventario de planta inexistente o duplicado para " + productoId);
                DocumentSnapshot planta = plantaResult.getDocuments().get(0);
                Inventario inventarioPlanta = planta.toObject(Inventario.class);
                if (inventarioPlanta == null || inventarioPlanta.getStockActual() < cantidad)
                    throw new IllegalStateException("Stock insuficiente en planta para " + productoId);
                DocumentReference stockRef = collection().document(vehiculoId + "_" + productoId);
                DocumentSnapshot stockSnap = transaction.get(stockRef).get();
                InventarioVehiculo stock = stockSnap.exists() ? stockSnap.toObject(InventarioVehiculo.class) : new InventarioVehiculo();
                if (stock == null) stock = new InventarioVehiculo();
                int anteriorPlanta = inventarioPlanta.getStockActual();
                stock.setId(vehiculoId + "_" + productoId);
                stock.setVehiculoId(vehiculoId);
                stock.setRepartidorId(repartidorId);
                stock.setProductoId(productoId);
                stock.setCargado(stock.getCargado() + cantidad);
                stock.setActualizadoEn(new Date());
                stock.setUltimaCargaEn(new Date());
                movimientos.add(new MovimientoCarga(planta.getReference(), stockRef, inventarioPlanta,
                        stock, productoId, cantidad, anteriorPlanta));
            }
            for (MovimientoCarga movimiento : movimientos) {
                int nuevoPlanta = movimiento.inventarioPlanta().getStockActual() - movimiento.cantidad();
                transaction.update(movimiento.plantaRef(), "stockActual", nuevoPlanta, "actualizadoEn", new Date());
                transaction.set(movimiento.stockRef(), movimiento.stock());
                Kardex kardex = new Kardex(null, movimiento.productoId(), TipoMovimiento.SALIDA,
                        movimiento.cantidad(), movimiento.anterior(), nuevoPlanta,
                        motivo == null ? "CARGA A VEHICULO" : motivo, null);
                transaction.set(firestore.collection(Colecciones.KARDEX).document(), kardex);
                MovimientoInventarioVehiculo registro = crearMovimiento(operacionId, vehiculoId, repartidorId,
                        movimiento.productoId(), movimiento.cantidad(), motivo, usuarioResponsableId,
                        TipoMovimientoVehiculo.CARGA_PLANTA);
                transaction.set(firestore.collection(Colecciones.MOVIMIENTOS_INVENTARIO_VEHICULOS)
                        .document(registro.getId()), registro);
            }
            transaction.set(operacionRef, java.util.Map.of(
                    "tipo", "CARGA_PLANTA",
                    "vehiculoId", vehiculoId,
                    "productoPrincipalId", cantidades.keySet().iterator().next(),
                    "productos", cantidades,
                    "repartidorId", repartidorId,
                    "usuarioResponsableId", usuarioResponsableId,
                    "creadoEn", new Date()
            ));
            return movimientos.get(0).stock();
        }));
    }

    public Mono<InventarioVehiculo> devolverAPlanta(String operacionId, String vehiculoId, String repartidorId,
            java.util.Map<String, Integer> cantidades, String motivo, String usuarioResponsableId) {
        DocumentReference operacionRef = firestore.collection(Colecciones.OPERACIONES_VEHICULO).document(operacionId);
        return ReactiveFirebase.defer(() -> firestore.runTransaction(transaction -> {
            DocumentSnapshot operacionExistente = transaction.get(operacionRef).get();
            if (operacionExistente.exists()) {
                Object lineas = operacionExistente.get("productos");
                if (lineas instanceof java.util.Map<?, ?> mapa && !mapa.isEmpty()) {
                    String productoId = String.valueOf(mapa.keySet().iterator().next());
                    return transaction.get(collection().document(vehiculoId + "_" + productoId)).get()
                            .toObject(InventarioVehiculo.class);
                }
                return null;
            }
            if (cantidades == null || cantidades.isEmpty()) throw new IllegalArgumentException("Indica productos para devolver.");
            java.util.List<MovimientoDevolucion> cambios = new java.util.ArrayList<>();
            for (var entrada : cantidades.entrySet()) {
                int cantidad = entrada.getValue() == null ? 0 : entrada.getValue();
                if (cantidad <= 0) throw new IllegalArgumentException("La cantidad debe ser mayor que cero.");
                String productoId = entrada.getKey();
                DocumentReference stockRef = collection().document(vehiculoId + "_" + productoId);
                DocumentSnapshot stockSnapshot = transaction.get(stockRef).get();
                if (!stockSnapshot.exists()) throw new IllegalStateException("No existe stock del producto en el vehículo.");
                InventarioVehiculo stock = stockSnapshot.toObject(InventarioVehiculo.class);
                if (stock == null || stock.getDisponible() < cantidad) throw new IllegalStateException("La devolución supera el stock físico disponible (sin reservas).");
                QuerySnapshot plantaResult = transaction.get(firestore.collection(Colecciones.INVENTARIOS).whereEqualTo("productoId", productoId)).get();
                if (plantaResult.size() != 1) throw new IllegalStateException("Inventario de planta inexistente o duplicado para " + productoId);
                DocumentSnapshot plantaDoc = plantaResult.getDocuments().get(0);
                Inventario planta = plantaDoc.toObject(Inventario.class);
                if (planta == null) throw new IllegalStateException("No se pudo leer inventario de planta.");
                cambios.add(new MovimientoDevolucion(stockRef, plantaDoc.getReference(), stock, planta,
                        productoId, cantidad, planta.getStockActual()));
            }
            for (MovimientoDevolucion cambio : cambios) {
                cambio.stock().setCargado(cambio.stock().getCargado() - cambio.cantidad());
                cambio.stock().setActualizadoEn(new Date());
                transaction.set(cambio.stockRef(), cambio.stock());
                int nuevo = cambio.stockPlanta().getStockActual() + cambio.cantidad();
                transaction.update(cambio.plantaRef(), "stockActual", nuevo, "actualizadoEn", new Date());
                Kardex kardex = new Kardex(null, cambio.productoId(), TipoMovimiento.ENTRADA, cambio.cantidad(),
                        cambio.stockPlanta().getStockActual(), nuevo, motivo == null ? "DEVOLUCION VEHICULO" : motivo, null);
                transaction.set(firestore.collection(Colecciones.KARDEX).document(), kardex);
                MovimientoInventarioVehiculo registro = crearMovimiento(operacionId, vehiculoId, repartidorId,
                        cambio.productoId(), cambio.cantidad(), motivo, usuarioResponsableId,
                        TipoMovimientoVehiculo.DEVOLUCION);
                registro.setVehiculoOrigenId(vehiculoId);
                transaction.set(firestore.collection(Colecciones.MOVIMIENTOS_INVENTARIO_VEHICULOS).document(registro.getId()), registro);
            }
            transaction.set(operacionRef, java.util.Map.of("tipo", "DEVOLUCION_PLANTA", "vehiculoId", vehiculoId,
                    "repartidorId", repartidorId == null ? "" : repartidorId, "productos", cantidades, "usuarioResponsableId", usuarioResponsableId,
                    "creadoEn", new Date()));
            return cambios.get(0).stock();
        }));
    }

    private MovimientoInventarioVehiculo crearMovimiento(String operacionId, String vehiculoId, String repartidorId,
            String productoId, int cantidad, String motivo, String usuario, TipoMovimientoVehiculo tipo) {
        MovimientoInventarioVehiculo movimiento = new MovimientoInventarioVehiculo();
        movimiento.setId(java.util.UUID.randomUUID().toString()); movimiento.setOperacionId(operacionId);
        movimiento.setVehiculoId(vehiculoId); movimiento.setVehiculoDestinoId(vehiculoId);
        movimiento.setRepartidorId(repartidorId); movimiento.setProductoId(productoId);
        movimiento.setTipoMovimiento(tipo); movimiento.setCantidad(cantidad); movimiento.setMotivo(motivo);
        movimiento.setUsuarioResponsableId(usuario);
        return movimiento;
    }

    private record MovimientoCarga(DocumentReference plantaRef, DocumentReference stockRef, Inventario inventarioPlanta,
            InventarioVehiculo stock, String productoId, int cantidad, int anterior) { }
    private record MovimientoDevolucion(DocumentReference stockRef, DocumentReference plantaRef, InventarioVehiculo stock,
            Inventario stockPlanta, String productoId, int cantidad, int anterior) { }
}
