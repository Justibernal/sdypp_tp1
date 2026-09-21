package ar.edu.unlu.sdypp.planb;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * PLAN B — un doble de la cola, para probar y demostrar el worker sin el servicio del otro
 * equipo.
 *
 * <p><b>Esto no es la cola del sistema.</b> La cola la hace el equipo que la tiene asignada;
 * esto es lo mínimo que hace falta para que el worker tenga contra qué hablar mientras esa
 * pieza no está publicada, y para poder probar los casos feos —la reserva que vence, la
 * respuesta que llega segunda, la cola que se cae— que con el servicio real no se pueden
 * provocar a voluntad.
 *
 * <p>Implementa la misma semántica que el módulo de colas del otro equipo, que es lo único
 * que lo hace útil: FIFO con <b>reserva</b>, devolución al frente, presupuesto por pedido, y
 * la regla de reintento — al vencer una reserva, lo idempotente se reencola y la escritura
 * se falla con {@code DEADLINE_EXCEEDED}, porque "no contestó" no dice si alcanzó a
 * ejecutarse.
 *
 * <p><b>Habla el contrato real</b> (contrato del worker v1): las mismas rutas, los mismos
 * códigos y los mismos cuerpos que el servicio del otro equipo. Si hablara otra cosa no
 * serviría para lo único que tiene que servir — probar que el worker cumple el contrato —,
 * y la prueba pasaría en verde mientras el worker no anda contra la cola de verdad.
 *
 * <pre>
 *   java -cp target/app-java.jar ar.edu.unlu.sdypp.planb.ColaFalsa 8000
 *
 *   # publicar tareas y recolectar, como haría el balanceador
 *   java -cp target/app-java.jar ar.edu.unlu.sdypp.planb.ColaFalsa publicar http://localhost:8000 20
 *
 *   curl -s localhost:8000/health
 *   curl -s -H "X-Cola-Token: $COLA_TOKEN" localhost:8000/estado
 *
 *   # un nodo que NO es master, para probar que el worker sigue el 421:
 *   COLA_FALSA_MASTER=http://localhost:8000 java -cp target/app-java.jar  *       ar.edu.unlu.sdypp.planb.ColaFalsa 8001
 * </pre>
 */
public final class ColaFalsa {

    /** Segundos que un worker tiene para contestar antes de que el pedido se reasigne. */
    private static final long RESERVA_MS = 10_000;

    /** Presupuesto de un pedido recién publicado, si el publicador no dice otro. */
    private static final long PRESUPUESTO_MS = 30_000;

    /** Versión del contrato que declara este doble. Tiene que ser la del servicio real. */
    private static final String CONTRATO = "1.0";

    /**
     * Respuestas sin recolectar por destinatario antes de contestar
     * {@code 409 destinatario-saturado}. Existe para poder provocar ese caso a voluntad:
     * con el servicio real hay que tirar abajo un balanceador para verlo.
     */
    private static final int COTA_RESPUESTAS =
            Integer.parseInt(System.getenv().getOrDefault("COLA_FALSA_COTA_RESPUESTAS", "1000"));

    /**
     * Los mismos tres tokens que maneja el servicio real, para que el worker que manda el
     * token equivocado se coma el 403 acá y no en la demo. Vacío = no se exige.
     */
    private static final String TOKEN = System.getenv().getOrDefault("COLA_TOKEN", "");
    private static final String TOKEN_PUBLICADOR =
            System.getenv().getOrDefault("COLA_TOKEN_PUBLICADOR", TOKEN);
    private static final String TOKEN_CONSUMIDOR =
            System.getenv().getOrDefault("COLA_TOKEN_CONSUMIDOR", TOKEN);

    /**
     * Si está seteada, este nodo se comporta como <b>slave</b>: contesta 421 en todas las
     * rutas de datos apuntando a esa URL. Es lo que permite probar el redirect del worker
     * sin levantar el clúster entero.
     */
    private static final String MASTER_AJENO = System.getenv().getOrDefault("COLA_FALSA_MASTER", "");

