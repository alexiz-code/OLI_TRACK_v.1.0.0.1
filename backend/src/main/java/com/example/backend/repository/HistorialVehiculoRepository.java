package com.example.backend.repository;

import com.example.backend.model.HistorialVehiculo;
import com.example.backend.util.Colecciones;
import com.google.cloud.firestore.Firestore;
import org.springframework.stereotype.Repository;

@Repository
public class HistorialVehiculoRepository extends FirestoreRepository<HistorialVehiculo> {
    public HistorialVehiculoRepository(Firestore firestore) {
        super(firestore, Colecciones.HISTORIAL_VEHICULOS, HistorialVehiculo.class);
    }
}
