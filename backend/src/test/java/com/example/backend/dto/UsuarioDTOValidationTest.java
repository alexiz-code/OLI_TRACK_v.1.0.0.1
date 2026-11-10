package com.example.backend.dto;

import com.example.backend.model.Rol;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class UsuarioDTOValidationTest {

    private static final ValidatorFactory validatorFactory =
            Validation.buildDefaultValidatorFactory();
    private static final Validator validator =
            validatorFactory.getValidator();

    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    @Test
    void acceptsAnEmptyOptionalPhoneNumber() {
        UsuarioDTO.CrearRequest request = validRequest();
        request.setTelefono("");

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void rejectsAnInvalidPhoneNumberWhenProvided() {
        UsuarioDTO.CrearRequest request = validRequest();
        request.setTelefono("123");

        assertTrue(
                validator.validate(request).stream()
                        .anyMatch(error -> error.getPropertyPath().toString().equals("telefono"))
        );
    }

    private static UsuarioDTO.CrearRequest validRequest() {
        UsuarioDTO.CrearRequest request = new UsuarioDTO.CrearRequest();
        request.setNombre("Cliente");
        request.setEmail("cliente@example.com");
        request.setPassword("secreto");
        request.setRol(Rol.CLIENTE);
        return request;
    }
}
