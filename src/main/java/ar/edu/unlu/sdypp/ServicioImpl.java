package ar.edu.unlu.sdypp;

import ar.edu.unlu.sdypp.contrato.*;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

import java.util.List;

/**
 * Los cinco RPC del contrato (CONTRATO.md §2). Mismas firmas que la App Python: por eso
 * las dos son intercambiables detrás del balanceador.
 */
public final class ServicioImpl extends ServicioGrpc.ServicioImplBase {

    private final RepositorioPersonas repositorio;

    public ServicioImpl(RepositorioPersonas repositorio) {
        this.repositorio = repositorio;
    }

    @Override
    public void identidad(IdentidadPedido pedido, StreamObserver<Instancia> respuesta) {
        Instancia.Builder builder = Instancia.newBuilder()
                .setApp(Config.APP)
                .setLenguaje(Config.LENGUAJE)
                .setVersion(Config.VERSION)
                .setMensaje(Config.MENSAJE)
                .setHost(Config.HOST)
                .setArrancado(Config.ARRANCADO);
        for (String[] integrante : Config.EQUIPO) {
            builder.addEquipo(Integrante.newBuilder()
                    .setNombre(integrante[0])
                    .setApellido(integrante[1])
                    .setLegajo(Integer.parseInt(integrante[2]))
                    .build());
        }
        Bitacora.registrar("Identidad", "OK");
        responder(respuesta, builder.build());
    }

    @Override
    public void salud(SaludPedido pedido, StreamObserver<EstadoSalud> respuesta) {
        Bitacora.registrar("Salud", "OK");
        responder(respuesta, EstadoSalud.newBuilder()
                .setStatus(EstadoSalud.Estado.SANO)
                .setApp(Config.APP)
                .setVersion(Config.VERSION)
                .build());
    }

    @Override
    public void echo(PingPedido pedido, StreamObserver<PongRespuesta> respuesta) {
        // En proto3 no se distingue "no mandó ping" de "mandó ping vacío": los dos
        // llegan como "". El contrato trata los dos igual.
        if (pedido.getPing().isEmpty()) {
            fallar(respuesta, Status.INVALID_ARGUMENT, "se requiere el campo ping", "Echo");
            return;
        }
        Bitacora.registrar("Echo", "OK");
        responder(respuesta, PongRespuesta.newBuilder()
                .setPong(pedido.getPing())
                .setServidoPor(Config.APP)
                .setVersion(Config.VERSION)
                .build());
    }

    @Override
    public void listarPersonas(ListarPersonasPedido pedido, StreamObserver<ListaPersonas> respuesta) {
        if (repositorio == null) {
            sinBase(respuesta, "ListarPersonas", "no hay TP_REDIS_URL configurada");
            return;
        }
        List<Persona> personas;
        try {
            personas = repositorio.listar();
        } catch (RepositorioPersonas.BaseNoDisponible e) {
            sinBase(respuesta, "ListarPersonas", String.valueOf(e.getCause()));
            return;
        }
        Bitacora.registrar("ListarPersonas", "OK");
        responder(respuesta, ListaPersonas.newBuilder()
                .setServidoPor(Config.APP)
                .addAllPersonas(personas)
                .build());
    }

    @Override
    public void crearPersona(NuevaPersona pedido, StreamObserver<RespuestaPersona> respuesta) {
        // El orden de los chequeos es contrato (CONTRATO.md §3): ante un mensaje con dos
        // problemas a la vez, las dos implementaciones tienen que devolver el mismo error
        // y no cada una el que detectó primero.
        String nombre = pedido.getNombre().strip();

        if (nombre.isEmpty()) {
            fallar(respuesta, Status.INVALID_ARGUMENT,
                    "se requieren los campos nombre y legajo", "CrearPersona");
            return;
        }
        if (pedido.getLegajo() < Config.LEGAJO_MIN || pedido.getLegajo() > Config.LEGAJO_MAX) {
            fallar(respuesta, Status.INVALID_ARGUMENT, "legajo fuera de rango", "CrearPersona");
            return;
        }
        if (nombre.length() > Config.NOMBRE_MAX) {
            fallar(respuesta, Status.INVALID_ARGUMENT, "nombre inválido", "CrearPersona");
            return;
        }

        if (repositorio == null) {
            sinBase(respuesta, "CrearPersona", "no hay TP_REDIS_URL configurada");
            return;
        }
        Integer id;
        try {
            id = repositorio.crear(nombre, pedido.getLegajo());
        } catch (RepositorioPersonas.BaseNoDisponible e) {
            sinBase(respuesta, "CrearPersona", String.valueOf(e.getCause()));
            return;
        }
        if (id == null) {
            fallar(respuesta, Status.ALREADY_EXISTS, "el legajo ya está registrado", "CrearPersona");
            return;
        }

        Bitacora.registrar("CrearPersona", "OK", id);
        responder(respuesta, RespuestaPersona.newBuilder()
                .setServidoPor(Config.APP)
                .setPersona(Persona.newBuilder()
                        .setId(id)
                        .setNombre(nombre)
                        .setLegajo(pedido.getLegajo())
                        .build())
                .build());
    }

    /** onNext + onCompleted: en un RPC unario la respuesta es un único mensaje. */
    private static <T> void responder(StreamObserver<T> respuesta, T mensaje) {
        respuesta.onNext(mensaje);
        respuesta.onCompleted();
    }

    /** Registra el fallo en la bitácora y corta el RPC con el código del contrato. */
    private static void fallar(StreamObserver<?> respuesta, Status codigo, String mensaje, String rpc) {
        Bitacora.registrar(rpc, codigo.getCode().name());
        StatusRuntimeException error = codigo.withDescription(mensaje).asRuntimeException();
        respuesta.onError(error);
    }

    /**
     * UNAVAILABLE de los RPC de personas. No hay degradación posible: sin base no hay
     * datos. Devolver una lista vacía sería peor que fallar, porque el cliente no podría
     * distinguir "no hay personas cargadas" de "no pude leerlas". Los demás RPC siguen
     * respondiendo normal: no dependen de la base.
     */
    private static void sinBase(StreamObserver<?> respuesta, String rpc, String motivo) {
        System.out.println("[personas] la base no respondió: " + motivo);
        fallar(respuesta, Status.UNAVAILABLE, "base de datos no disponible", rpc);
    }
}
