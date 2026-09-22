package ar.edu.unlu.sdypp.worker;

import ar.edu.unlu.sdypp.Config;
import ar.edu.unlu.sdypp.Operaciones;
import ar.edu.unlu.sdypp.contrato.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.protobuf.Message;
import io.grpc.Status;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Traduce entre el JSON de la cola y el contrato, y ejecuta la operación.
 *
 * <p>Es la frontera del sistema: de acá para adentro todo es tipado (los mensajes del
 * .proto) y de acá para afuera es JSON sin tipos. La ejecución no se hace acá — se delega
 * en {@link Operaciones}, la misma clase que usa el servidor gRPC, para que la misma
 * operación dé el mismo código por los dos caminos.
 *
 * <h2>Los trece casos borde vuelven</h2>
 * CONTRATO.md §3 celebra que el tipado de Protobuf eliminó trece casos borde que la v1.3
 * validaba a mano: legajo como string, decimal, booleano, en notación exponencial, mayor a
 * int32… Por la cola viaja JSON, así que <b>vuelven todos</b>: ya no hay stub del cliente
 * que los rechace antes de salir a la red. La traducción de acá abajo los resuelve otra
 * vez, y en un solo lugar.
 *
 * <p>La regla es que la traducción <b>no inventa errores nuevos</b>: un legajo ilegible se
 * convierte en 0, y el 0 ya está fuera del rango {@code 1 … 2147483647} que fija el
 * contrato, así que sale por el mismo {@code INVALID_ARGUMENT · legajo fuera de rango} de
 * siempre. Un nombre que no es string se trata como ausente. Así el orden de los chequeos
 * (§3) se respeta aunque el pedido llegue con dos problemas a la vez.
 */
public final class Ejecutor {

    /** Lo que el worker le devuelve a la cola, más lo que necesita la bitácora. */
    public static final class Resuelta {
        /** Nombre del código gRPC: OK, INVALID_ARGUMENT, ALREADY_EXISTS, UNAVAILABLE… */
        public final String estado;
        public final JsonObject contenido;
        /** Nombre del RPC del contrato: la bitácora dice lo mismo por los dos caminos. */
        public final String rpc;
        /** Id de la persona involucrada, o null. Es el quinto campo de la bitácora (§5). */
        public final Integer id;

        Resuelta(String estado, JsonObject contenido, String rpc, Integer id) {
            this.estado = estado;
            this.contenido = contenido;
            this.rpc = rpc;
            this.id = id;
        }
    }

    /**
     * Los hilos donde corre la operación mientras el consumidor le mira el presupuesto.
     * Son <b>daemon</b> a propósito: uno que se pasó del presupuesto no puede impedir que la
     * JVM salga cuando el worker se apaga.
     */
    private static final ExecutorService RELOJ = Executors.newCachedThreadPool(tarea -> {
        Thread hilo = new Thread(tarea, "presupuesto");
        hilo.setDaemon(true);
        return hilo;
    });

    private final Operaciones operaciones;

    public Ejecutor(Operaciones operaciones) {
        this.operaciones = operaciones;
    }

