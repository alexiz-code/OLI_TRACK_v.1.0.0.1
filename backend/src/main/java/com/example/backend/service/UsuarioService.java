package com.example.backend.service;

import com.example.backend.dto.UsuarioDTO;
import com.example.backend.exception.OperacionInvalidaException;
import com.example.backend.exception.RecursoNoEncontradoException;
import com.example.backend.mapper.EntityMapper;
import com.example.backend.model.Rol;
import com.example.backend.model.Usuario;
import com.example.backend.repository.UsuarioRepository;
import com.example.backend.util.ReactiveFirebase;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.UserRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Registro de usuarios y gestión de perfil.
 *
 * El registro crea la cuenta primero en Firebase Authentication (Admin SDK)
 * y luego el documento de perfil en Firestore, usando el mismo uid como id.
 *
 * Si la escritura en Firestore falla, se revierte la cuenta de Firebase
 * para no dejar cuentas sin perfil asociado.
 */
@Service
public class UsuarioService {

    private static final Logger log =
            LoggerFactory.getLogger(UsuarioService.class);

    private final FirebaseAuth firebaseAuth;
    private final UsuarioRepository usuarioRepository;
    private final AuditService auditService;
    private final EntityMapper mapper;

    public UsuarioService(
            FirebaseAuth firebaseAuth,
            UsuarioRepository usuarioRepository,
            AuditService auditService,
            EntityMapper mapper) {

        this.firebaseAuth = firebaseAuth;
        this.usuarioRepository = usuarioRepository;
        this.auditService = auditService;
        this.mapper = mapper;
    }

    /**
     * Registra un usuario.
     *
     * @param request datos del usuario
     * @param rolForzado si no es null, se utiliza este rol ignorando
     *                   el rol enviado en el request.
     *
     * Ejemplo:
     *
     * registrar(request, Rol.CLIENTE)
     *
     * fuerza CLIENTE.
     *
     * registrar(request, Rol.ADMINISTRADOR)
     *
     * fuerza ADMINISTRADOR.
     *
     * registrar(request, null)
     *
     * utiliza el rol enviado por el request.
     */
    public Mono<UsuarioDTO.Response> registrar(
            UsuarioDTO.CrearRequest request,
            Rol rolForzado) {

        Rol rol = rolForzado != null
                ? rolForzado
                : request.getRol();

        return usuarioRepository
                .findByEmail(request.getEmail())

                .flatMap(existente ->
                        Mono.<UsuarioDTO.Response>error(
                                new OperacionInvalidaException(
                                        "Ya existe un usuario registrado con ese email"
                                )
                        )
                )

                .switchIfEmpty(
                        Mono.defer(
                                () -> crearCuentaCompleta(
                                        request,
                                        rol
                                )
                        )
                );
    }

    /**
     * Crea primero la cuenta en Firebase Authentication.
     */
    private Mono<UsuarioDTO.Response> crearCuentaCompleta(
            UsuarioDTO.CrearRequest request,
            Rol rol) {

        UserRecord.CreateRequest firebaseRequest =
                new UserRecord.CreateRequest()
                        .setEmail(request.getEmail())
                        .setPassword(request.getPassword())
                        .setDisplayName(request.getNombre())
                        .setDisabled(false);

        return ReactiveFirebase
                .<UserRecord>defer(
                        () -> firebaseAuth.createUserAsync(firebaseRequest)
                )

                .onErrorMap(
                        e -> new OperacionInvalidaException(
                                "No se pudo crear la cuenta: "
                                        + e.getMessage()
                        )
                )

                .flatMap(
                        userRecord ->
                                guardarPerfil(
                                        userRecord,
                                        request,
                                        rol
                                )
                );
    }

    /**
     * Guarda el perfil del usuario en Firestore.
     */
    private Mono<UsuarioDTO.Response> guardarPerfil(
            UserRecord userRecord,
            UsuarioDTO.CrearRequest request,
            Rol rol) {

        Usuario usuario = new Usuario();

        usuario.setId(userRecord.getUid());
        usuario.setNombre(request.getNombre());
        usuario.setEmail(request.getEmail());
        usuario.setTelefono(
                StringUtils.hasText(request.getTelefono())
                        ? request.getTelefono().trim()
                        : null
        );
        usuario.setRol(rol);
        usuario.setActivo(true);

        return usuarioRepository
                .save(usuario)

                .flatMap(
                        u ->
                                auditService
                                        .registrarActual(
                                                "USUARIO_CREADO",
                                                "usuario",
                                                u.getId(),
                                                u.getEmail()
                                                        + " · rol "
                                                        + u.getRol()
                                        )
                                        .thenReturn(u)
                )

                .map(mapper::toDTO)

                .onErrorResume(
                        e ->
                                revertirCuentaFirebase(
                                        userRecord.getUid(),
                                        e
                                )
                );
    }

    /**
     * Si Firestore falla después de crear Firebase,
     * elimina la cuenta Firebase para evitar usuarios incompletos.
     */
    private Mono<UsuarioDTO.Response> revertirCuentaFirebase(
            String uid,
            Throwable causa) {

        log.error(
                "Fallo al guardar el perfil en Firestore, "
                        + "revirtiendo cuenta de Firebase uid={}",
                uid,
                causa
        );

        return ReactiveFirebase
                .defer(
                        () -> firebaseAuth.deleteUserAsync(uid)
                )

                .onErrorResume(
                        e2 -> Mono.empty()
                )

                .then(
                        Mono.error(
                                new OperacionInvalidaException(
                                        "No se pudo completar el registro. "
                                                + "Intente nuevamente."
                                )
                        )
                );
    }

