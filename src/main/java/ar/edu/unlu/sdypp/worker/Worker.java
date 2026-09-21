package ar.edu.unlu.sdypp.worker;

import ar.edu.unlu.sdypp.Bitacora;
import ar.edu.unlu.sdypp.Config;
import ar.edu.unlu.sdypp.Operaciones;
import ar.edu.unlu.sdypp.RepositorioPersonas;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Worker de la cola — toma un pedido, lo resuelve y devuelve la respuesta.
 *
 * <p>Es un servicio aparte del servidor gRPC: corre en su propio contenedor, se escala y se
 * reinicia sin tocar las réplicas, y no escucha ningún puerto de servicio — <b>nadie le
 * habla</b>. Es él quien va a buscar trabajo. Eso es lo que lo hace desplegable en cualquier
 * casa sin pedirle nada al equipo de red: no necesita ser alcanzable desde afuera, sólo
 * alcanzar la cola.
 *
 * <pre>
 *   TP_COLA_URL=http://cola:8000/tareas TP_REDIS_URL=redis://sdypp-redis:6379/0 \
 *     java -cp target/app-java.jar ar.edu.unlu.sdypp.worker.Worker
 * </pre>
 *
 * <h2>El ciclo</h2>
 * <ol>
 *   <li><b>Tomar</b> — un GET de long-polling que se queda esperando hasta que haya trabajo.</li>
 *   <li><b>Resolver</b> — la operación del contrato, con la misma clase que usa el servidor
 *       gRPC ({@link Operaciones}), así el mismo pedido da el mismo código por los dos
 *       caminos.</li>
 *   <li><b>Responder</b> — un POST a la misma URL. Si falla, se reintenta: la tarea ya se
 *       ejecutó y perder la respuesta de un alta que sí se escribió en la base es el peor
 *       resultado posible.</li>
 * </ol>
 *
 * <h2>Consumidores que compiten</h2>
 * Nadie reparte el trabajo: el worker que está libre pide el próximo pedido y la cola se lo
 * da a uno solo. Levantar más workers es toda la configuración que hace falta para escalar,
 * y un worker que se muere no deja a nadie esperando — su pedido vence la reserva y la cola
 * se lo da a otro. Por eso el worker no lleva estado: lo único que tiene en la mano es la
 * tarea que está resolviendo.
 */
public final class Worker {

    /** Segundos que se le dan a la tarea en curso al apagar. Mismo criterio que AppJava. */
    private static final int GRACIA_SEGUNDOS =
            Integer.parseInt(System.getenv().getOrDefault("TP_GRACE", "8"));

    /** Intentos de entregar una respuesta ya calculada, antes de darla por perdida. */
    private static final int REINTENTOS_RESPUESTA = 3;

    /** Techo del backoff cuando la cola no responde, en milisegundos. */
    private static final long ESPERA_MAXIMA_MS = 15_000;

    private static volatile boolean apagando = false;

    // Contadores del panel. AtomicLong porque los incrementan todos los consumidores a la
    // vez: con un long común se pierden incrementos y el panel miente justo cuando se lo
    // mira en la demo.
    private static final AtomicLong TOMADAS = new AtomicLong();
    private static final AtomicLong RESUELTAS = new AtomicLong();
    private static final AtomicLong FALLIDAS = new AtomicLong();
    private static final AtomicLong DESCARTADAS = new AtomicLong();
    private static final AtomicLong SATURADAS = new AtomicLong();
    private static final AtomicLong PERDIDAS = new AtomicLong();
    private static final AtomicLong VACIAS = new AtomicLong();
    private static final AtomicLong ERRORES_COLA = new AtomicLong();
    private static final Map<String, AtomicLong> POR_OPERACION = new ConcurrentHashMap<>();
    private static final Map<String, String> EN_VUELO = new ConcurrentHashMap<>();

    /** El cliente, para que el panel —que es estático— pueda mostrar el master vigente. */
    private static volatile ClienteCola COLA = null;

    private static volatile boolean colaSana = true;
    private static volatile String ultimoError = null;
    private static volatile String ultimaTarea = null;

    private Worker() {
    }

