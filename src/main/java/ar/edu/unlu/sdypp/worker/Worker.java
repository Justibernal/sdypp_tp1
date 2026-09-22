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
 *   TP_COLA_URLS=http://nodo1:8085,http://nodo2:8085 TP_COLA_TOKEN=... \
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
    /** 409 destinatario-saturado: la respuesta era buena y el balanceador no la levantó. */
    private static final AtomicLong SATURADAS = new AtomicLong();
    private static final AtomicLong PERDIDAS = new AtomicLong();
    private static final AtomicLong VACIAS = new AtomicLong();
    private static final AtomicLong ERRORES_COLA = new AtomicLong();
    private static final Map<String, AtomicLong> POR_OPERACION = new ConcurrentHashMap<>();
    private static final Map<String, String> EN_VUELO = new ConcurrentHashMap<>();

    private static volatile boolean colaSana = true;
    private static volatile String ultimoError = null;
    private static volatile String ultimaTarea = null;

    /**
     * Las réplicas de cola configuradas, para que /estado diga contra qué está hablando.
     * Con tres URLs en una variable de entorno, "a cuál le está pegando" deja de ser obvio
     * — y es lo primero que se pregunta cuando no entra trabajo.
     */
    private static volatile String colaDestino = "";

    /** Para que /estado pueda decir contra qué master está hablando ahora mismo. */
    private static volatile ClienteCola clienteCola;

    private Worker() {
    }

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : Config.COLA_URL;
        if (url.isBlank()) {
            System.out.println("Falta TP_COLA_URLS: la seed list de nodos de cola, separada por coma.");
            System.out.println("  ej: TP_COLA_URLS=http://10.0.0.1:8085,http://10.0.0.2:8085");
            System.exit(2);
        }

        // El consumidor NO tiene default. El contrato de la cola pide el host:puerto gRPC de
        // la réplica, porque ese string es el que el balanceador publica en su /health como
        // "réplicas que están consumiendo". Uno inventado deja a la réplica figurando como
        // sana y sin consumir nada, y alguien pierde una tarde buscando por qué.
        if (Config.COLA_CONSUMIDOR.isBlank()) {
            System.out.println("Falta TP_COLA_CONSUMIDOR: el host:puerto gRPC de esta réplica.");
            System.out.println("  Tiene que ser EL MISMO string que el balanceador tiene como");
            System.out.println("  destino, porque es con lo que cruza salud y consumo.");
            System.out.println("  ej: TP_COLA_CONSUMIDOR=100.101.15.93:8111");
            System.exit(2);
        }
        if (!Config.COLA_CONSUMIDOR.matches("[^\\s/]+:\\d+")) {
            System.out.println("[cola] aviso: TP_COLA_CONSUMIDOR=\"" + Config.COLA_CONSUMIDOR
                    + "\" no tiene forma de host:puerto. La cola lo acepta igual, pero el"
                    + " balanceador no va a poder cruzarlo con su registro de réplicas.");
        }

        RepositorioPersonas repositorio = RepositorioPersonas.crear();
        Operaciones operaciones = new Operaciones(repositorio);
        Ejecutor ejecutor = new Ejecutor(operaciones);

        // Una URL mal escrita se muere acá y no con una traza: TP_COLA_URLS se copia a mano
        // en el entorno de cada casa y lleva tres URLs separadas por coma, que es
        // exactamente donde se cuela una coma de más o un "http:/" con una sola barra.
        ClienteCola cola;
        try {
            cola = new ClienteCola(url, Config.COLA_CONSUMIDOR);
        } catch (IllegalArgumentException e) {
            System.out.println("TP_COLA_URLS no se entiende: " + e.getMessage());
            System.out.println("  formato: una o varias URLs separadas por coma (seed list)");
            System.out.println("  ej: TP_COLA_URLS=http://10.0.0.1:8085,http://10.0.0.2:8085");
            System.exit(2);
            return;
        }

        System.out.printf("Worker de cola (PID: %d) (Arrancado: %s)%n",
                ProcessHandle.current().pid(), Config.ARRANCADO);
        System.out.printf("[instancia] %s@%s host=%s version=%d hilos=%d%n",
                Config.APP, Config.CASA, Config.HOST, Config.VERSION, Config.COLA_HILOS);
        clienteCola = cola;
        colaDestino = cola.destino();
        System.out.println("[cola] " + colaDestino);
        System.out.println("[cola] " + cola.cuantosNodos() + " nodo(s) · como "
                + Config.COLA_CONSUMIDOR + " · espera " + Config.COLA_ESPERA + "s · "
                + (Config.COLA_TOKEN.isBlank()
                   ? "SIN token (TP_COLA_TOKEN vacío)"
                   : "token en " + Config.COLA_AUTH));

        // Se ubica el master y se compara el major del contrato ANTES de tomar el primer
        // pedido. Un major distinto es fatal: significa que algún campo cambió de nombre o
        // que un código cambió de significado, y seguir sería adivinar en silencio. Que el
        // clúster no conteste todavía, en cambio, no lo es — para eso está el backoff.
        try {
            System.out.println("[cola] " + cola.verificar());
        } catch (ClienteCola.ContratoIncompatible e) {
            System.out.println("[cola] CONTRATO INCOMPATIBLE — el worker no arranca.");
            System.out.println("  " + e.getMessage());
            System.exit(3);
            return;
        }

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
            } catch (ClienteCola.ContratoIncompatible e) {
                // El clúster puede cambiar de contrato con el worker ya corriendo (un nodo
                // nuevo, un despliegue del otro equipo). Reintentar contra un contrato que no
                // entendemos es peor que parar: responderíamos con campos que ya no
                // significan lo mismo. Se para TODO el worker, no sólo este hilo, para que el
                // orquestador lo vea caído en vez de vivo y mudo.
                System.out.println("[cola] CONTRATO INCOMPATIBLE en caliente: " + e.getMessage());
                System.out.println("[cola] el worker se detiene: no puede seguir respondiendo a ciegas");
                ultimoError = e.getMessage();
                apagando = true;
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

                // Los dos 409 del contrato NO significan lo mismo, y por eso no se cuentan
                // juntos: uno dice que la respuesta ya no hacía falta y el otro que sí hacía
                // falta y no la pudieron recibir. Mezclarlos escondería el segundo, que es
                // el único de los dos que indica un problema.
                if (entrega == ClienteCola.Entrega.DESCARTADA) {
                    DESCARTADAS.incrementAndGet();
                    System.out.println("[cola] la " + tarea
                            + " ya la había contestado otro: llegamos tarde (no es un error)");
                    return;
                }
                if (entrega == ClienteCola.Entrega.SATURADO) {
                    SATURADAS.incrementAndGet();
                    ultimoError = "el balanceador no está recolectando la respuesta de la " + tarea;
                    if (intento == REINTENTOS_RESPUESTA) {
                        PERDIDAS.incrementAndGet();
                        System.out.println("[cola] el destinatario sigue saturado después de "
                                + intento + " intentos: se pierde la respuesta de la " + tarea);
                        return;
                    }
                    // Se reintenta, pero sin apurarse: el problema está del otro lado y
                    // volver enseguida sólo le agrega carga a quien ya no da abasto.
                    try {
                        Thread.sleep(espera);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
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
                try {
                    Thread.sleep(espera);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    PERDIDAS.incrementAndGet();
                    return;
                }
                espera *= 2;
            }
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
        o.addProperty("cola", colaDestino);
        // Cuál de los nodos es el master ahora. Es lo primero que se quiere saber cuando el
        // worker deja de tomar trabajo, y lo único que no se puede deducir de la config.
        ClienteCola cliente = clienteCola;
        o.addProperty("maestro", cliente == null ? null : cliente.maestroActual());
        // Si el token está puesto o no, nunca cuál es: /estado no pide credencial y se mira
        // proyectado en la demo.
        o.addProperty("colaConToken", !Config.COLA_TOKEN.isBlank());
        o.addProperty("colaSana", colaSana);
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
