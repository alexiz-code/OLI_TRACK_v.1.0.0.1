package com.example.backend.service;

import com.example.backend.dto.ConfiguracionDTO;
import com.example.backend.model.ConfiguracionEmpresa;
import com.example.backend.model.MetodoPago;
import com.example.backend.repository.ConfiguracionEmpresaRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/** Configuración de la empresa (HU-009, HU-019, HU-020, HU-084): un único documento con id "empresa". */
@Service
public class ConfiguracionService {

    private static final String ID = "empresa";

    private final ConfiguracionEmpresaRepository repository;
    private final AuditService auditService;

    public ConfiguracionService(ConfiguracionEmpresaRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    /** Devuelve la configuración guardada o, si aún no existe, los valores por defecto. */
    public Mono<ConfiguracionEmpresa> obtener() {
        return repository.findById(ID).defaultIfEmpty(porDefecto());
    }

    public Mono<ConfiguracionDTO.Datos> obtenerDatos() {
        return obtener().map(this::aDatos);
    }

    public Mono<ConfiguracionDTO.Datos> actualizar(ConfiguracionDTO.Datos datos) {
        ConfiguracionEmpresa c = new ConfiguracionEmpresa();
        c.setId(ID);
        c.setNombre(datos.getNombre());
        c.setRuc(datos.getRuc());
        c.setDireccionCentral(datos.getDireccionCentral());
        c.setTelefono(datos.getTelefono());
        c.setCorreo(datos.getCorreo());
        c.setHorarioApertura(datos.getHorarioApertura());
        c.setHorarioCierre(datos.getHorarioCierre());
        c.setDiasAtencion(datos.getDiasAtencion());
        c.setValidarHorarioAtencion(datos.isValidarHorarioAtencion());
        c.setLatitudCentral(datos.getLatitudCentral());
        c.setLongitudCentral(datos.getLongitudCentral());
        c.setMetodosPagoHabilitados(metodosPagoPermitidos(datos.getMetodosPagoHabilitados()));
        c.setTiempoEstimadoEntregaMinutos(datos.getTiempoEstimadoEntregaMinutos());
        c.setUmbralRetrasoMinutos(datos.getUmbralRetrasoMinutos());
        c.setUmbralSinAsignarMinutos(datos.getUmbralSinAsignarMinutos());

        return repository.save(c)
                .flatMap(guardada -> auditService
                        .registrarActual("CONFIGURACION_ACTUALIZADA", "configuracion", ID, "Se actualizó la configuración de la empresa")
                        .thenReturn(guardada))
                .map(this::aDatos);
    }

    private ConfiguracionDTO.Datos aDatos(ConfiguracionEmpresa c) {
        return new ConfiguracionDTO.Datos(
                c.getNombre(), c.getRuc(), c.getDireccionCentral(), c.getTelefono(), c.getCorreo(),
                c.getHorarioApertura(), c.getHorarioCierre(), c.getDiasAtencion(), c.isValidarHorarioAtencion(),
                c.getLatitudCentral(), c.getLongitudCentral(), metodosPagoPermitidos(c.getMetodosPagoHabilitados()),
                c.getTiempoEstimadoEntregaMinutos(), c.getUmbralRetrasoMinutos(), c.getUmbralSinAsignarMinutos());
    }

    private List<MetodoPago> metodosPagoPermitidos(List<MetodoPago> metodos) {
        if (metodos == null) {
            return List.of();
        }
        return metodos.stream()
                .filter(metodo -> metodo == MetodoPago.EFECTIVO
                        || metodo == MetodoPago.YAPE
                        || metodo == MetodoPago.PLIN)
                .distinct()
                .toList();
    }

    private ConfiguracionEmpresa porDefecto() {
        ConfiguracionEmpresa c = new ConfiguracionEmpresa();
        c.setId(ID);
        c.setNombre("OLI FRESH ICE");
        c.setRuc("");
        c.setDireccionCentral("Ayacucho, Perú");
        c.setTelefono("");
        c.setCorreo("");
        c.setHorarioApertura("06:30");
        c.setHorarioCierre("22:00");
        c.setDiasAtencion("Lunes a Domingo");
        c.setValidarHorarioAtencion(false);
        c.setLatitudCentral(-13.181655);
        c.setLongitudCentral(-74.210656);
        c.setMetodosPagoHabilitados(new ArrayList<>(List.of(
                MetodoPago.EFECTIVO,
                MetodoPago.YAPE,
                MetodoPago.PLIN
        )));
        c.setTiempoEstimadoEntregaMinutos(30);
        c.setUmbralRetrasoMinutos(60);
        c.setUmbralSinAsignarMinutos(10);
        return c;
    }
}
