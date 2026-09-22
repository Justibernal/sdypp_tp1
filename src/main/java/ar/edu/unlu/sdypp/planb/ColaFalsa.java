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
 * <pre>
 *   java -cp target/app-java.jar ar.edu.unlu.sdypp.planb.ColaFalsa 8000 /tareas
 *
 *   # publicar tareas, como haría el balanceador
 *   java -cp target/app-java.jar ar.edu.unlu.sdypp.planb.ColaFalsa publicar http://localhost:8000/tareas 20
 *
 *   curl -s localhost:8000/estado
 *   curl -s "localhost:8000/tareas/respuestas?destinatario=balanceador@casa-justino&espera=5"
 * </pre>
 */
public final class ColaFalsa {

    /** Segundos que un worker tiene para contestar antes de que el pedido se reasigne. */
    private static final long RESERVA_MS = 10_000;

    /** Presupuesto de un pedido recién publicado, si el publicador no dice otro. */
    private static final long PRESUPUESTO_MS = 30_000;

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
        String ruta = args.length > 1 ? args[1] : "/tareas";

        HttpServer servidor = HttpServer.create(new InetSocketAddress(puerto), 0);
        // Pool y no el executor por defecto (que atiende de a uno): los GET del worker son
        // de long-polling y se quedan colgados hasta veinte segundos. Con un solo hilo, un
        // worker esperando trabajo bloquearía al balanceador que viene a publicar — y la
        // cola se trabaría sola.
        servidor.setExecutor(Executors.newFixedThreadPool(16));

        servidor.createContext(ruta + "/publicar", ColaFalsa::publicarHandler);
        servidor.createContext(ruta + "/respuestas", ColaFalsa::respuestasHandler);
        servidor.createContext(ruta, ColaFalsa::tareasHandler);
        servidor.createContext("/estado", intercambio -> responder(intercambio, 200, estado()));
        servidor.start();

        // El recuperador: sin esto, el pedido de un worker que se murió se queda en vuelo
        // para siempre. Es lo que reemplaza al aviso que el worker ya no puede dar.
        Executors.newSingleThreadScheduledExecutor(hilo -> {
            Thread t = new Thread(hilo, "recuperador");
            t.setDaemon(true);
            return t;
        }).scheduleWithFixedDelay(ColaFalsa::recuperar, 1, 1, TimeUnit.SECONDS);

        System.out.println("Cola (PLAN B — doble de prueba) en 0.0.0.0:" + puerto + ruta);
        System.out.println("  GET    " + ruta + "?consumidor=<id>&espera=<s>   tomar un pedido");
        System.out.println("  POST   " + ruta + "                              devolver la respuesta");
        System.out.println("  DELETE " + ruta + "?id=<id>&consumidor=<id>      soltar un pedido");
        System.out.println("  POST   " + ruta + "/publicar                     publicar (rol balanceador)");
        System.out.println("  GET    " + ruta + "/respuestas?destinatario=<d>  recolectar (rol balanceador)");
        System.out.println("  GET    /estado");
    }

    // --- el endpoint del worker --------------------------------------------------------

    private static void tareasHandler(HttpExchange intercambio) throws IOException {
        switch (intercambio.getRequestMethod()) {
            case "GET":
                tomar(intercambio);
                return;
            case "POST":
                recibirRespuesta(intercambio);
                return;
            case "DELETE":
                soltar(intercambio);
                return;
            default:
                responder(intercambio, 405, "{\"error\":\"método no permitido\"}");
        }
    }

    /** GET: entrega el próximo pedido y lo reserva. 204 si no hubo nada en `espera`. */
    private static void tomar(HttpExchange intercambio) throws IOException {
        Map<String, String> parametros = consulta(intercambio);
        String consumidor = parametros.getOrDefault("consumidor", "anónimo");
        long limite = ahora() + entero(parametros.get("espera"), 20) * 1000;

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

    /** POST: el worker contestó. 409 si ese pedido ya no existe (llegó segundo). */
    private static void recibirRespuesta(HttpExchange intercambio) throws IOException {
        JsonObject cuerpo = cuerpoJson(intercambio);
        if (cuerpo == null || !cuerpo.has("id")) {
            responder(intercambio, 400, "{\"error\":\"falta el id\"}");
            return;
        }
        String id = cuerpo.get("id").getAsString();

        synchronized (CANDADO) {
            Pedido pedido = completar(id);
            if (pedido == null) {
                descartados++;
                responder(intercambio, 409, "{\"error\":\"desconocido\"}");
                return;
            }
            respondidos++;
            JsonObject respuesta = armar(pedido,
                    texto(cuerpo, "estado", "OK"),
                    cuerpo.has("contenido") && cuerpo.get("contenido").isJsonObject()
                            ? cuerpo.getAsJsonObject("contenido") : new JsonObject(),
                    texto(cuerpo, "atendidoPor", null),
                    texto(cuerpo, "app", null));
            RESPUESTAS.computeIfAbsent(pedido.destinatario, k -> new ArrayDeque<>()).add(respuesta);
            CANDADO.notifyAll();
        }
        responder(intercambio, 200, "{\"entregada\":true}");
    }

    /** DELETE: el worker suelta un pedido sin resolver. Vuelve al frente: ya esperó una vez. */
    private static void soltar(HttpExchange intercambio) throws IOException {
        Map<String, String> parametros = consulta(intercambio);
        String id = parametros.get("id");
        synchronized (CANDADO) {
            Pedido pedido = id == null ? null : EN_VUELO.remove(id);
            if (pedido == null) {
                responder(intercambio, 404, "{\"error\":\"no estaba en vuelo\"}");
                return;
            }
            pedido.reservadoPor = null;
            pedido.reservadoHasta = 0;
            ESPERANDO.addFirst(pedido);
            CANDADO.notifyAll();
        }
        responder(intercambio, 200, "{\"devuelto\":true}");
    }

    // --- los endpoints del rol balanceador ---------------------------------------------

    private static void publicarHandler(HttpExchange intercambio) throws IOException {
        if (!intercambio.getRequestMethod().equals("POST")) {
            responder(intercambio, 405, "{\"error\":\"método no permitido\"}");
            return;
        }
        JsonObject cuerpo = cuerpoJson(intercambio);
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
        responder(intercambio, 202, "{\"id\":\"" + pedido.id + "\"}");
    }

    private static void respuestasHandler(HttpExchange intercambio) throws IOException {
        Map<String, String> parametros = consulta(intercambio);
        String destinatario = parametros.getOrDefault("destinatario", "balanceador@local");
        long limite = ahora() + entero(parametros.get("espera"), 5) * 1000;

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
        String url = args.length > 1 ? args[1] : "http://localhost:8000/tareas";
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
                            .uri(URI.create(url + "/publicar"))
                            .timeout(Duration.ofSeconds(5))
                            .header("Content-Type", "application/json; charset=utf-8")
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
            HttpResponse<String> respuesta = http.send(HttpRequest.newBuilder()
                            .uri(URI.create(url + "/respuestas?destinatario="
                                    + java.net.URLEncoder.encode(destinatario, StandardCharsets.UTF_8) + "&espera=10"))
                            .timeout(Duration.ofSeconds(15))
                            .GET().build(),
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