    /**
     * Obtiene un usuario por ID.
     */
    public Mono<UsuarioDTO.Response> obtenerPorId(String id) {

        return usuarioRepository
                .findById(id)

                .switchIfEmpty(
                        Mono.error(
                                RecursoNoEncontradoException.de(
                                        "Usuario",
                                        id
                                )
                        )
                )

                .map(mapper::toDTO);
    }

    /**
     * Lista todos los usuarios.
     */
    public Flux<UsuarioDTO.Response> listar() {

        return usuarioRepository
                .findAll()
                .map(mapper::toDTO);
    }

    /**
     * Actualiza el perfil de un usuario.
     */
    public Mono<UsuarioDTO.Response> actualizar(
            String id,
            UsuarioDTO.ActualizarRequest request) {

        return usuarioRepository
                .findById(id)

                .switchIfEmpty(
                        Mono.error(
                                RecursoNoEncontradoException.de(
                                        "Usuario",
                                        id
                                )
                        )
                )

                .flatMap(
                        usuario -> {

                            usuario.setNombre(
                                    request.getNombre()
                            );

                            if (StringUtils.hasText(
                                    request.getTelefono())) {

                                usuario.setTelefono(
                                        request.getTelefono()
                                );
                            }

                            return usuarioRepository.save(usuario)
                                    .flatMap(actualizado -> auditService.registrarActual(
                                                    "USUARIO_ACTUALIZADO",
                                                    "usuario",
                                                    actualizado.getId(),
                                                    actualizado.getEmail())
                                            .thenReturn(actualizado));
                        }
                )

                .map(mapper::toDTO);
    }

    /**
     * Baja lógica del usuario.
     *
     * También bloquea el login en Firebase.
     */
    public Mono<Void> desactivar(String id) {

        return usuarioRepository
                .findById(id)

                .switchIfEmpty(
                        Mono.error(
                                RecursoNoEncontradoException.de(
                                        "Usuario",
                                        id
                                )
                        )
                )

                .flatMap(
                        usuario -> {

                            usuario.setActivo(false);

                            return usuarioRepository.save(
                                    usuario
                            );
                        }
                )

                .flatMap(
                        usuario ->
                                ReactiveFirebase.defer(
                                                () ->
                                                        firebaseAuth.updateUserAsync(
                                                                new UserRecord
                                                                        .UpdateRequest(
                                                                        usuario.getId()
                                                                )
                                                                        .setDisabled(true)
                                                        )
                                        )

                                        .then(
                                                auditService.registrarActual(
                                                        "USUARIO_DESACTIVADO",
                                                        "usuario",
                                                        usuario.getId(),
                                                        usuario.getEmail()
                                                )
                                        )
                )

                .then();
    }

    public Mono<Void> reactivar(String id) {
        return usuarioRepository.findById(id)
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Usuario", id)))
                .flatMap(usuario -> ReactiveFirebase.defer(() -> firebaseAuth.updateUserAsync(
                                new UserRecord.UpdateRequest(usuario.getId()).setDisabled(false)))
                        .then(Mono.defer(() -> {
                            usuario.setActivo(true);
                            return usuarioRepository.save(usuario);
                        }))
                        .flatMap(actualizado -> auditService.registrarActual(
                                "USUARIO_REACTIVADO", "usuario", actualizado.getId(), actualizado.getEmail())))
                .then();
    }

    /**
     * Cambia el rol de un usuario.
     */
    public Mono<UsuarioDTO.Response> cambiarRol(
            String id,
            Rol nuevoRol) {

        return usuarioRepository
                .findById(id)

                .switchIfEmpty(
                        Mono.error(
                                RecursoNoEncontradoException.de(
                                        "Usuario",
                                        id
                                )
                        )
                )

                .flatMap(
                        usuario -> {

                            Rol anterior =
                                    usuario.getRol();

                            usuario.setRol(nuevoRol);

                            return usuarioRepository
                                    .save(usuario)

                                    .flatMap(
                                            u ->
                                                    auditService
                                                            .registrarActual(
                                                                    "USUARIO_ROL_CAMBIADO",
                                                                    "usuario",
                                                                    u.getId(),
                                                                    u.getEmail()
                                                                            + ": "
                                                                            + anterior
                                                                            + " -> "
                                                                            + nuevoRol
                                                            )
                                                            .thenReturn(u)
                                    );
                        }
                )

                .map(mapper::toDTO);
    }

    /**
     * Genera un enlace para restablecer contraseña.
     */
    public Mono<UsuarioDTO.ResetLinkResponse>
    generarEnlaceRestablecimiento(String id) {

        return usuarioRepository
                .findById(id)

                .switchIfEmpty(
                        Mono.error(
                                RecursoNoEncontradoException.de(
                                        "Usuario",
                                        id
                                )
                        )
                )

                .flatMap(
                        usuario ->
                                ReactiveFirebase
                                        .<String>defer(
                                                () ->
                                                        firebaseAuth
                                                                .generatePasswordResetLinkAsync(
                                                                        usuario.getEmail()
                                                                )
                                        )

                                        .onErrorMap(
                                                e ->
                                                        new OperacionInvalidaException(
                                                                "No se pudo generar "
                                                                        + "el enlace: "
                                                                        + e.getMessage()
                                                        )
                                        )

                                        .flatMap(
                                                link ->
                                                        auditService
                                                                .registrarActual(
                                                                        "USUARIO_RESET_PASSWORD",
                                                                        "usuario",
                                                                        usuario.getId(),
                                                                        usuario.getEmail()
                                                                )
                                                                .thenReturn(link)
                                        )
                )

                .map(
                        UsuarioDTO.ResetLinkResponse::new
                );
    }
}
