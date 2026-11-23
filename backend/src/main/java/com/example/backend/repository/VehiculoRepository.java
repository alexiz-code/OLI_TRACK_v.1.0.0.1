package com.example.backend.repository;

import com.example.backend.model.Vehiculo;
import com.example.backend.util.Colecciones;
import com.google.cloud.firestore.Firestore;
import org.springframework.stereotype.Repository;

@Repository
public class VehiculoRepository extends FirestoreRepository<Vehiculo> {
    public VehiculoRepository(Firestore firestore) {
        super(firestore, Colecciones.VEHICULOS, Vehiculo.class);
    }
}