    public static void main(String[] args) throws Exception {
        String urls = args.length > 0 ? args[0] : Config.COLA_URLS;
        if (urls.isBlank()) {
            System.out.println("Falta TP_COLA_URLS: las URLs de los nodos de cola, separadas por comas.");
            System.out.println("  ej: TP_COLA_URLS=http://cola-1:8085,http://cola-2:8086 \\");
            System.out.println("      TP_COLA_TOKEN=<token> TP_COLA_CONSUMIDOR=10.0.0.7:8080 \\");
            System.out.println("      java -cp app-java.jar " + Worker.class.getName());
            System.exit(2);
        }

        // El consumidor no tiene default a propósito. El contrato pide el host:puerto gRPC
        // de la réplica —el mismo string que el balanceador usa como `destino`— y un default
        // inventado deja a la réplica figurando sana y sin consumir nada: el sistema anda,
        // la métrica miente, y nadie lo nota hasta que alguien pierde una tarde buscándolo.
        // Preferimos no arrancar.
        if (Config.COLA_CONSUMIDOR.isBlank()) {
            System.out.println("Falta TP_COLA_CONSUMIDOR: el host:puerto gRPC de la réplica,");
            System.out.println("  el mismo string que el balanceador usa como `destino` en su pool.");
            System.out.println("  ej: TP_COLA_CONSUMIDOR=100.91.134.43:8080");
            System.exit(2);
        }
        if (!Config.COLA_CONSUMIDOR.matches("[^\\s:]+:\\d+")) {
            System.out.println("[cola] OJO: TP_COLA_CONSUMIDOR=" + Config.COLA_CONSUMIDOR
                    + " no tiene forma host:puerto. El contrato pide el host:puerto gRPC de la"
                    + " réplica; con otro string la cola no puede cruzar sus contadores con el"
                    + " /health del balanceador.");
        }
        if (Config.COLA_TOKEN.isBlank()) {
            System.out.println("[cola] sin TP_COLA_TOKEN: sólo funciona si la cola arrancó sin token");
        }

        RepositorioPersonas repositorio = RepositorioPersonas.crear();
        Operaciones operaciones = new Operaciones(repositorio);
        Ejecutor ejecutor = new Ejecutor(operaciones);
        ClienteCola cola = new ClienteCola(urls, Config.COLA_TOKEN, Config.COLA_CONSUMIDOR);
        COLA = cola;

        System.out.printf("Worker de cola (PID: %d) (Arrancado: %s)%n",
                ProcessHandle.current().pid(), Config.ARRANCADO);
        System.out.printf("[instancia] %s@%s host=%s version=%d hilos=%d%n",
                Config.APP, Config.CASA, Config.HOST, Config.VERSION, Config.COLA_HILOS);
        System.out.println("[cola] nodos " + cola.nodos() + " como " + Config.COLA_CONSUMIDOR
                + " (espera " + Config.COLA_ESPERA + "s, token "
                + (Config.COLA_TOKEN.isBlank() ? "NO" : "sí") + ")");
        verificarContrato(cola);
        if (repositorio == null) {
            System.out.println("[personas] sin TP_REDIS_URL: las tareas de personas se responden UNAVAILABLE");
        } else {
            System.out.println("[personas] base compartida en " + Config.urlSinCredenciales(Config.REDIS_URL));
        }
        System.out.println("[bitacora] " + Bitacora.archivo().toAbsolutePath());

        HttpServer panel = abrirPanel();

        List<Thread> consumidores = new ArrayList<>();
        for (int i = 1; i <= Config.COLA_HILOS; i++) {
            Thread hilo = new Thread(() -> consumir(cola, ejecutor), "consumidor-" + i);
            consumidores.add(hilo);
            hilo.start();
        }

        // El hook corre con SIGTERM (docker stop) y con SIGINT (Ctrl+C).
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[ Graceful Shutdown ] Señal recibida. No se toman más pedidos.");
            apagando = true;
            // Se interrumpe a los que están esperando trabajo, no a los que están
            // resolviendo: cortarle el hilo a uno que ya escribió en la base perdería la
            // respuesta de una operación que sí ocurrió. Los que están ocupados terminan
            // solos y salen al ver la bandera.
            for (Thread hilo : consumidores) {
                if (EN_VUELO.get(hilo.getName()) == null) {
                    hilo.interrupt();
                }
            }
            long limite = System.currentTimeMillis() + GRACIA_SEGUNDOS * 1000L;
            for (Thread hilo : consumidores) {
                try {
                    hilo.join(Math.max(1, limite - System.currentTimeMillis()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            panel.stop(0);
            System.out.println("[ Graceful Shutdown ] Worker detenido. "
                    + RESUELTAS.get() + " tareas resueltas, " + FALLIDAS.get() + " con error.");
        }));

        for (Thread hilo : consumidores) {
            hilo.join();
        }
    }

    /**
     * Compara el major del contrato que declara la cola con el que este worker sabe hablar.
     *
     * <p>Difiere: se sale. Operar contra un contrato desconocido es peor que no arrancar —
     * un campo que cambió de significado no se nota en el momento, se nota en los datos.
     *
     * <p>No contesta nadie: se sigue igual, con un aviso. La cola puede estar levantándose
     * después que el worker, y para eso está el backoff; hacer fallar el arranque por eso
     * convertiría un orden de encendido en un error de despliegue.
     */
    private static void verificarContrato(ClienteCola cola) {
        JsonObject salud = cola.salud();
        if (salud == null) {
            System.out.println("[cola] ningún nodo contestó /health al arrancar: "
                    + "se sigue igual y se reintenta con backoff");
            return;
        }
        String declarado = salud.has("contrato") && !salud.get("contrato").isJsonNull()
                ? salud.get("contrato").getAsString() : null;
        if (declarado == null) {
            System.out.println("[cola] el nodo no declara versión de contrato en /health; "
                    + "se asume v" + Config.COLA_CONTRATO);
            return;
        }
        String major = declarado.split("\\.")[0];
        if (!major.equals(Config.COLA_CONTRATO)) {
            System.out.println("[cola] contrato incompatible: la cola habla v" + declarado
                    + " y este worker está escrito contra v" + Config.COLA_CONTRATO + ".x");
            System.out.println("  Un major distinto significa que algún campo cambió de "
                    + "significado. No se arranca.");
            System.exit(3);
        }
        System.out.println("[cola] contrato v" + declarado + " · rol="
                + (salud.has("rol") ? salud.get("rol").getAsString() : "?")
                + " · master=" + (salud.has("masterConocido") && !salud.get("masterConocido").isJsonNull()
                        ? salud.get("masterConocido").getAsString() : "(sin resolver)"));
    }

    /** El ciclo de un consumidor: tomar, resolver, responder. */
    private static void consumir(ClienteCola cola, Ejecutor ejecutor) {
        String yo = Thread.currentThread().getName();
        long espera = 0;

        while (!apagando) {
            Tarea tarea;
            try {
                if (espera > 0) {
                    Thread.sleep(espera);
                }
                tarea = cola.tomar(Config.COLA_ESPERA);
                if (!colaSana) {
                    colaSana = true;
                    System.out.println("[cola] volvió a responder");
                }
                espera = 0;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClienteCola.ColaNoDisponible e) {
                if (apagando || Thread.currentThread().isInterrupted()) {
                    break;
                }
                // Backoff exponencial y no un reintento inmediato: con la cola caída, N
                // workers reintentando sin pausa la inundan de conexiones justo cuando está
                // intentando levantarse.
                espera = espera == 0 ? 1000 : Math.min(espera * 2, ESPERA_MAXIMA_MS);
                long fallos = ERRORES_COLA.incrementAndGet();
                ultimoError = e.getMessage();
                if (colaSana) {
                    colaSana = false;
                    System.out.println("[cola] no responde: " + e.getMessage()
                            + " — reintentando (espera hasta " + (ESPERA_MAXIMA_MS / 1000) + "s)");
                } else if (fallos % 20 == 0) {
                    System.out.println("[cola] sigue sin responder (" + fallos + " fallos): " + e.getMessage());
                }
                continue;
            }

            if (tarea == null) {
                VACIAS.incrementAndGet();
                continue;
            }

            // Tomada justo cuando llegó la señal: se suelta para que otro la agarre en el
            // acto, en vez de dejarla esperando a que le venza la reserva.
            if (apagando) {
                cola.devolver(tarea);
                break;
            }

            EN_VUELO.put(yo, tarea.id);
            TOMADAS.incrementAndGet();
            try {
                long empezo = System.nanoTime();
                Ejecutor.Resuelta resuelta = ejecutor.ejecutar(tarea);
                long tardoMs = (System.nanoTime() - empezo) / 1_000_000;

                POR_OPERACION.computeIfAbsent(resuelta.rpc, k -> new AtomicLong()).incrementAndGet();
                if ("OK".equals(resuelta.estado)) {
                    RESUELTAS.incrementAndGet();
                } else {
                    FALLIDAS.incrementAndGet();
                }
                ultimaTarea = tarea.id + " · " + resuelta.rpc + " · " + resuelta.estado;

                // El id de la tarea va al final de la línea, como sexto campo: es el id de
                // correlación que el contrato no tiene y que la auditoría necesita para
                // cruzar sin depender de que los relojes de las casas coincidan.
                Bitacora.registrar(resuelta.rpc, resuelta.estado, resuelta.id, "tarea=" + tarea.id);

                if (tarea.quedaMs != Tarea.SIN_PRESUPUESTO && tardoMs > tarea.quedaMs) {
                    System.out.println("[cola] la " + tarea + " tardó " + tardoMs
                            + "ms y tenía " + tarea.quedaMs + "ms: la respuesta puede llegar tarde");
                }
                entregar(cola, tarea, resuelta);
            } catch (RuntimeException e) {
                // Que una tarea explote no puede matar al consumidor: el worker se quedaría
                // mudo y la cola tardaría una reserva entera en darse cuenta.
                FALLIDAS.incrementAndGet();
                ultimoError = "al resolver la " + tarea + ": " + e;
                System.out.println("[worker] error resolviendo la " + tarea + ": " + e);
            } finally {
                EN_VUELO.remove(yo);
            }
        }
        EN_VUELO.remove(yo);
    }

    /**
     * Entrega la respuesta, con reintentos. La tarea <b>ya se ejecutó</b>: si el alta se
     * escribió en la base y la respuesta se pierde, el cliente recibe un error por algo que
     * sí ocurrió — y como la cola no reintenta las escrituras, nadie lo corrige después.
     * Por eso se reintenta la entrega y no la ejecución.
     */
    private static void entregar(ClienteCola cola, Tarea tarea, Ejecutor.Resuelta resuelta) {
        long espera = 250;
        for (int intento = 1; intento <= REINTENTOS_RESPUESTA; intento++) {
            try {
                ClienteCola.Entrega entrega = cola.responder(tarea, resuelta);
                if (entrega == ClienteCola.Entrega.DESCARTADA) {
                    DESCARTADAS.incrementAndGet();
                    System.out.println("[cola] la " + tarea + " ya no existía: otra réplica llegó primero");
                    return;
                }
                // Saturado NO es lo mismo que descartado: el pedido sigue existiendo y su
                // destinatario puede volver. Se reintenta con la misma pausa creciente que
                // el resto de las fallas de entrega, y recién ahí se da por perdida.
                if (entrega == ClienteCola.Entrega.SATURADA) {
                    SATURADAS.incrementAndGet();
                    if (intento == REINTENTOS_RESPUESTA) {
                        PERDIDAS.incrementAndGet();
                        System.out.println("[cola] el destinatario de la " + tarea
                                + " sigue saturado después de " + intento + " intentos");
                        return;
                    }
                    ultimoError = "destinatario saturado al entregar la " + tarea;
                    if (!dormir(espera)) {
                        PERDIDAS.incrementAndGet();
                        return;
                    }
                    espera *= 2;
                    continue;
                }
                return;
            } catch (ClienteCola.ColaNoDisponible e) {
                ultimoError = e.getMessage();
                if (intento == REINTENTOS_RESPUESTA) {
                    PERDIDAS.incrementAndGet();
                    System.out.println("[cola] no se pudo entregar la respuesta de la " + tarea
                            + " (" + intento + " intentos): " + e.getMessage());
                    return;
                }
                if (!dormir(espera)) {
                    PERDIDAS.incrementAndGet();
                    return;
                }
                espera *= 2;
            }
        }
    }

    /** Duerme entre reintentos. false si interrumpieron: el que llama abandona la entrega. */
    private static boolean dormir(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // --- panel de estado ---------------------------------------------------------------

    /**
     * Un HTTP mínimo de sólo lectura, el mismo {@code com.sun.net.httpserver} del JDK que
     * usa el plano de control del conmutador. No expone nada que se pueda cambiar, así que
     * escucha en todas las interfaces: sirve para el HEALTHCHECK del contenedor y para
     * proyectarlo en la demo.
     */
    private static HttpServer abrirPanel() throws IOException {
        HttpServer servidor = HttpServer.create(new InetSocketAddress(Config.COLA_ADMIN), 0);

        /*
         * Sano quiere decir "el worker está trabajando", NO "la cola responde". Un worker
         * que se declara enfermo porque la cola está caída se hace reiniciar por el
         * orquestador una y otra vez sin arreglar nada — y cuando la cola vuelve, no hay
         * worker esperando. La salud de la cola se mira en /estado, que para eso está.
         */
        servidor.createContext("/salud", intercambio -> {
            JsonObject o = new JsonObject();
            o.addProperty("status", apagando ? "APAGANDO" : "SANO");
            o.addProperty("app", Config.APP);
            o.addProperty("version", Config.VERSION);
            responder(intercambio, apagando ? 503 : 200, o.toString());
        });

        servidor.createContext("/estado", intercambio -> responder(intercambio, 200, estado()));

        servidor.setExecutor(null);
        servidor.start();
        System.out.println("[panel] http://0.0.0.0:" + Config.COLA_ADMIN + "/estado");
        return servidor;
    }

    private static String estado() {
        JsonObject o = new JsonObject();
        o.addProperty("app", Config.APP);
        o.addProperty("version", Config.VERSION);
        o.addProperty("host", Config.HOST);
        o.addProperty("casa", Config.CASA);
        o.addProperty("consumidor", Config.COLA_CONSUMIDOR);
        o.addProperty("hilos", Config.COLA_HILOS);
        o.addProperty("arrancado", Config.ARRANCADO);
        o.addProperty("colaSana", colaSana);
        // Cuál de los nodos de la seed list está atendiendo ahora. Es lo primero que se
        // mira cuando el clúster cambió de master y hay que saber si el worker se mudó.
        o.addProperty("master", COLA == null ? null : COLA.master());
        o.addProperty("tomadas", TOMADAS.get());
        o.addProperty("resueltas", RESUELTAS.get());
        o.addProperty("fallidas", FALLIDAS.get());
        o.addProperty("descartadas", DESCARTADAS.get());
        o.addProperty("saturadas", SATURADAS.get());
        o.addProperty("perdidas", PERDIDAS.get());
        o.addProperty("esperasVacias", VACIAS.get());
        o.addProperty("erroresDeCola", ERRORES_COLA.get());

        JsonObject operaciones = new JsonObject();
        POR_OPERACION.forEach((rpc, cuenta) -> operaciones.addProperty(rpc, cuenta.get()));
        o.add("porOperacion", operaciones);

        JsonArray enVuelo = new JsonArray();
        EN_VUELO.values().forEach(enVuelo::add);
        o.add("enVuelo", enVuelo);

        o.addProperty("ultimaTarea", ultimaTarea);
        o.addProperty("ultimoError", ultimoError);
        return o.toString();
    }

    private static void responder(HttpExchange intercambio, int codigo, String json) throws IOException {
        byte[] cuerpo = json.getBytes(StandardCharsets.UTF_8);
        intercambio.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        intercambio.sendResponseHeaders(codigo, cuerpo.length);
        try (OutputStream salida = intercambio.getResponseBody()) {
            salida.write(cuerpo);
        }
    }
}
