package com.example.backend.dto;

import com.example.backend.model.Rol;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

public class UsuarioDTO {

    private UsuarioDTO() {
    }

    /** Cuerpo esperado al registrar un usuario nuevo. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CrearRequest {

        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 120, message = "El nombre no debe superar 120 caracteres")
        private String nombre;

        @NotBlank(message = "El email es obligatorio")
        @Email(message = "El email no tiene un formato válido")
        private String email;

        @NotBlank(message = "La contraseña es obligatoria")
        @Size(min = 6, message = "La contraseña debe tener al menos 6 caracteres")
        private String password;

        @Pattern(regexp = "^(?:\\+?[0-9]{6,15})?$", message = "El teléfono no tiene un formato válido")
        private String telefono;

        @NotNull(message = "El rol es obligatorio")
        private Rol rol;
    }

    /** Cuerpo esperado al actualizar datos de perfil (no cambia email ni password). */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActualizarRequest {

        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 120, message = "El nombre no debe superar 120 caracteres")
        private String nombre;

        @Pattern(regexp = "^(?:\\+?[0-9]{6,15})?$", message = "El teléfono no tiene un formato válido")
        private String telefono;
    }

    /** Enlace de restablecimiento de contraseña generado por Firebase (HU-008). */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResetLinkResponse {
        private String link;
    }

    /** Cuerpo para que un administrador cambie el rol de un usuario existente. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CambiarRolRequest {

        @NotNull(message = "El rol es obligatorio")
        private Rol rol;
    }

    /** Representación de un usuario devuelta por la API. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private String id;
        private String nombre;
        private String email;
        private String telefono;
        private Rol rol;
        private boolean activo;
        private Date creadoEn;
    }
}
