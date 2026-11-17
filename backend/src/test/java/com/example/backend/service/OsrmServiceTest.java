package com.example.backend.service;

import com.example.backend.config.OsrmProperties;
import com.example.backend.dto.UbicacionDTO;
import com.example.backend.exception.ApiException;
import com.example.backend.model.EstadoRepartidor;
import com.example.backend.model.Repartidor;
import com.example.backend.model.Ubicacion;
import com.example.backend.repository.RepartidorRepository;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.IdToken;
import com.google.auth.oauth2.IdTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OsrmServiceTest {

    @Test
    void convierteGeometriaGeoJsonYUnidadesDeRuta() {
        AtomicReference<String> requestPath = new AtomicReference<>();
        OsrmService service = service(HttpStatus.OK, """
                {
                  "code":"Ok",
                  "routes":[{"distance":2500,"duration":600,
                    "geometry":{"coordinates":[[-74.22,-13.16],[-74.215,-13.155],[-74.21,-13.15]]}}]
                }
                """, mock(RepartidorRepository.class), requestPath);

        StepVerifier.create(service.calcularRuta(
                        new UbicacionDTO(-13.16, -74.22),
                        new UbicacionDTO(-13.15, -74.21)
                ))
                .assertNext(route -> {
                    assertEquals(2.5, route.distanciaKm());
                    assertEquals(10.0, route.duracionMinutos());
                    assertEquals(3, route.geometria().size());
                    assertEquals(-13.16, route.geometria().get(0).latitud());
                    assertEquals(-74.22, route.geometria().get(0).longitud());
                    assertEquals(-13.155, route.geometria().get(1).latitud());
                    assertEquals(-74.215, route.geometria().get(1).longitud());
                })
                .verifyComplete();

        org.junit.jupiter.api.Assertions.assertTrue(requestPath.get().contains("overview=full"));
        org.junit.jupiter.api.Assertions.assertTrue(requestPath.get().contains("geometries=geojson"));
    }

    @Test
    void comparaPorTiempoOSRMSinAsignarRepartidores() {
        RepartidorRepository repository = mock(RepartidorRepository.class);
        Repartidor lento = repartidor("lento", -13.16, -74.22);
        Repartidor rapido = repartidor("rapido", -13.17, -74.23);
        when(repository.findByEstado(EstadoRepartidor.DISPONIBLE))
                .thenReturn(reactor.core.publisher.Flux.just(lento, rapido));
        OsrmService service = service(HttpStatus.OK, """
                {"code":"Ok","durations":[[900],[300]],"distances":[[4000],[1200]]}
                """, repository, new AtomicReference<>());

        StepVerifier.create(service.compararRepartidores(new UbicacionDTO(-13.15, -74.21)))
                .assertNext(result -> {
                    assertEquals("rapido", result.repartidorRecomendadoId());
                    assertEquals("rapido", result.repartidores().get(0).repartidorId());
                    assertEquals(5.0, result.repartidores().get(0).duracionMinutos());
                    assertEquals("lento", result.repartidores().get(1).repartidorId());
                })
                .verifyComplete();
    }

    @Test
    void reportaQueNoHayRutaVial() {
        OsrmService service = service(HttpStatus.OK, "{\"code\":\"NoSegment\"}",
                mock(RepartidorRepository.class), new AtomicReference<>());

        StepVerifier.create(service.calcularRuta(
                        new UbicacionDTO(-13.16, -74.22),
                        new UbicacionDTO(-13.15, -74.21)
                ))
                .expectErrorMatches(error -> error instanceof ApiException exception
                        && exception.getStatus() == HttpStatus.NOT_FOUND)
                .verify();
    }

    @Test
    void ocultaLosDetallesDelErrorDePermisosOSRM() {
        OsrmService service = service(HttpStatus.FORBIDDEN, "{\"error\":\"private detail\"}",
                mock(RepartidorRepository.class), new AtomicReference<>());

        StepVerifier.create(service.calcularRuta(
                        new UbicacionDTO(-13.16, -74.22),
                        new UbicacionDTO(-13.15, -74.21)
                ))
                .expectErrorMatches(error -> error instanceof ApiException exception
                        && exception.getStatus() == HttpStatus.BAD_GATEWAY
                        && "El motor OSRM rechazó la solicitud.".equals(exception.getMessage()))
                .verify();
    }

    @Test
    void usaLaURLCanonicaOSRMComoAudienciaDelIdToken() {
        String audience = "https://osrm-ayacucho-test-nbnprskwlq-uc.a.run.app";
        String token = jwt(audience);
        TestIdTokenCredentials credentials = new TestIdTokenCredentials(token);
        AtomicReference<String> authorization = new AtomicReference<>();
        ExchangeFunction exchange = request -> {
            authorization.set(request.headers().getFirst(HttpHeaders.AUTHORIZATION));
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"code":"Ok","routes":[{"distance":1000,"duration":60,
                            "geometry":{"coordinates":[[-74.22,-13.16],[-74.21,-13.15]]}}]}
                            """)
                    .build());
        };
        WebClient webClient = WebClient.builder()
                .baseUrl(audience)
                .exchangeFunction(exchange)
                .build();
        OsrmProperties properties = new OsrmProperties();
        properties.setBaseUrl(audience + "/");
        properties.setIamEnabled(true);
        OsrmService service = new OsrmService(
                webClient, properties, mock(RepartidorRepository.class), credentials
        );

        StepVerifier.create(service.calcularRuta(
                        new UbicacionDTO(-13.16, -74.22),
                        new UbicacionDTO(-13.15, -74.21)
                ))
                .expectNextCount(1)
                .verifyComplete();

        assertEquals(audience, credentials.audience.get());
        assertEquals("Bearer " + token, authorization.get());
    }

    @Test
    void rechazaCoordenadasNoFinitasAntesDeConsultarOSRM() {
        OsrmService service = service(HttpStatus.OK, "{}",
                mock(RepartidorRepository.class), new AtomicReference<>());
        assertThrows(ApiException.class, () -> service.calcularRuta(
                new UbicacionDTO(Double.NaN, -74.22),
                new UbicacionDTO(-13.15, -74.21)
        ));
    }

    private static Repartidor repartidor(String id, double latitude, double longitude) {
        Repartidor repartidor = new Repartidor();
        repartidor.setId(id);
        repartidor.setActivo(true);
        repartidor.setEstado(EstadoRepartidor.DISPONIBLE);
        repartidor.setUbicacionActual(new Ubicacion(latitude, longitude, null));
        return repartidor;
    }

    private static String jwt(String audience) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString(
                "{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(
                ("{\"aud\":\"" + audience + "\",\"exp\":4102444800}").getBytes(StandardCharsets.UTF_8));
        String signature = encoder.encodeToString("signature".getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + "." + signature;
    }

    private static OsrmService service(
            HttpStatus status,
            String body,
            RepartidorRepository repository,
            AtomicReference<String> requestPath
    ) {
        ExchangeFunction exchange = request -> {
            requestPath.set(request.url().toString());
            return Mono.just(ClientResponse.create(status)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(body)
                    .build());
        };
        WebClient webClient = WebClient.builder()
                .baseUrl("http://localhost:5000")
                .exchangeFunction(exchange)
                .build();
        OsrmProperties properties = new OsrmProperties();
        return new OsrmService(webClient, properties, repository, null);
    }

    private static final class TestIdTokenCredentials
            extends GoogleCredentials implements IdTokenProvider {

        private final String token;
        private final AtomicReference<String> audience = new AtomicReference<>();

        private TestIdTokenCredentials(String token) {
            this.token = token;
        }

        @Override
        public IdToken idTokenWithAudience(
                String targetAudience,
                List<IdTokenProvider.Option> options
        ) throws java.io.IOException {
            audience.set(targetAudience);
            return IdToken.create(token);
        }

        @Override
        public AccessToken refreshAccessToken() {
            return new AccessToken("test-access-token", new Date(4102444800000L));
        }
    }
}