    public Resuelta ejecutar(Tarea tarea) {
        String rpc = rpcDe(tarea.operacion);

        // Sin presupuesto no se ejecuta: la respuesta no la va a leer nadie y el alta
        // habría escrito en la base para nada. La cola ya descarta las vencidas antes de
        // entregarlas, pero puede vencer entre que la entregó y que este hilo la tomó.
        if (tarea.vencida()) {
            return new Resuelta(Status.Code.DEADLINE_EXCEEDED.name(),
                    error("la tarea venció antes de ejecutarse"), rpc, null);
        }

        JsonObject parametros = tarea.parametros;
        switch (rpc) {
            case "Identidad":
                return conPresupuesto(tarea, rpc, operaciones::identidad);
            case "Salud":
                return conPresupuesto(tarea, rpc, operaciones::salud);
            case "Echo":
                return conPresupuesto(tarea, rpc,
                        () -> operaciones.echo(textoDe(parametros, "ping")));
            case "ListarPersonas":
                return conPresupuesto(tarea, rpc, operaciones::listarPersonas);
            case "CrearPersona":
                return conPresupuesto(tarea, rpc, () -> operaciones.crearPersona(
                        textoDe(parametros, "nombre"), legajoDe(parametros)));
            default:
                // UNIMPLEMENTED y no un error genérico: le dice al equipo de la cola que el
                // pedido llegó bien y que el problema está en el catálogo de operaciones,
                // no en la red ni en los datos. Reintentarlo no va a cambiar nada.
                return new Resuelta(Status.Code.UNIMPLEMENTED.name(),
                        error("operación no reconocida: " + tarea.operacion), rpc, null);
        }
    }

