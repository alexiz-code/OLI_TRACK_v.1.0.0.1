package com.example.backend.service;

import com.example.backend.config.OsrmProperties;
import com.example.backend.dto.CompararRepartidoresDTO;
import com.example.backend.dto.RutaDTO;
import com.example.backend.dto.UbicacionDTO;
import com.example.backend.exception.ApiException;
import com.example.backend.model.EstadoRepartidor;
import com.example.backend.model.Repartidor;
import com.example.backend.model.Ubicacion;
import com.example.backend.repository.RepartidorRepository;
import com.example.backend.repository.VehiculoRepository;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.IdTokenCredentials;
import com.google.auth.oauth2.IdTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class OsrmService {

    private static final Logger log = LoggerFactory.getLogger(OsrmService.class);

    private final WebClient webClient;
    private final OsrmProperties properties;
    private final RepartidorRepository repartidorRepository;
    private final VehiculoRepository vehiculoRepository;
    private final IdTokenCredentials idTokenCredentials;

    public OsrmService(
            WebClient osrmWebClient,
            OsrmProperties properties,
            RepartidorRepository repartidorRepository,
            GoogleCredentials googleCredentials
    ) {
        this(osrmWebClient, properties, repartidorRepository, googleCredentials, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OsrmService(WebClient osrmWebClient, OsrmProperties properties, RepartidorRepository repartidorRepository,
                       GoogleCredentials googleCredentials, VehiculoRepository vehiculoRepository) {
        this.webClient = osrmWebClient;
        this.properties = properties;
        this.repartidorRepository = repartidorRepository;
        this.vehiculoRepository = vehiculoRepository;
        this.idTokenCredentials = createIdTokenCredentials(properties, googleCredentials);
    }

    public Mono<RutaDTO> calcularRuta(UbicacionDTO origen, UbicacionDTO destino) {
        validarCoordenadas(origen);
        validarCoordenadas(destino);

        String coordinates = punto(origen) + ";" + punto(destino);
        String path = "/route/v1/driving/" + coordinates
                + "?overview=full&geometries=geojson&steps=false";

        return get(path).map(response -> {
            validarRespuestaOk(response, "No existe una ruta entre estos puntos.");
            JsonNode route = response.path("routes").path(0);
            JsonNode geometry = route.path("geometry").path("coordinates");
            if (!route.isObject() || !geometry.isArray() || geometry.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "OSRM devolvió una ruta incompleta.");
            }

            List<RutaDTO.CoordenadaDTO> points = new ArrayList<>(geometry.size());
            for (JsonNode coordinate : geometry) {
                if (!coordinate.isArray() || coordinate.size() < 2
                        || !coordinate.get(0).isNumber() || !coordinate.get(1).isNumber()) {
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "OSRM devolvió una geometría inválida.");
                }
                points.add(new RutaDTO.CoordenadaDTO(
                        coordinate.get(1).asDouble(),
                        coordinate.get(0).asDouble()
                ));
            }

            return new RutaDTO(
                    "OK",
                    route.path("distance").asDouble() / 1000.0,
                    route.path("duration").asDouble() / 60.0,
                    List.copyOf(points),
                    null
            );
        });
    }

    public Mono<CompararRepartidoresDTO> compararRepartidores(UbicacionDTO destino) {
        validarCoordenadas(destino);

        return repartidorRepository.findByEstado(EstadoRepartidor.DISPONIBLE)
                .filter(Repartidor::isActivo)
                .filter(repartidor -> coordenadasValidas(repartidor.getUbicacionActual()))
                .filterWhen(repartidor -> {
                    if (vehiculoRepository == null) return Mono.just(true);
                    if (repartidor.getVehiculoId() == null) return Mono.just(false);
                    return vehiculoRepository.findById(repartidor.getVehiculoId())
                            .map(vehiculo -> vehiculo.isActivo() && "DISPONIBLE".equals(vehiculo.getEstadoOperativo()))
                            .defaultIfEmpty(false);
                })
                .take((long) properties.getMaxTableCandidates() + 1)
                .collectList()
                .flatMap(repartidores -> {
                    if (repartidores.isEmpty()) {
                        return Mono.just(new CompararRepartidoresDTO("SIN_REPARTIDORES", null, List.of()));
                    }
                    if (repartidores.size() > properties.getMaxTableCandidates()) {
                        return Mono.error(new ApiException(
                                HttpStatus.PAYLOAD_TOO_LARGE,
                                "Hay demasiados repartidores disponibles para una sola comparación."
                        ));
                    }

                    String origins = repartidores.stream()
                            .map(repartidor -> punto(repartidor.getUbicacionActual()))
                            .reduce((first, second) -> first + ";" + second)
                            .orElseThrow();
                    String sources = IntStream.range(0, repartidores.size())
                            .mapToObj(Integer::toString)
                            .collect(Collectors.joining(";"));
                    String path = "/table/v1/driving/" + origins + ";" + punto(destino)
                            + "?sources=" + sources
                            + "&destinations=" + repartidores.size()
                            + "&annotations=duration,distance";

                    return get(path).map(response -> convertirComparacion(response, repartidores));
                });
    }

    private CompararRepartidoresDTO convertirComparacion(JsonNode response, List<Repartidor> repartidores) {
        validarRespuestaOk(response, "OSRM no pudo comparar los repartidores.");
        JsonNode durations = response.path("durations");
        JsonNode distances = response.path("distances");
        if (!durations.isArray() || durations.size() != repartidores.size()
                || !distances.isArray() || distances.size() != repartidores.size()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "OSRM devolvió una matriz de tiempos inválida.");
        }

        List<CompararRepartidoresDTO.ResultadoRepartidorDTO> results = new ArrayList<>();
        for (int index = 0; index < repartidores.size(); index++) {
            JsonNode duration = durations.path(index).path(0);
            JsonNode distance = distances.path(index).path(0);
            boolean reachable = duration.isNumber() && distance.isNumber();
            results.add(new CompararRepartidoresDTO.ResultadoRepartidorDTO(
                    repartidores.get(index).getId(),
                    reachable ? "OK" : "SIN_RUTA",
                    reachable ? distance.asDouble() / 1000.0 : null,
                    reachable ? duration.asDouble() / 60.0 : null
            ));
        }

        results.sort(Comparator.comparing(
                CompararRepartidoresDTO.ResultadoRepartidorDTO::duracionMinutos,
                Comparator.nullsLast(Comparator.naturalOrder())
        ));
        String recommendation = results.stream()
                .filter(result -> "OK".equals(result.estado()))
                .map(CompararRepartidoresDTO.ResultadoRepartidorDTO::repartidorId)
                .findFirst()
                .orElse(null);

        return new CompararRepartidoresDTO(
                recommendation == null ? "SIN_RUTAS" : "OK",
                recommendation,
                List.copyOf(results)
        );
    }

    private Mono<JsonNode> get(String path) {
        WebClient.RequestHeadersSpec<?> request = webClient.get().uri(path);
        Mono<JsonNode> response;
        if (idTokenCredentials == null) {
            response = enviar(request);
        } else {
            response = Mono.fromCallable(() -> {
                        idTokenCredentials.refreshIfExpired();
                        return idTokenCredentials.getAccessToken().getTokenValue();
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(token -> enviar(request.headers(headers -> headers.setBearerAuth(token))));
        }

        return response
                .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                .onErrorMap(TimeoutException.class, error ->
                        new ApiException(HttpStatus.GATEWAY_TIMEOUT, "OSRM tardó demasiado en responder."))
                .onErrorMap(error -> !(error instanceof ApiException), error -> {
                    log.warn("No se pudo completar una solicitud al motor OSRM.", error);
                    return new ApiException(HttpStatus.BAD_GATEWAY, "El motor OSRM no está disponible.");
                });
    }

    private Mono<JsonNode> enviar(WebClient.RequestHeadersSpec<?> request) {
        return request.retrieve()
                .onStatus(HttpStatusCode::isError, response -> {
                    int status = response.statusCode().value();
                    log.warn("OSRM rechazó la solicitud con HTTP {}.", status);
                    return Mono.error(new ApiException(
                            HttpStatus.BAD_GATEWAY,
                            "El motor OSRM rechazó la solicitud."
                    ));
                })
                .bodyToMono(JsonNode.class)
                .switchIfEmpty(Mono.error(new ApiException(
                        HttpStatus.BAD_GATEWAY,
                        "El motor OSRM devolvió una respuesta vacía."
                )));
    }

    private void validarRespuestaOk(JsonNode response, String noRouteMessage) {
        String code = response.path("code").asText();
        if ("NoRoute".equals(code) || "NoTable".equals(code) || "NoSegment".equals(code)) {
            throw new ApiException(HttpStatus.NOT_FOUND, noRouteMessage);
        }
        if (!"Ok".equals(code)) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "OSRM devolvió un error al calcular la ruta.");
        }
    }

    private static IdTokenCredentials createIdTokenCredentials(
            OsrmProperties properties,
            GoogleCredentials credentials
    ) {
        if (!properties.isIamEnabled()) {
            return null;
        }
        URI serviceUri = URI.create(properties.getBaseUrl());
        if (!"https".equalsIgnoreCase(serviceUri.getScheme())
                || serviceUri.getHost() == null
                || (serviceUri.getRawPath() != null
                        && !serviceUri.getRawPath().isEmpty()
                        && !"/".equals(serviceUri.getRawPath()))
                || serviceUri.getRawQuery() != null
                || serviceUri.getRawFragment() != null) {
            throw new IllegalStateException(
                    "OSRM_BASE_URL debe ser una URL HTTPS válida cuando OSRM_IAM_ENABLED=true."
            );
        }
        if (!(credentials instanceof IdTokenProvider provider)) {
            throw new IllegalStateException(
                    "Las credenciales ADC no pueden emitir ID tokens para OSRM; usa la identidad de runtime de Cloud Run."
            );
        }
        return IdTokenCredentials.newBuilder()
                .setIdTokenProvider(provider)
                .setTargetAudience(properties.getBaseUrl().replaceAll("/+$", ""))
                .build();
    }

    private static void validarCoordenadas(UbicacionDTO location) {
        if (location == null || !coordenadasValidas(location.getLatitud(), location.getLongitud())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Las coordenadas no son válidas.");
        }
    }

    private static boolean coordenadasValidas(Ubicacion location) {
        return location != null && coordenadasValidas(location.getLatitud(), location.getLongitud());
    }

    private static boolean coordenadasValidas(Double latitude, Double longitude) {
        return latitude != null && longitude != null
                && Double.isFinite(latitude) && Double.isFinite(longitude)
                && latitude >= -90 && latitude <= 90
                && longitude >= -180 && longitude <= 180;
    }

    private static String punto(UbicacionDTO point) {
        return point.getLongitud() + "," + point.getLatitud();
    }

    private static String punto(Ubicacion point) {
        return point.getLongitud() + "," + point.getLatitud();
    }
}
