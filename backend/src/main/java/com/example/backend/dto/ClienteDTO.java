package com.example.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

public class ClienteDTO {

    private ClienteDTO() {
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CrearRequest {

        @NotBlank(message = "El usuarioId es obligatorio")
        private String usuarioId;

        @NotBlank(message = "El nombre del negocio es obligatorio")
        @Size(max = 150, message = "El nombre del negocio no debe superar 150 caracteres")
        private String nombreNegocio;

        @NotBlank(message = "La dirección es obligatoria")
        private String direccion;

        private String referencia;

        @NotNull(message = "La ubicación del local es obligatoria")
        @Valid
        private UbicacionDTO ubicacion;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActualizarRequest {

        @NotBlank(message = "El nombre del negocio es obligatorio")
        @Size(max = 150, message = "El nombre del negocio no debe superar 150 caracteres")
        private String nombreNegocio;

        @NotBlank(message = "La dirección es obligatoria")
        private String direccion;

        private String referencia;

        @Valid
        private UbicacionDTO ubicacion;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DireccionCrearRequest {

        @NotBlank(message = "El alias es obligatorio")
        private String alias;

        @NotBlank(message = "La dirección es obligatoria")
        private String direccion;

        private String referencia;

        @NotNull(message = "La ubicación es obligatoria")
        @Valid
        private UbicacionDTO ubicacion;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DireccionDTO {
        private String id;
        private String alias;
        private String direccion;
        private String referencia;
        private UbicacionDTO ubicacion;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private String id;
        private String usuarioId;
        private String nombreNegocio;
        private String direccion;
        private String referencia;
        private UbicacionDTO ubicacion;
        private boolean activo;
        private Date creadoEn;
        private List<DireccionDTO> direccionesAdicionales;
        private Date ultimoPedidoEn;
        private String fotoNegocioUrl;
    }
}
