package com.example.backend.model;

import com.google.cloud.firestore.annotation.DocumentId;
import com.google.cloud.firestore.annotation.ServerTimestamp;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Date;

@Data
@NoArgsConstructor
public class HistorialVehiculo implements EntidadFirestore {
    @DocumentId private String id;
    private String vehiculoId;
    private String tipo;
    private String detalle;
    private String responsableId;
    @ServerTimestamp private Date fecha;
}
