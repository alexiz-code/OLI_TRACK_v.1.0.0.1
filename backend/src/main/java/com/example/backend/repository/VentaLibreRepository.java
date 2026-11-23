package com.example.backend.repository;

import com.example.backend.model.InventarioVehiculo;
import com.example.backend.model.MovimientoInventarioVehiculo;
import com.example.backend.model.TipoMovimientoVehiculo;
import com.example.backend.model.VentaLibre;
import com.example.backend.util.Colecciones;
import com.example.backend.util.ReactiveFirebase;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.util.Date;
import java.util.UUID;

@Repository
public class VentaLibreRepository extends FirestoreRepository<VentaLibre> {
    private final Firestore firestore;

    public VentaLibreRepository(Firestore firestore) {
        super(firestore, Colecciones.VENTAS_LIBRES, VentaLibre.class);
        this.firestore = firestore;
    }

    public Mono<VentaLibre> registrarVenta(
            String operacionId,
            VentaLibre venta,
            String vehiculoId,
            String productoId,
            int cantidad
    ) {
        DocumentReference operacionRef = firestore.collection(Colecciones.OPERACIONES_VEHICULO)
                .document(operacionId);
        DocumentReference stockRef = firestore.collection(Colecciones.INVENTARIO_VEHICULOS)
                .document(vehiculoId + "_" + productoId);
        DocumentReference ventaRef = collection().document(operacionId);

        return ReactiveFirebase.defer(() -> firestore.runTransaction(transaction -> {
            DocumentSnapshot operacion = transaction.get(operacionRef).get();
            if (operacion.exists()) {
                DocumentSnapshot ventaExistente = transaction.get(ventaRef).get();
                return ventaExistente.exists()
                        ? ventaExistente.toObject(VentaLibre.class)
                        : venta;
            }
            DocumentSnapshot stockSnapshot = transaction.get(stockRef).get();
            if (!stockSnapshot.exists()) {
                throw new IllegalStateException("El vehículo no tiene stock registrado para este producto.");
            }
            InventarioVehiculo stock = stockSnapshot.toObject(InventarioVehiculo.class);
            if (stock == null) {
                throw new IllegalStateException("No se pudo leer el stock del vehículo.");
            }
            int disponible = stock.getDisponible();
            if (cantidad <= 0) {
                throw new IllegalArgumentException("La cantidad debe ser mayor que cero.");
            }
            if (disponible < cantidad) {
                throw new IllegalStateException("Stock insuficiente en el vehículo.");
            }

            venta.setId(operacionId);
            venta.setOperacionId(operacionId);
            venta.setVehiculoId(vehiculoId);
            venta.setEstado("CONFIRMADA");
            stock.setVendido(stock.getVendido() + cantidad);
            stock.setActualizadoEn(new Date());
            transaction.set(stockRef, stock);
            transaction.set(ventaRef, venta);

            MovimientoInventarioVehiculo movimiento = new MovimientoInventarioVehiculo();
            movimiento.setId(UUID.randomUUID().toString());
            movimiento.setOperacionId(operacionId);
            movimiento.setVehiculoId(vehiculoId);
            movimiento.setVehiculoOrigenId(vehiculoId);
            movimiento.setRepartidorId(venta.getRepartidorId());
            movimiento.setProductoId(productoId);
            movimiento.setTipoMovimiento(TipoMovimientoVehiculo.VENTA_LIBRE);
            movimiento.setCantidad(cantidad);
            movimiento.setMotivo("Venta libre confirmada");
            movimiento.setUsuarioResponsableId(venta.getUsuarioResponsableId());
            transaction.set(firestore.collection(Colecciones.MOVIMIENTOS_INVENTARIO_VEHICULOS)
                    .document(movimiento.getId()), movimiento);
            transaction.set(operacionRef, java.util.Map.of(
                    "tipo", "VENTA_LIBRE",
                    "vehiculoId", vehiculoId,
                    "productoId", productoId,
                    "cantidad", cantidad,
                    "creadoEn", new Date()
            ));
            return venta;
        }));
    }

}