    private static final class Pedido {
        final String id = UUID.randomUUID().toString().substring(0, 8);
        String operacion;
        JsonObject parametros = new JsonObject();
        boolean idempotente;
        String destinatario;
        String cliente;
        long venceEn;
        final long encoladoEn = ahora();
        final List<String> intentos = new ArrayList<>();
        String reservadoPor;
        long reservadoHasta;

        long queda() {
            return venceEn - ahora();
        }

        JsonObject comoJson() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("operacion", operacion);
            o.add("parametros", parametros);
            o.addProperty("idempotente", idempotente);
            o.addProperty("cliente", cliente);
            o.addProperty("quedaMs", Math.max(queda(), 0));
            o.addProperty("intento", intentos.size());
            return o;
        }
    }

    private static final Object CANDADO = new Object();
    private static final Deque<Pedido> ESPERANDO = new ArrayDeque<>();
    private static final Map<String, Pedido> EN_VUELO = new HashMap<>();
    private static final Map<String, Deque<JsonObject>> RESPUESTAS = new HashMap<>();
    private static final Map<String, Long> VISTOS = new HashMap<>();
    private static long publicados = 0;
    private static long reasignados = 0;
    private static long respondidos = 0;
    private static long descartados = 0;

    private ColaFalsa() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("publicar")) {
            publicar(args);
            return;
        }

        int puerto = args.length > 0 ? Integer.parseInt(args[0]) : 8000;

        HttpServer servidor = HttpServer.create(new InetSocketAddress(puerto), 0);
        // Pool y no el executor por defecto (que atiende de a uno): los GET del worker son
        // de long-polling y se quedan colgados hasta veinte segundos. Con un solo hilo, un
        // worker esperando trabajo bloquearía al balanceador que viene a publicar — y la
        // cola se trabaría sola.
        servidor.setExecutor(Executors.newFixedThreadPool(16));

        // Las rutas son las del contrato y no una invencion nuestra: es lo unico que hace
        // que probar contra este doble signifique algo. HttpServer resuelve por prefijo mas
        // largo, asi que "/pedidos" atiende tambien "/pedidos/tomar" y el despacho fino se
        // hace adentro, por ruta exacta.
        servidor.createContext("/pedidos", ColaFalsa::pedidosHandler);
        servidor.createContext("/respuestas", ColaFalsa::respuestasHandler);
        servidor.createContext("/health", ColaFalsa::saludHandler);
        servidor.createContext("/estado", ColaFalsa::estadoHandler);
        servidor.start();

        // El recuperador: sin esto, el pedido de un worker que se murió se queda en vuelo
        // para siempre. Es lo que reemplaza al aviso que el worker ya no puede dar.
        Executors.newSingleThreadScheduledExecutor(hilo -> {
            Thread t = new Thread(hilo, "recuperador");
            t.setDaemon(true);
            return t;
        }).scheduleWithFixedDelay(ColaFalsa::recuperar, 1, 1, TimeUnit.SECONDS);

        System.out.println("Cola (PLAN B — doble de prueba) en 0.0.0.0:" + puerto
                + " · contrato v" + CONTRATO
                + " · token " + (TOKEN_CONSUMIDOR.isEmpty() ? "NO" : "si")
                + (MASTER_AJENO.isEmpty() ? " · rol master" : " · rol slave -> " + MASTER_AJENO));
        System.out.println("  POST /pedidos/tomar      {consumidor,espera}    worker: tomar");
        System.out.println("  POST /respuestas         {id,estado,...}        worker: responder");
        System.out.println("  POST /pedidos/devolver   {id,consumidor}        worker: soltar");
        System.out.println("  POST /pedidos            {operacion,...}        balanceador: publicar");
        System.out.println("  POST /respuestas/tomar   {destinatario,espera}  balanceador: recolectar");
        System.out.println("  GET  /health · GET /estado");
    }

    // --- despacho ----------------------------------------------------------------------

    /**
     * Las tres rutas de pedidos. El cuerpo se lee SIEMPRE y antes que nada —aunque la ruta
     * no exista o el token este mal— por la misma razon que en el servicio real: con
     * keep-alive, los bytes que no se leen quedan en el buffer del socket y el servidor los
     * toma como la linea de pedido de la request siguiente. La conexion queda envenenada y
     * el proximo pedido muere con un 400 que no tiene nada que ver con lo que mando.
     */
    private static void pedidosHandler(HttpExchange intercambio) throws IOException {
        if (!"POST".equals(intercambio.getRequestMethod())) {
            responder(intercambio, 405, "{\"error\":\"metodo no permitido\"}");
            return;
        }
        JsonObject cuerpo = cuerpoJson(intercambio);
        if (redirigir(intercambio)) {
            return;
        }
        switch (ruta(intercambio)) {
            case "/pedidos":
                if (autorizado(intercambio, TOKEN_PUBLICADOR)) {
                    publicarPedido(intercambio, cuerpo);
                }
                return;
            case "/pedidos/tomar":
                if (autorizado(intercambio, TOKEN_CONSUMIDOR)) {
                    tomar(intercambio, cuerpo);
                }
                return;
            case "/pedidos/devolver":
                if (autorizado(intercambio, TOKEN_CONSUMIDOR)) {
                    soltar(intercambio, cuerpo);
                }
                return;
            default:
                responder(intercambio, 404, "{\"error\":\"no existe\"}");
        }
    }

    /** Las dos rutas de respuestas: la del worker (publicar) y la del balanceador (tomar). */
    private static void respuestasHandler(HttpExchange intercambio) throws IOException {
        if (!"POST".equals(intercambio.getRequestMethod())) {
            responder(intercambio, 405, "{\"error\":\"metodo no permitido\"}");
            return;
        }
        JsonObject cuerpo = cuerpoJson(intercambio);
        if (redirigir(intercambio)) {
            return;
        }
        switch (ruta(intercambio)) {
            case "/respuestas":
                if (autorizado(intercambio, TOKEN_CONSUMIDOR)) {
                    recibirRespuesta(intercambio, cuerpo);
                }
                return;
            case "/respuestas/tomar":
                if (autorizado(intercambio, TOKEN_PUBLICADOR)) {
                    entregarRespuesta(intercambio, cuerpo);
                }
                return;
            default:
                responder(intercambio, 404, "{\"error\":\"no existe\"}");
        }
    }

    /**
     * El {@code /health} del contrato, sin token: es lo que usan el worker y el balanceador
     * para descubrir quien es el master, y exigirles un token para preguntarlo seria pedir
     * la llave para averiguar donde esta la puerta.
     */
    private static void saludHandler(HttpExchange intercambio) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("cola", "sana");
        o.addProperty("rol", MASTER_AJENO.isEmpty() ? "master" : "slave");
        o.addProperty("termino", 1);
        o.addProperty("masterConocido", MASTER_AJENO.isEmpty() ? miUrl(intercambio) : MASTER_AJENO);
        o.addProperty("contrato", CONTRATO);
        o.addProperty("instancia", "cola-falsa");
        synchronized (CANDADO) {
            o.addProperty("esperando", ESPERANDO.size());
            o.addProperty("enVuelo", EN_VUELO.size());
        }
        o.addProperty("cota", COTA_RESPUESTAS);
        responder(intercambio, 200, o.toString());
    }

    private static void estadoHandler(HttpExchange intercambio) throws IOException {
        if (autorizado(intercambio, TOKEN_PUBLICADOR)) {
            responder(intercambio, 200, estado());
        }
    }

    /** La ruta pedida, sin query y sin barra final. */
    private static String ruta(HttpExchange intercambio) {
        String ruta = intercambio.getRequestURI().getPath();
        while (ruta.length() > 1 && ruta.endsWith("/")) {
            ruta = ruta.substring(0, ruta.length() - 1);
        }
        return ruta;
    }

    /** 403 y false si el token no coincide. Vacio = no se exige. */
    private static boolean autorizado(HttpExchange intercambio, String esperado) throws IOException {
        if (esperado.isEmpty()
                || esperado.equals(intercambio.getRequestHeaders().getFirst("X-Cola-Token"))) {
            return true;
        }
        responder(intercambio, 403, "{\"error\":\"token invalido\"}");
        return false;
    }

    /**
     * 421 y true si este nodo esta haciendo de slave. Es el caso que el worker tiene que
     * saber seguir: actualizar su cache con la URL de adentro y reintentar ahi.
     */
    private static boolean redirigir(HttpExchange intercambio) throws IOException {
        if (MASTER_AJENO.isEmpty()) {
            return false;
        }
        responder(intercambio, 421,
                "{\"error\":\"no-soy-master\",\"master\":\"" + MASTER_AJENO + "\"}");
        return true;
    }

    /**
     * La URL por la que el cliente llego hasta aca, sacada del header Host.
     *
     * <p>No se arma con getLocalAddress(): sobre IPv6 eso devuelve la direccion cruda y sale
     * un "http://0:0:0:0:0:0:0:1:8000" que no es una URL valida. El Host es lo que el
     * cliente escribio, que es justo lo que tiene que poder volver a usar.
     */
    private static String miUrl(HttpExchange intercambio) {
        String host = intercambio.getRequestHeaders().getFirst("Host");
        if (host == null || host.isBlank()) {
            host = "localhost:" + intercambio.getLocalAddress().getPort();
        }
        return "http://" + host;
    }

    // --- las rutas del worker ----------------------------------------------------------

    /**
     * {@code POST /pedidos/tomar}: entrega el proximo pedido y lo reserva. 204 si no hubo
     * nada en {@code espera} segundos.
     *
     * <p>El {@code consumidor} es obligatorio y sin default: el servicio real contesta 400
     * sin el, y un default silencioso acá haria pasar una prueba que contra la cola de
     * verdad falla.
     */
    private static void tomar(HttpExchange intercambio, JsonObject cuerpoPedido) throws IOException {
        if (cuerpoPedido == null || texto(cuerpoPedido, "consumidor", null) == null) {
            responder(intercambio, 400, "{\"error\":\"falta consumidor\"}");
            return;
        }
        String consumidor = texto(cuerpoPedido, "consumidor", null);
        long limite = ahora() + Math.min(enteroJson(cuerpoPedido, "espera", 20), 30) * 1000;

        // Se arma la respuesta adentro del candado y se escribe afuera: un cliente lento
        // escribiendo su respuesta no tiene por qué frenar al que viene a publicar.
        String cuerpo = null;
        synchronized (CANDADO) {
            VISTOS.put(consumidor, ahora());
            while (cuerpo == null) {
                Pedido pedido = proximoVivo();
                if (pedido != null) {
                    pedido.reservadoPor = consumidor;
                    pedido.reservadoHasta = Math.min(ahora() + RESERVA_MS, pedido.venceEn);
                    pedido.intentos.add(consumidor);
                    EN_VUELO.put(pedido.id, pedido);
                    cuerpo = pedido.comoJson().toString();
                    break;
                }
                long restante = limite - ahora();
                if (restante <= 0) {
                    break;
                }
                try {
                    CANDADO.wait(Math.min(restante, 500));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (cuerpo == null) {
            responder(intercambio, 204, "");
        } else {
            responder(intercambio, 200, cuerpo);
        }
    }

    /**
     * {@code POST /respuestas}: el worker contesto.
     *
     * <p>Los dos 409 del contrato salen de aca y se distinguen por el cuerpo:
     * {@code desconocido} (el pedido ya lo contesto otro: hay que tirarla) y
     * {@code destinatario-saturado} (el balanceador no recolecta: se puede reintentar).
     */
    private static void recibirRespuesta(HttpExchange intercambio, JsonObject cuerpo) throws IOException {
        if (cuerpo == null || !cuerpo.has("id")) {
            responder(intercambio, 400, "{\"error\":\"falta el id\"}");
            return;
        }
        String id = cuerpo.get("id").getAsString();

        synchronized (CANDADO) {
            Pedido pedido = completar(id);
            if (pedido == null) {
                descartados++;
                responder(intercambio, 409, "{\"resultado\":\"desconocido\"}");
                return;
            }
            // Saturado: el destinatario hace rato que no recolecta. Se chequea ANTES de
            // contar la respuesta como entregada, porque el pedido vuelve a quedar sin
            // contestar y el worker tiene que poder reintentar.
            Deque<JsonObject> pendientes = RESPUESTAS.computeIfAbsent(
                    pedido.destinatario, k -> new ArrayDeque<>());
            if (pendientes.size() >= COTA_RESPUESTAS) {
                EN_VUELO.put(pedido.id, pedido);
                responder(intercambio, 409, "{\"resultado\":\"destinatario-saturado\"}");
                return;
            }
            respondidos++;
            JsonObject respuesta = armar(pedido,
                    texto(cuerpo, "estado", "OK"),
                    cuerpo.has("contenido") && cuerpo.get("contenido").isJsonObject()
                            ? cuerpo.getAsJsonObject("contenido") : new JsonObject(),
                    texto(cuerpo, "atendidoPor", null),
                    texto(cuerpo, "app", null));
            pendientes.add(respuesta);
            CANDADO.notifyAll();
        }
        responder(intercambio, 202, "{\"resultado\":\"entregada\"}");
    }

    /**
     * {@code POST /pedidos/devolver}: el worker suelta un pedido sin resolver. Vuelve al
     * frente, no al final: ya espero una vez.
     *
     * <p>409 y no 404 cuando no estaba en vuelo, como el servicio real: no es "esa ruta no
     * existe", es "llegaste tarde, la reserva ya vencio y el recuperador se te adelanto".
     */
    private static void soltar(HttpExchange intercambio, JsonObject cuerpo) throws IOException {
        String id = cuerpo == null ? null : texto(cuerpo, "id", null);
        synchronized (CANDADO) {
            Pedido pedido = id == null ? null : EN_VUELO.remove(id);
            if (pedido == null) {
                responder(intercambio, 409, "{\"resultado\":\"no-estaba-en-vuelo\"}");
                return;
            }
            pedido.reservadoPor = null;
            pedido.reservadoHasta = 0;
            ESPERANDO.addFirst(pedido);
            CANDADO.notifyAll();
        }
        responder(intercambio, 200, "{\"resultado\":\"devuelto\"}");
    }

    // --- los endpoints del rol balanceador ---------------------------------------------

    /** {@code POST /pedidos}: lo llama el balanceador. 202 con el id asignado. */
    private static void publicarPedido(HttpExchange intercambio, JsonObject cuerpo) throws IOException {
        if (cuerpo == null) {
            responder(intercambio, 400, "{\"error\":\"cuerpo ilegible\"}");
            return;
        }
        Pedido pedido = new Pedido();
        pedido.operacion = texto(cuerpo, "operacion", "GET /");
        if (cuerpo.has("parametros") && cuerpo.get("parametros").isJsonObject()) {
            pedido.parametros = cuerpo.getAsJsonObject("parametros");
        }
        // Idempotente por defecto salvo que sea un alta: es la regla del otro equipo, y el
        // que publica es quien sabe si la operación escribe o no.
        pedido.idempotente = cuerpo.has("idempotente")
                ? cuerpo.get("idempotente").getAsBoolean()
                : !pedido.operacion.toUpperCase().startsWith("POST /PERSONAS");
        pedido.destinatario = texto(cuerpo, "destinatario", "balanceador@local");
        pedido.cliente = texto(cuerpo, "cliente", "127.0.0.1");
        pedido.venceEn = ahora() + (cuerpo.has("presupuestoMs")
                ? cuerpo.get("presupuestoMs").getAsLong() : PRESUPUESTO_MS);

        synchronized (CANDADO) {
            ESPERANDO.addLast(pedido);
            publicados++;
            CANDADO.notifyAll();
        }
        responder(intercambio, 202, "{\"id\":\"" + pedido.id + "\",\"encolado\":true}");
    }

    /**
     * {@code POST /respuestas/tomar}: lo llama el recolector del balanceador. Cada respuesta
     * tiene UN destinatario y nadie mas se la puede llevar — con una FIFO sola, el segundo
     * balanceador se comeria las respuestas del primero.
     */
    private static void entregarRespuesta(HttpExchange intercambio, JsonObject cuerpoPedido) throws IOException {
        String destinatario = cuerpoPedido == null ? "balanceador@local"
                : texto(cuerpoPedido, "destinatario", "balanceador@local");
        long limite = ahora() + Math.min(
                cuerpoPedido == null ? 5 : enteroJson(cuerpoPedido, "espera", 5), 30) * 1000;

        String cuerpo = null;
        synchronized (CANDADO) {
            while (cuerpo == null) {
                Deque<JsonObject> pendientes = RESPUESTAS.get(destinatario);
                if (pendientes != null && !pendientes.isEmpty()) {
                    cuerpo = pendientes.pollFirst().toString();
                    break;
                }
                long restante = limite - ahora();
                if (restante <= 0) {
                    break;
                }
                try {
                    CANDADO.wait(Math.min(restante, 500));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (cuerpo == null) {
            responder(intercambio, 204, "");
        } else {
            responder(intercambio, 200, cuerpo);
        }
    }

    // --- mantenimiento -----------------------------------------------------------------

    /**
     * Reservas vencidas. La regla de reintento es contrato: lo idempotente se reencola,
     * la escritura se falla. Repetir un alta que quizá se ejecutó crearía la persona dos
     * veces; un 504 honesto es mejor que un duplicado silencioso.
     */
    private static void recuperar() {
        List<JsonObject> aPublicar = new ArrayList<>();
        synchronized (CANDADO) {
            for (Pedido pedido : new ArrayList<>(EN_VUELO.values())) {
                if (pedido.reservadoHasta > ahora()) {
                    continue;
                }
                EN_VUELO.remove(pedido.id);
                String quien = pedido.reservadoPor;
                pedido.reservadoPor = null;
                if (pedido.queda() <= 0) {
                    aPublicar.add(armar(pedido, "DEADLINE_EXCEEDED",
                            error("venció mientras lo atendía " + quien), null, null));
                } else if (pedido.idempotente) {
                    ESPERANDO.addFirst(pedido);
                    reasignados++;
                    System.out.println("[cola] reasignada " + pedido.id + ": " + quien + " no contestó");
                } else {
                    aPublicar.add(armar(pedido, "DEADLINE_EXCEEDED",
                            error(quien + " no contestó y la operación no es idempotente"), null, null));
                }
            }
            for (Pedido pedido : new ArrayList<>(ESPERANDO)) {
                if (pedido.queda() <= 0) {
                    ESPERANDO.remove(pedido);
                    aPublicar.add(armar(pedido, "DEADLINE_EXCEEDED",
                            error("venció esperando en la cola"), null, null));
                }
            }
            for (JsonObject respuesta : aPublicar) {
                String destinatario = respuesta.get("destinatario").getAsString();
                RESPUESTAS.computeIfAbsent(destinatario, k -> new ArrayDeque<>()).add(respuesta);
            }
            if (!aPublicar.isEmpty()) {
                CANDADO.notifyAll();
            }
        }
    }

    // --- el modo cliente: publicar tareas como lo haría el balanceador -----------------

    private static void publicar(String[] args) throws Exception {
        String url = args.length > 1 ? args[1] : "http://localhost:8000";
        int cuantas = args.length > 2 ? Integer.parseInt(args[2]) : 10;
        String destinatario = args.length > 3 ? args[3] : "balanceador@local";
        long base = System.currentTimeMillis() % 100000;

        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        int publicadas = 0;
        for (int i = 0; i < cuantas; i++) {
            JsonObject sobre = new JsonObject();
            JsonObject parametros = new JsonObject();
            // Una rueda de las cinco operaciones del contrato, para que la demo muestre
            // todas y no sólo el alta.
            switch (i % 5) {
                case 0:
                    sobre.addProperty("operacion", "POST /personas");
                    parametros.addProperty("nombre", "Ada Lovelace " + (base + i));
                    parametros.addProperty("legajo", (int) (900000 + base + i));
                    break;
                case 1:
                    sobre.addProperty("operacion", "GET /personas");
                    break;
                case 2:
                    sobre.addProperty("operacion", "POST /echo");
                    parametros.addProperty("ping", "tarea " + i);
                    break;
                case 3:
                    sobre.addProperty("operacion", "GET /");
                    break;
                default:
                    sobre.addProperty("operacion", "GET /health");
                    break;
            }
            sobre.add("parametros", parametros);
            sobre.addProperty("destinatario", destinatario);

            HttpResponse<String> respuesta = http.send(HttpRequest.newBuilder()
                            .uri(URI.create(url + "/pedidos"))
                            .timeout(Duration.ofSeconds(5))
                            .header("Content-Type", "application/json; charset=utf-8")
                            .header("X-Cola-Token", TOKEN_PUBLICADOR)
                            .POST(HttpRequest.BodyPublishers.ofString(sobre.toString(), StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (respuesta.statusCode() / 100 == 2) {
                publicadas++;
            } else {
                System.out.println("  no se pudo publicar: " + respuesta.statusCode() + " " + respuesta.body());
            }
        }
        System.out.println("publicadas " + publicadas + "/" + cuantas + " tareas en " + url);

        // Recolecta las respuestas, que es lo que haría el balanceador antes de contestarle
        // al cliente. Sirve para ver de punta a punta que el worker las resolvió.
        int recolectadas = 0;
        Map<String, Integer> porEstado = new HashMap<>();
        Map<String, Integer> porWorker = new HashMap<>();
        while (recolectadas < publicadas) {
            JsonObject pedido = new JsonObject();
            pedido.addProperty("destinatario", destinatario);
            pedido.addProperty("espera", 10);
            HttpResponse<String> respuesta = http.send(HttpRequest.newBuilder()
                            .uri(URI.create(url + "/respuestas/tomar"))
                            .timeout(Duration.ofSeconds(15))
                            .header("Content-Type", "application/json; charset=utf-8")
                            .header("X-Cola-Token", TOKEN_PUBLICADOR)
                            .POST(HttpRequest.BodyPublishers.ofString(pedido.toString(), StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (respuesta.statusCode() != 200) {
                break;
            }
            JsonObject sobre = JsonParser.parseString(respuesta.body()).getAsJsonObject();
            recolectadas++;
            porEstado.merge(texto(sobre, "estado", "?"), 1, Integer::sum);
            porWorker.merge(texto(sobre, "atendidoPor", "(sin worker)"), 1, Integer::sum);
        }
        System.out.println("recolectadas " + recolectadas + " respuestas");
        porEstado.forEach((estado, cuenta) -> System.out.println("  " + estado + ": " + cuenta));
        System.out.println("reparto entre workers:");
        porWorker.forEach((worker, cuenta) -> System.out.println("  " + worker + ": " + cuenta));
    }

    // --- interno -----------------------------------------------------------------------

    private static long ahora() {
        return System.nanoTime() / 1_000_000;
    }

    /** Un parámetro numérico que llega mal no tiene que devolver un 500: vale el default. */
    private static long entero(String valor, long porDefecto) {
        try {
            return valor == null ? porDefecto : Long.parseLong(valor.trim());
        } catch (NumberFormatException e) {
            return porDefecto;
        }
    }

    /** Un numero del cuerpo JSON. Si llega como string o roto, vale el default. */
    private static long enteroJson(JsonObject cuerpo, String clave, long porDefecto) {
        JsonElement e = cuerpo == null ? null : cuerpo.get(clave);
        if (e == null || !e.isJsonPrimitive()) {
            return porDefecto;
        }
        try {
            return e.getAsLong();
        } catch (RuntimeException ex) {
            return porDefecto;
        }
    }

    private static Pedido proximoVivo() {
        while (!ESPERANDO.isEmpty()) {
            Pedido pedido = ESPERANDO.pollFirst();
            if (pedido.queda() > 0) {
                return pedido;
            }
        }
        return null;
    }

    private static Pedido completar(String id) {
        Pedido pedido = EN_VUELO.remove(id);
        if (pedido != null) {
            return pedido;
        }
        for (Pedido esperando : ESPERANDO) {
            if (esperando.id.equals(id)) {
                ESPERANDO.remove(esperando);
                return esperando;
            }
        }
        return null;
    }

    private static JsonObject armar(Pedido pedido, String estado, JsonObject contenido,
                                    String atendidoPor, String app) {
        JsonObject o = new JsonObject();
        o.addProperty("id", pedido.id);
        o.addProperty("operacion", pedido.operacion);
        o.addProperty("estado", estado);
        o.add("contenido", contenido);
        o.addProperty("atendidoPor", atendidoPor);
        o.addProperty("app", app);
        o.addProperty("destinatario", pedido.destinatario);
        JsonArray intentos = new JsonArray();
        pedido.intentos.forEach(intentos::add);
        o.add("intentos", intentos);
        o.addProperty("esperaMs", ahora() - pedido.encoladoEn);
        return o;
    }

    private static JsonObject error(String detalle) {
        JsonObject o = new JsonObject();
        o.addProperty("error", detalle);
        return o;
    }

    private static String estado() {
        synchronized (CANDADO) {
            JsonObject o = new JsonObject();
            o.addProperty("esperando", ESPERANDO.size());
            o.addProperty("enVuelo", EN_VUELO.size());
            o.addProperty("publicados", publicados);
            o.addProperty("reasignados", reasignados);
            o.addProperty("respondidos", respondidos);
            o.addProperty("descartados", descartados);
            o.addProperty("reservaSegundos", RESERVA_MS / 1000);

            JsonObject consumidores = new JsonObject();
            VISTOS.forEach((consumidor, visto) -> consumidores.addProperty(consumidor, ahora() - visto));
            o.add("ultimoPedidoHaceMs", consumidores);

            JsonObject pendientes = new JsonObject();
            RESPUESTAS.forEach((destinatario, cola) -> pendientes.addProperty(destinatario, cola.size()));
            o.add("respuestasPendientes", pendientes);
            return o.toString();
        }
    }

    private static Map<String, String> consulta(HttpExchange intercambio) {
        Map<String, String> parametros = new HashMap<>();
        String consulta = intercambio.getRequestURI().getRawQuery();
        if (consulta == null) {
            return parametros;
        }
        for (String par : consulta.split("&")) {
            int igual = par.indexOf('=');
            if (igual > 0) {
                parametros.put(java.net.URLDecoder.decode(par.substring(0, igual), StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(par.substring(igual + 1), StandardCharsets.UTF_8));
            }
        }
        return parametros;
    }

    private static JsonObject cuerpoJson(HttpExchange intercambio) throws IOException {
        String cuerpo = new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (cuerpo.isBlank()) {
            return null;
        }
        try {
            JsonElement raiz = JsonParser.parseString(cuerpo);
            return raiz.isJsonObject() ? raiz.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String texto(JsonObject o, String clave, String porDefecto) {
        JsonElement e = o.get(clave);
        return (e == null || e.isJsonNull() || !e.isJsonPrimitive()) ? porDefecto : e.getAsString();
    }

    private static void responder(HttpExchange intercambio, int codigo, String json) throws IOException {
        byte[] cuerpo = json.getBytes(StandardCharsets.UTF_8);
        intercambio.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        intercambio.sendResponseHeaders(codigo, cuerpo.length == 0 ? -1 : cuerpo.length);
        if (cuerpo.length > 0) {
            try (OutputStream salida = intercambio.getResponseBody()) {
                salida.write(cuerpo);
            }
        } else {
            intercambio.close();
        }
    }
}
