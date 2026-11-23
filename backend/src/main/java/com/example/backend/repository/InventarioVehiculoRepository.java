package com.example.backend.repository;

import com.example.backend.model.Inventario;
import com.example.backend.model.InventarioVehiculo;
import com.example.backend.model.MovimientoInventarioVehiculo;
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
            String productoId,
            int cantidad,
            String motivo,
            String usuarioResponsableId
    ) {
        DocumentReference operacionRef = firestore.collection(Colecciones.OPERACIONES_VEHICULO)
                .document(operacionId);
        DocumentReference vehiculoRef = firestore.collection(Colecciones.VEHICULOS).document(vehiculoId);
        DocumentReference stockRef = collection().document(vehiculoId + "_" + productoId);
        DocumentReference plantaRef = firestore.collection(Colecciones.INVENTARIOS).document();

        return ReactiveFirebase.defer(() -> firestore.runTransaction(transaction -> {
            DocumentSnapshot operacion = transaction.get(operacionRef).get();
            if (operacion.exists()) {
                return transaction.get(stockRef).get().toObject(InventarioVehiculo.class);
            }
            if (cantidad <= 0) {
                throw new IllegalArgumentException("La cantidad debe ser mayor que cero.");
            }
            DocumentSnapshot vehiculo = transaction.get(vehiculoRef).get();
            if (!vehiculo.exists()) {
                throw new IllegalStateException("No existe el vehículo seleccionado.");
            }

            QuerySnapshot plantaResult = transaction.get(firestore.collection(Colecciones.INVENTARIOS)
                    .whereEqualTo("productoId", productoId)).get();
            if (plantaResult.isEmpty()) {
                throw new IllegalStateException("El producto no tiene inventario en planta.");
            }
            DocumentSnapshot planta = plantaResult.getDocuments().get(0);
            Inventario inventarioPlanta = planta.toObject(Inventario.class);
            if (inventarioPlanta == null || inventarioPlanta.getStockActual() < cantidad) {
                throw new IllegalStateException("Stock insuficiente en planta.");
            }

            InventarioVehiculo stock = transaction.get(stockRef).get().exists()
                    ? transaction.get(stockRef).get().toObject(InventarioVehiculo.class)
                    : new InventarioVehiculo();
            if (stock == null) {
                stock = new InventarioVehiculo();
            }
            stock.setId(vehiculoId + "_" + productoId);
            stock.setVehiculoId(vehiculoId);
            stock.setRepartidorId(repartidorId);
            stock.setProductoId(productoId);
            stock.setCargado(stock.getCargado() + cantidad);
            stock.setActualizadoEn(new Date());

            transaction.update(planta.getReference(), "stockActual",
                    inventarioPlanta.getStockActual() - cantidad, "actualizadoEn", new Date());
            transaction.set(stockRef, stock);

            MovimientoInventarioVehiculo movimiento = new MovimientoInventarioVehiculo();
            movimiento.setId(java.util.UUID.randomUUID().toString());
            movimiento.setOperacionId(operacionId);
            movimiento.setVehiculoId(vehiculoId);
            movimiento.setVehiculoDestinoId(vehiculoId);
            movimiento.setRepartidorId(repartidorId);
            movimiento.setProductoId(productoId);
            movimiento.setTipoMovimiento(TipoMovimientoVehiculo.CARGA_PLANTA);
            movimiento.setCantidad(cantidad);
            movimiento.setMotivo(motivo);
            movimiento.setUsuarioResponsableId(usuarioResponsableId);
            transaction.set(firestore.collection(Colecciones.MOVIMIENTOS_INVENTARIO_VEHICULOS)
                    .document(movimiento.getId()), movimiento);
            transaction.set(operacionRef, java.util.Map.of(
                    "tipo", "CARGA_PLANTA",
                    "vehiculoId", vehiculoId,
                    "productoId", productoId,
                    "cantidad", cantidad,
                    "creadoEn", new Date()
            ));
            return stock;
        }));
    }
}
