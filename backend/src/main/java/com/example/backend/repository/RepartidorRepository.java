package com.example.backend.repository;

import com.example.backend.model.EstadoRepartidor;
import com.example.backend.model.Repartidor;
import com.example.backend.model.Ubicacion;
import com.example.backend.util.Colecciones;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Transaction;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public class RepartidorRepository extends FirestoreRepository<Repartidor> {

    public RepartidorRepository(Firestore firestore) {
        super(firestore, Colecciones.REPARTIDORES, Repartidor.class);
    }

    public Mono<Repartidor> findByUsuarioId(String usuarioId) {
        return findByField("usuarioId", usuarioId).next();
    }

    /** Usado por GET /api/repartidores/disponibles. */
    public Flux<Repartidor> findByEstado(EstadoRepartidor estado) {
        return findByField("estado", estado.name());
    }

    /** Reclama de forma atómica un repartidor libre para impedir asignaciones simultáneas. */
    public Mono<Repartidor> reclamarDisponible(String repartidorId) {
        return reclamarDisponible(repartidorId, false);
    }

    /** Un supervisor puede asignar explícitamente a un repartidor desconectado. */
    public Mono<Repartidor> reclamarParaAsignacionManual(String repartidorId) {
        return reclamarDisponible(repartidorId, true);
    }

    private Mono<Repartidor> reclamarDisponible(String repartidorId, boolean permitirDesconectado) {
        DocumentReference ref = collection().document(repartidorId);
        return com.example.backend.util.ReactiveFirebase.defer(() -> firestore.runTransaction(transaction -> {
            DocumentSnapshot snapshot = transaction.get(ref).get();
            if (!snapshot.exists()) {
                throw new IllegalStateException("No existe el repartidor " + repartidorId);
            }
            Repartidor repartidor = snapshot.toObject(Repartidor.class);
            boolean estadoAsignable = repartidor != null
                    && (repartidor.getEstado() == EstadoRepartidor.DISPONIBLE
                    || permitirDesconectado && repartidor.getEstado() == EstadoRepartidor.DESCONECTADO);
            if (repartidor == null || !repartidor.isActivo() || !estadoAsignable || repartidor.getVehiculoId() == null) {
                throw new IllegalStateException("El repartidor no está activo y disponible");
            }
            DocumentReference vehicleRef = firestore.collection(Colecciones.VEHICULOS).document(repartidor.getVehiculoId());
            DocumentSnapshot vehicleSnapshot = transaction.get(vehicleRef).get();
            if (!vehicleSnapshot.exists() || !Boolean.TRUE.equals(vehicleSnapshot.getBoolean("activo"))
                    || !"DISPONIBLE".equals(vehicleSnapshot.getString("estadoOperativo"))) {
                throw new IllegalStateException("El vehiculo asignado no esta disponible para nuevos pedidos.");
            }
            transaction.update(ref, "estado", EstadoRepartidor.EN_RUTA.name());
            repartidor.setEstado(EstadoRepartidor.EN_RUTA);
            return repartidor;
        }));
    }

    public Mono<Void> actualizarEstado(String repartidorId, EstadoRepartidor estado) {
        DocumentReference ref = collection().document(repartidorId);
        return com.example.backend.util.ReactiveFirebase.defer(() -> firestore.runTransaction(transaction -> {
            DocumentSnapshot snapshot = transaction.get(ref).get();
            if (!snapshot.exists()) {
                throw new IllegalStateException("No existe el repartidor " + repartidorId);
            }
            transaction.update(ref, "estado", estado.name());
            return null;
        })).then();
    }

    public Mono<Void> actualizarPlaca(String repartidorId, String placa) {
        DocumentReference ref = collection().document(repartidorId);
        return com.example.backend.util.ReactiveFirebase.defer(() -> firestore.runTransaction(transaction -> {
            DocumentSnapshot snapshot = transaction.get(ref).get();
            if (!snapshot.exists()) {
                throw new IllegalStateException("No existe el repartidor " + repartidorId);
            }
            transaction.update(ref, "placaVehiculo", placa);
            return null;
        })).then();
    }

    public Mono<Void> actualizarUbicacion(String repartidorId, Ubicacion ubicacion) {
        DocumentReference ref = collection().document(repartidorId);
        return com.example.backend.util.ReactiveFirebase.defer(() -> firestore.runTransaction(transaction -> {
            DocumentSnapshot snapshot = transaction.get(ref).get();
            if (!snapshot.exists()) {
                throw new IllegalStateException("No existe el repartidor " + repartidorId);
            }
            transaction.update(ref, "ubicacionActual", ubicacion);
            return null;
        })).then();
    }

    /** Asignación bidireccional y con exclusión por transacción para evitar dobles vehículos. */
    public Mono<Void> asignarVehiculo(String vehiculoId, String repartidorId, String placa, String actorId, String motivo) {
        DocumentReference driverRef = collection().document(repartidorId);
        DocumentReference vehicleRef = firestore.collection(Colecciones.VEHICULOS).document(vehiculoId);
        DocumentReference historyRef = firestore.collection(Colecciones.HISTORIAL_VEHICULOS).document();
        return com.example.backend.util.ReactiveFirebase.defer(() -> firestore.runTransaction(tx -> {
            DocumentSnapshot driverSnapshot = tx.get(driverRef).get();
            DocumentSnapshot vehicleSnapshot = tx.get(vehicleRef).get();
            if (!driverSnapshot.exists() || !vehicleSnapshot.exists()) throw new IllegalStateException("Repartidor o vehículo inexistente.");
            Repartidor driver = driverSnapshot.toObject(Repartidor.class);
            String currentDriver = vehicleSnapshot.getString("repartidorId");
            String currentVehicle = driver.getVehiculoId();
            if (currentVehicle != null && !currentVehicle.equals(vehiculoId)) throw new IllegalStateException("El repartidor ya tiene otro vehículo asignado.");
            DocumentReference oldVehicleRef = currentVehicle == null ? null : firestore.collection(Colecciones.VEHICULOS).document(currentVehicle);
            DocumentSnapshot oldVehicle = oldVehicleRef == null ? null : tx.get(oldVehicleRef).get();
            DocumentReference previousDriverRef = currentDriver == null || currentDriver.equals(repartidorId) ? null : collection().document(currentDriver);
            DocumentSnapshot previousDriver = previousDriverRef == null ? null : tx.get(previousDriverRef).get();
            if (oldVehicleRef != null && oldVehicle.exists() && !vehiculoId.equals(currentVehicle)) {
                tx.update(oldVehicleRef, "repartidorId", null, "actualizadoEn", new java.util.Date());
            }
            if (previousDriverRef != null && previousDriver.exists()) {
                tx.update(previousDriverRef, "vehiculoId", null, "placaVehiculo", null);
            }
            tx.update(driverRef, "vehiculoId", vehiculoId, "placaVehiculo", placa);
            tx.update(vehicleRef, "repartidorId", repartidorId, "actualizadoEn", new java.util.Date());
            java.util.Map<String, Object> history = new java.util.HashMap<>();
            history.put("vehiculoId", vehiculoId); history.put("tipo", "ASIGNACION");
            history.put("detalle", "Responsable " + (currentDriver == null ? "" : currentDriver + " → ") + repartidorId + (motivo == null ? "" : ": " + motivo));
            history.put("responsableId", actorId); history.put("fecha", new java.util.Date());
            tx.set(historyRef, history);
            return null;
        })).then();
    }

    public Mono<Void> desasignarVehiculo(String vehiculoId, String actorId, String motivo) {
        DocumentReference vehicleRef = firestore.collection(Colecciones.VEHICULOS).document(vehiculoId);
        DocumentReference historyRef = firestore.collection(Colecciones.HISTORIAL_VEHICULOS).document();
        return com.example.backend.util.ReactiveFirebase.defer(() -> firestore.runTransaction(tx -> {
            DocumentSnapshot vehicle = tx.get(vehicleRef).get();
            if (!vehicle.exists()) throw new IllegalStateException("No existe el vehículo.");
            String driverId = vehicle.getString("repartidorId");
            DocumentReference driverRef = driverId == null ? null : collection().document(driverId);
            DocumentSnapshot driver = driverRef == null ? null : tx.get(driverRef).get();
            tx.update(vehicleRef, "repartidorId", null, "actualizadoEn", new java.util.Date());
            if (driverRef != null && driver.exists()) tx.update(driverRef, "vehiculoId", null, "placaVehiculo", null);
            java.util.Map<String, Object> history = new java.util.HashMap<>();
            history.put("vehiculoId", vehiculoId); history.put("tipo", "DESASIGNACION");
            history.put("detalle", motivo == null ? "Responsable retirado: " + driverId : motivo);
            history.put("responsableId", actorId); history.put("fecha", new java.util.Date());
            tx.set(historyRef, history);
            return null;
        })).then();
    }
}
