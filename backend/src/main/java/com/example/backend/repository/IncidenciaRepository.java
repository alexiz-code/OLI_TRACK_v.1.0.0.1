package com.example.backend.repository;

import com.example.backend.model.EstadoIncidencia;
import com.example.backend.model.Incidencia;
import com.example.backend.util.Colecciones;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public class IncidenciaRepository extends FirestoreRepository<Incidencia> {

    public IncidenciaRepository(Firestore firestore) {
        super(firestore, Colecciones.INCIDENCIAS, Incidencia.class);
    }

    public Flux<Incidencia> findByPedidoId(String pedidoId) {
        return findByField("pedidoId", pedidoId);
    }

    public Flux<Incidencia> findByRepartidorId(String repartidorId) {
        return findByField("repartidorId", repartidorId);
    }

    public Flux<Incidencia> findByEstado(EstadoIncidencia estado) {
        return findByField("estado", estado.name());
    }

    public reactor.core.publisher.Mono<Incidencia> registrarAveriaVehiculo(Incidencia incidencia, boolean inseguro) {
        DocumentReference incidenciaRef = collection().document();
        DocumentReference vehiculoRef = firestore.collection(Colecciones.VEHICULOS).document(incidencia.getVehiculoId());
        incidencia.setId(incidenciaRef.getId());
        return com.example.backend.util.ReactiveFirebase.defer(() -> firestore.runTransaction(transaction -> {
            DocumentSnapshot vehiculo = transaction.get(vehiculoRef).get();
            if (!vehiculo.exists()) throw new IllegalStateException("No existe el vehículo asignado.");
            if (!incidencia.getRepartidorId().equals(vehiculo.getString("repartidorId")))
                throw new IllegalStateException("El vehículo ya no está asignado a este repartidor.");
            transaction.set(incidenciaRef, incidencia);
            if (inseguro) transaction.update(vehiculoRef, "estadoOperativo", "PENDIENTE_REVISION", "actualizadoEn", new java.util.Date());
            return incidencia;
        }));
    }
}