    /**
     * Ejecuta la operación sin pasarse de {@code quedaMs}.
     *
     * <p>El presupuesto lo calcula la cola con <b>su</b> reloj y viaja como una duración, no
     * como un instante: los relojes de las casas no están sincronizados, y una máquina
     * adelantada dos segundos descartaría pedidos vivos si comparara contra un {@code
     * vence_en}.
     *
     * <p>Es un límite a la <b>espera</b>, no una interrupción: la operación que se pasó sigue
     * corriendo hasta terminar. No se la corta a propósito — cortarle el hilo a un alta a
     * mitad de camino dejaría la base a medias, que es peor que una respuesta tardía. Lo que
     * se corta es el worker esperándola: pasado el presupuesto la respuesta ya no le sirve a
     * nadie, y este consumidor tiene que volver a tomar trabajo en vez de quedarse colgado.
     *
     * <p>Con las operaciones de este servicio el caso no debería darse nunca —son lecturas y
     * un script Lua contra Redis, con el pool cortando a 1 s— así que esto es una red, no un
     * camino habitual. Existe porque el contrato lo pide y porque una base lenta no tiene por
     * qué dejar a un consumidor fuera de combate.
     */
    private Resuelta conPresupuesto(Tarea tarea, String rpc,
                                    Supplier<Operaciones.Resultado> operacion) {
        if (tarea.quedaMs == Tarea.SIN_PRESUPUESTO) {
            return traducir(rpc, operacion.get());
        }
        Future<Operaciones.Resultado> enCurso = RELOJ.submit(operacion::get);
        try {
            return traducir(rpc, enCurso.get(tarea.quedaMs, TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            System.out.println("[worker] la " + tarea + " se pasó de los " + tarea.quedaMs
                    + "ms de presupuesto: la respuesta ya no le sirve a nadie");
            return new Resuelta(Status.Code.DEADLINE_EXCEEDED.name(),
                    error("la operación no terminó dentro del presupuesto del pedido"), rpc, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Resuelta(Status.Code.UNAVAILABLE.name(),
                    error("el worker se está apagando"), rpc, null);
        } catch (ExecutionException e) {
            // La operación explotó. Se propaga como venía: el que la envuelve en un código
            // del contrato es Worker, que ya sabe no matar al consumidor por esto.
            Throwable causa = e.getCause();
            if (causa instanceof RuntimeException) {
                throw (RuntimeException) causa;
            }
            throw new IllegalStateException(causa);
        }
    }

    /** El resultado del contrato, pasado a JSON. */
    private static Resuelta traducir(String rpc, Operaciones.Resultado resultado) {
        if (resultado.fallo()) {
            if (resultado.detalle != null) {
                System.out.println("[personas] la base no respondió: " + resultado.detalle);
            }
            return new Resuelta(resultado.codigo.name(), error(resultado.mensaje), rpc, null);
        }
        return new Resuelta("OK", contenidoDe(resultado.valor), rpc, resultado.id);
    }

    // --- operacion -> RPC del contrato -------------------------------------------------

    /**
     * La cola nombra las operaciones como la request HTTP que las originó
     * ({@code "POST /personas"}), porque es lo que recibe el balanceador. El contrato las
     * nombra como RPC ({@code CrearPersona}). La tabla de equivalencia es la misma de la
     * v1.3 → v2.0, que el .proto documenta método por método.
     *
     * <p>Se aceptan también los nombres de RPC pelados: el catálogo de operaciones todavía
     * no está cerrado y no cuesta nada entender los dos. Lo que no se reconoce sale por
     * UNIMPLEMENTED, nunca por una excepción.
     */
    static String rpcDe(String operacion) {
        if (operacion == null) {
            return "desconocida";
        }
        String limpio = operacion.trim().replaceAll("\\s+", " ");
        String verbo = "";
        String ruta = limpio;
        int espacio = limpio.indexOf(' ');
        if (espacio > 0) {
            verbo = limpio.substring(0, espacio).toUpperCase();
            ruta = limpio.substring(espacio + 1);
        }
        ruta = ruta.toLowerCase();
        if (ruta.length() > 1 && ruta.endsWith("/")) {
            ruta = ruta.substring(0, ruta.length() - 1);
        }

        switch (verbo + " " + ruta) {
            case "GET /":
            case "GET ":
                return "Identidad";
            case "GET /health":
            case "GET /salud":
                return "Salud";
            case "POST /echo":
                return "Echo";
            case "GET /personas":
                return "ListarPersonas";
            case "POST /personas":
                return "CrearPersona";
            default:
                break;
        }
        switch (limpio.toLowerCase()) {
            case "identidad":
                return "Identidad";
            case "salud":
            case "health":
                return "Salud";
            case "echo":
                return "Echo";
            case "listarpersonas":
                return "ListarPersonas";
            case "crearpersona":
                return "CrearPersona";
            default:
                return "desconocida";
        }
    }

    // --- parametros (JSON sin tipos) -> tipos del contrato -----------------------------

    /**
     * Un campo de texto. Si no es un string JSON se trata como ausente: un {@code nombre}
     * que llega como número o como objeto no es un nombre, y convertirlo a "42" guardaría
     * en la base algo que el cliente nunca pidió.
     */
    private static String textoDe(JsonObject parametros, String clave) {
        JsonElement e = parametros.get(clave);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            return null;
        }
        return e.getAsString();
    }

    /**
     * El legajo como int32. Devuelve <b>0</b> cuando no se puede: ausente, decimal,
     * booleano, en notación exponencial, con letras, o más grande que int32. El 0 ya está
     * fuera del rango que fija el contrato, así que los seis casos salen por el mismo
     * {@code legajo fuera de rango} — que es exactamente lo que la matriz de verificación
     * de CONTRATO.md §3 espera para "sin legajo", "legajo:0" y "legajo:-5".
     *
     * <p>Se acepta el legajo como string ({@code "100200"}) porque un formulario HTML manda
     * todo como texto y el balanceador reenvía lo que le llega. No se acepta {@code "100.0"}
     * ni {@code 1e5}: en el camino a int32 uno pierde información y el otro la gana.
     */
    private static int legajoDe(JsonObject parametros) {
        JsonElement e = parametros.get("legajo");
        if (e == null || !e.isJsonPrimitive()) {
            return 0;
        }
        JsonPrimitive primitivo = e.getAsJsonPrimitive();
        if (primitivo.isBoolean()) {
            return 0;
        }
        String texto = primitivo.getAsString().trim();
        if (!texto.matches("[+-]?\\d+")) {
            return 0;
        }
        try {
            return Integer.parseInt(texto);
        } catch (NumberFormatException ex) {
            return 0; // más grande que int32: fuera de rango, que es lo que hay que decir
        }
    }

    // --- mensajes del contrato -> JSON -------------------------------------------------

    /**
     * Los nombres de los campos van en <b>camelCase</b>: {@code servidoPor}, no
     * {@code servido_por}.
     *
     * <p>No es una preferencia de estilo: este objeto es <b>exactamente</b> lo que el
     * balanceador le publica al cliente final, sin tocar nada — el contrato público lo
     * documenta campo por campo. Mandar los nombres del {@code .proto} tal cual haría que la
     * misma operación se viera distinta según qué réplica la atendió, que es justo lo que las
     * dos implementaciones existen para evitar. El {@code .proto} sigue mandando adentro; la
     * conversión a camelCase pasa acá, en la frontera, y en un solo lugar.
     *
     * <p>{@code servidoPor} lo lleva también {@code Identidad}, aunque el mensaje del
     * {@code .proto} no tenga ese campo: el contrato público lo lista y sale de lo mismo que
     * los demás.
     */
    private static JsonObject contenidoDe(Message mensaje) {
        JsonObject o = new JsonObject();
        if (mensaje instanceof Instancia) {
            Instancia i = (Instancia) mensaje;
            o.addProperty("app", i.getApp());
            o.addProperty("lenguaje", i.getLenguaje());
            JsonArray equipo = new JsonArray();
            for (Integrante integrante : i.getEquipoList()) {
                JsonObject miembro = new JsonObject();
                miembro.addProperty("nombre", integrante.getNombre());
                miembro.addProperty("apellido", integrante.getApellido());
                miembro.addProperty("legajo", integrante.getLegajo());
                equipo.add(miembro);
            }
            o.add("equipo", equipo);
            o.addProperty("version", i.getVersion());
            o.addProperty("mensaje", i.getMensaje());
            o.addProperty("host", i.getHost());
            o.addProperty("arrancado", i.getArrancado());
            o.addProperty("servidoPor", Config.APP);
        } else if (mensaje instanceof EstadoSalud) {
            EstadoSalud s = (EstadoSalud) mensaje;
            // El nombre del enumerado y no su número: "SANO" es legible en el sobre y no se
            // confunde con el 0, que en proto3 es el valor "no especificado".
            o.addProperty("status", s.getStatus().name());
            o.addProperty("app", s.getApp());
            o.addProperty("version", s.getVersion());
        } else if (mensaje instanceof PongRespuesta) {
            PongRespuesta p = (PongRespuesta) mensaje;
            o.addProperty("pong", p.getPong());
            o.addProperty("servidoPor", p.getServidoPor());
            o.addProperty("version", p.getVersion());
        } else if (mensaje instanceof ListaPersonas) {
            ListaPersonas l = (ListaPersonas) mensaje;
            o.addProperty("servidoPor", l.getServidoPor());
            JsonArray personas = new JsonArray();
            for (Persona p : l.getPersonasList()) {
                personas.add(dePersona(p));
            }
            o.add("personas", personas);
        } else if (mensaje instanceof RespuestaPersona) {
            RespuestaPersona r = (RespuestaPersona) mensaje;
            o.addProperty("servidoPor", r.getServidoPor());
            o.add("persona", dePersona(r.getPersona()));
        }
        return o;
    }

    private static JsonObject dePersona(Persona p) {
        JsonObject o = new JsonObject();
        o.addProperty("id", p.getId());
        o.addProperty("nombre", p.getNombre());
        o.addProperty("legajo", p.getLegajo());
        return o;
    }

    /**
     * El cuerpo de un fallo. Un solo campo {@code error} con el mensaje del contrato, que es
     * la forma que ya usa la cola cuando falla por su cuenta: su recuperador publica
     * {@code {"error": detalle}} al vencer una reserva. El código va afuera, en
     * {@code estado}.
     */
    private static JsonObject error(String mensaje) {
        JsonObject o = new JsonObject();
        o.addProperty("error", mensaje);
        o.addProperty("app", Config.APP);
        return o;
    }
}
