package com.example.backend.dto;

import com.example.backend.model.EstadoRepartidor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

public class RepartidorDTO {

    private RepartidorDTO() {
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CrearRequest {

        @NotBlank(message = "El usuarioId es obligatorio")
        private String usuarioId;

        private String placaVehiculo;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActualizarRequest {

        @NotBlank(message = "La placa del vehículo es obligatoria")
        @Size(max = 20, message = "La placa no debe superar 20 caracteres")
        private String placaVehiculo;
    }

    /** Cuerpo para cambiar solo el estado (disponible / en ruta / desconectado). */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EstadoRequest {
        @NotNull(message = "El estado del repartidor es obligatorio")
        private EstadoRepartidor estado;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private String id;
        private String usuarioId;
        private String vehiculoId;
        private String placaVehiculo;
        private String tipoVehiculo;
        private EstadoRepartidor estado;
        private UbicacionDTO ubicacionActual;
        private boolean activo;
        private Date creadoEn;
        private String nombreUsuario;
        private String emailUsuario;
        private String telefonoUsuario;
        private String estadoVehiculo;
    }
}
