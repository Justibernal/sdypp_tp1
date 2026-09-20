package ar.edu.unlu.sdypp;

import ar.edu.unlu.sdypp.contrato.*;
import com.google.protobuf.Message;
import io.grpc.Status;

import java.util.List;

/**
 * Las cinco operaciones del contrato, sin transporte.
 *
 * <p>Existe porque ahora hay <b>dos</b> formas de llegar a la misma operación: el servidor
 * gRPC ({@link ServicioImpl}) y el worker que toma tareas de la cola
 * ({@code ar.edu.unlu.sdypp.worker.Ejecutor}). Si cada uno validara por su cuenta, el mismo
 * alta daría {@code INVALID_ARGUMENT} por un camino y {@code ALREADY_EXISTS} por el otro, y
 * el contrato dejaría de valer apenas cambia el transporte. Con una sola implementación, el
 * orden de los chequeos de CONTRATO.md §3 se escribe una vez y vale para los dos.
 *
 * <p>Acá no se escribe la bitácora: cada transporte registra lo suyo, porque no anotan lo
 * mismo — el servidor anota el nombre del RPC, el worker anota además el id de la tarea que
 * le dio la cola.
 */
public final class Operaciones {

    /**
     * Lo que devuelve una operación: el código del contrato y, si salió bien, el mensaje.
     *
     * <p>El código es un {@link Status.Code} de gRPC y no un enumerado propio aunque una de
     * las dos vías sea HTTP: los códigos son contrato (CONTRATO.md §2) y el servicio de cola
     * ya los usa por nombre — su recuperador publica {@code DEADLINE_EXCEEDED} cuando una
     * reserva vence.
     */
    public static final class Resultado {
        public final Status.Code codigo;
        public final String mensaje;   // descripción del error; "" cuando salió bien
        public final Message valor;    // el mensaje del contrato; null cuando falló
        public final Integer id;       // id de la persona involucrada; null si no aplica
        public final String detalle;   // por qué no respondió la base, para el log; null si no aplica

        private Resultado(Status.Code codigo, String mensaje, Message valor, Integer id, String detalle) {
            this.codigo = codigo;
            this.mensaje = mensaje;
            this.valor = valor;
            this.id = id;
            this.detalle = detalle;
        }

        public boolean fallo() {
            return codigo != Status.Code.OK;
        }

        static Resultado ok(Message valor) {
            return new Resultado(Status.Code.OK, "", valor, null, null);
        }

        static Resultado ok(Message valor, int id) {
            return new Resultado(Status.Code.OK, "", valor, id, null);
        }

        static Resultado error(Status.Code codigo, String mensaje) {
            return new Resultado(codigo, mensaje, null, null, null);
        }

        /**
         * UNAVAILABLE de los RPC de personas. No hay degradación posible: sin base no hay
         * datos. Devolver una lista vacía sería peor que fallar, porque el cliente no podría
         * distinguir "no hay personas cargadas" de "no pude leerlas".
         */
        static Resultado sinBase(String detalle) {
            return new Resultado(Status.Code.UNAVAILABLE, "base de datos no disponible", null, null, detalle);
        }
    }

    private final RepositorioPersonas repositorio;

    public Operaciones(RepositorioPersonas repositorio) {
        this.repositorio = repositorio;
    }

    public Resultado identidad() {
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
        return Resultado.ok(builder.build());
    }

    public Resultado salud() {
        return Resultado.ok(EstadoSalud.newBuilder()
                .setStatus(EstadoSalud.Estado.SANO)
                .setApp(Config.APP)
                .setVersion(Config.VERSION)
                .build());
    }

    public Resultado echo(String ping) {
        // En proto3 no se distingue "no mandó ping" de "mandó ping vacío": los dos llegan
        // como "". El contrato trata los dos igual, y por la cola pasa lo mismo con un
        // campo ausente en el JSON.
        if (ping == null || ping.isEmpty()) {
            return Resultado.error(Status.Code.INVALID_ARGUMENT, "se requiere el campo ping");
        }
        return Resultado.ok(PongRespuesta.newBuilder()
                .setPong(ping)
                .setServidoPor(Config.APP)
                .setVersion(Config.VERSION)
                .build());
    }

    public Resultado listarPersonas() {
        if (repositorio == null) {
            return Resultado.sinBase("no hay TP_REDIS_URL configurada");
        }
        List<Persona> personas;
        try {
            personas = repositorio.listar();
        } catch (RepositorioPersonas.BaseNoDisponible e) {
            return Resultado.sinBase(String.valueOf(e.getCause()));
        }
        return Resultado.ok(ListaPersonas.newBuilder()
                .setServidoPor(Config.APP)
                .addAllPersonas(personas)
                .build());
    }

    /**
     * Alta. El orden de los chequeos es contrato (CONTRATO.md §3): ante un pedido con dos
     * problemas a la vez, las dos implementaciones —y ahora también los dos transportes—
     * tienen que devolver el mismo error y no cada uno el que detectó primero.
     *
     * <p>El {@code nombre} llega ya trimeado desde el transporte, que es quien sabe cómo
     * venía: en gRPC es un string por el tipo, y por la cola puede venir cualquier cosa.
     */
    public Resultado crearPersona(String nombre, int legajo) {
        String limpio = nombre == null ? "" : nombre.strip();

        if (limpio.isEmpty()) {
            return Resultado.error(Status.Code.INVALID_ARGUMENT, "se requieren los campos nombre y legajo");
        }
        if (legajo < Config.LEGAJO_MIN || legajo > Config.LEGAJO_MAX) {
            return Resultado.error(Status.Code.INVALID_ARGUMENT, "legajo fuera de rango");
        }
        if (limpio.length() > Config.NOMBRE_MAX) {
            return Resultado.error(Status.Code.INVALID_ARGUMENT, "nombre inválido");
        }

        if (repositorio == null) {
            return Resultado.sinBase("no hay TP_REDIS_URL configurada");
        }
        Integer id;
        try {
            id = repositorio.crear(limpio, legajo);
        } catch (RepositorioPersonas.BaseNoDisponible e) {
            return Resultado.sinBase(String.valueOf(e.getCause()));
        }
        if (id == null) {
            return Resultado.error(Status.Code.ALREADY_EXISTS, "el legajo ya está registrado");
        }

        return Resultado.ok(RespuestaPersona.newBuilder()
                .setServidoPor(Config.APP)
                .setPersona(Persona.newBuilder()
                        .setId(id)
                        .setNombre(limpio)
                        .setLegajo(legajo)
                        .build())
                .build(), id);
    }
}
