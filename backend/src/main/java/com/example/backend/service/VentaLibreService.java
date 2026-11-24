package com.example.backend.service;

import com.example.backend.dto.UbicacionDTO;
import com.example.backend.dto.VentaLibreDTO;
import com.example.backend.exception.ApiException;
import com.example.backend.exception.OperacionInvalidaException;
import com.example.backend.exception.RecursoNoEncontradoException;
import com.example.backend.model.Producto;
import com.example.backend.model.Repartidor;
import com.example.backend.model.Vehiculo;
import com.example.backend.model.Ubicacion;
import com.example.backend.model.VentaLibre;
import com.example.backend.repository.ProductoRepository;
import com.example.backend.repository.RepartidorRepository;
import com.example.backend.repository.VehiculoRepository;
import com.example.backend.repository.VentaLibreRepository;
import com.example.backend.security.SecurityContextUtils;
import com.example.backend.security.UsuarioAutenticado;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;


@Service
public class VentaLibreService {
    private final VentaLibreRepository ventaRepository;
    private final RepartidorRepository repartidorRepository;
    private final VehiculoRepository vehiculoRepository;
    private final ProductoRepository productoRepository;

    public VentaLibreService(VentaLibreRepository ventaRepository,
                             RepartidorRepository repartidorRepository,
                             VehiculoRepository vehiculoRepository,
                             ProductoRepository productoRepository) {
        this.ventaRepository = ventaRepository;
        this.repartidorRepository = repartidorRepository;
        this.vehiculoRepository = vehiculoRepository;
        this.productoRepository = productoRepository;
    }

    public Mono<VentaLibreDTO.Response> crear(VentaLibreDTO.CrearRequest request) {
        if (request.getMetodoPago() == null) {
            return Mono.error(new OperacionInvalidaException("La forma de pago es obligatoria."));
        }
        return SecurityContextUtils.usuarioActual()
                .flatMap(actual -> crearParaUsuario(actual, request));
    }

    private Mono<VentaLibreDTO.Response> crearParaUsuario(
            UsuarioAutenticado actual,
            VentaLibreDTO.CrearRequest request
    ) {
        return repartidorRepository.findByUsuarioId(actual.getUid())
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de(
                        "Perfil de repartidor", actual.getUid())))
                .flatMap(repartidor -> vehiculoRepository.findByField("repartidorId", repartidor.getId())
                        .next()
                        .switchIfEmpty(Mono.error(new OperacionInvalidaException(
                                "No tienes un vehículo asignado.")))
                        .flatMap(vehiculo -> productoRepository.findById(request.getProductoId())
                                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de(
                                        "Producto", request.getProductoId())))
                                .flatMap(producto -> crearVenta(
                                        actual, repartidor, vehiculo, producto, request))));
    }

    private Mono<VentaLibreDTO.Response> crearVenta(UsuarioAutenticado actual,
                                                     Repartidor repartidor,
                                                     Vehiculo vehiculo,
                                                     Producto producto,
                                                     VentaLibreDTO.CrearRequest request) {
        if (!vehiculo.isActivo() || !"DISPONIBLE".equals(vehiculo.getEstadoOperativo())) {
            return Mono.error(new OperacionInvalidaException("El vehiculo esta inactivo, en revision o en mantenimiento y no puede vender."));
        }
        if (!producto.isActivo()) {
            return Mono.error(new OperacionInvalidaException("El producto no está activo."));
        }
        String operacionId = "venta-libre-" + request.getOperacionId();
        VentaLibre venta = new VentaLibre();
        venta.setReferenciaComprador(
                request.getReferenciaComprador() == null
                        ? "Tienda no registrada"
                        : request.getReferenciaComprador().trim()
        );
        venta.setRepartidorId(repartidor.getId());
        venta.setProductoId(producto.getId());
        venta.setCantidad(request.getCantidad());
        venta.setPrecioUnitario(producto.getPrecioUnitario());
        venta.setTotal(producto.getPrecioUnitario()
                .multiply(java.math.BigDecimal.valueOf(request.getCantidad())));
        venta.setMetodoPago(request.getMetodoPago());
        venta.setUsuarioResponsableId(actual.getUid());
        if (request.getUbicacionVenta() != null) {
            venta.setUbicacionVenta(new Ubicacion(
                    request.getUbicacionVenta().getLatitud(),
                    request.getUbicacionVenta().getLongitud()
            ));
        }
        return ventaRepository.registrarVenta(
                        operacionId,
                        venta,
                        vehiculo.getId(),
                        producto.getId(),
                        request.getCantidad()
                )
                .onErrorMap(error -> error instanceof IllegalStateException
                                || error instanceof IllegalArgumentException,
                        error -> new OperacionInvalidaException(error.getMessage()))
                .map(this::map);
    }

    public Flux<VentaLibreDTO.Response> misVentas() {
        return SecurityContextUtils.usuarioActual()
                .flatMapMany(actual -> repartidorRepository.findByUsuarioId(actual.getUid())
                        .flatMapMany(repartidor -> ventaRepository.findByField("repartidorId", repartidor.getId())
                                .map(this::map)));
    }

    public Flux<VentaLibreDTO.Response> listarTodas() {
        return ventaRepository.findAll().map(this::map);
    }

    private VentaLibreDTO.Response map(VentaLibre venta) {
        VentaLibreDTO.Response response = new VentaLibreDTO.Response();
        response.setId(venta.getId());
        response.setOperacionId(venta.getOperacionId());
        response.setReferenciaComprador(venta.getReferenciaComprador());
        response.setVehiculoId(venta.getVehiculoId());
        response.setRepartidorId(venta.getRepartidorId());
        response.setProductoId(venta.getProductoId());
        response.setCantidad(venta.getCantidad());
        response.setPrecioUnitario(venta.getPrecioUnitario());
        response.setTotal(venta.getTotal());
        response.setMetodoPago(venta.getMetodoPago());
        if (venta.getUbicacionVenta() != null) {
            response.setUbicacionVenta(new UbicacionDTO(
                    venta.getUbicacionVenta().getLatitud(),
                    venta.getUbicacionVenta().getLongitud()
            ));
        }
        response.setEstado(venta.getEstado());
        response.setCreadoEn(venta.getCreadoEn());
        return response;
    }
}
