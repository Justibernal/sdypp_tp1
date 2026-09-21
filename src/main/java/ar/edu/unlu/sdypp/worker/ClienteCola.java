package ar.edu.unlu.sdypp.worker;

import ar.edu.unlu.sdypp.Config;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * El cliente HTTP contra el servicio de colas. <b>Todo lo que depende del contrato del otro
 * equipo está acá adentro</b>: las rutas, el token, los códigos y la forma del cuerpo. El
 * resto del worker habla de tareas, no de HTTP.
 *
 * <p>Implementa el contrato del worker v1 ({@code docs/contrato-worker.md} del repo del
 * balanceador, rama {@code feature/desacople}). Tres rutas de datos, todas POST con cuerpo
 * JSON y todas contra el <b>master</b> del clúster:
 *
 * <pre>
 *   POST /pedidos/tomar      {"consumidor","espera"}   200 con el pedido · 204 si no hubo
 *   POST /respuestas         {"id","estado",...}       202 entregada · 409 descartada
 *   POST /pedidos/devolver   {"id","consumidor"}       200 devuelto · 409 ya no estaba
 *   GET  /health             (sin token)               descubrimiento y versión del contrato
 * </pre>
 *
 * <h2>Por qué hay una lista de nodos y no una URL</h2>
 * La cola es un clúster de N nodos con un único <b>master</b>; los slaves replican y están
 * para tomar la posta si el master cae. <b>Sólo el master atiende</b> — y no es un capricho:
 * {@code tomar} no es una lectura, es una mutación (reserva el pedido y lo saca del pool).
 * Si dos workers pudieran tomar contra dos nodos distintos, el mismo pedido se entregaría
 * dos veces.
 *
 * <p>Por eso el worker arranca con una <i>seed list</i>, pregunta quién es el master por
 * {@code /health}, lo cachea y le pega directo. Cuando el master cambia, el nodo viejo
 * contesta {@code 421 Misdirected Request} con la URL del nuevo y el worker se muda solo,
 * <b>sin reiniciar el proceso</b>. Con una lista de un elemento —la situación de hoy— el
 * nodo nunca contesta 421 y esa rama no se ejecuta nunca.
 *
 * <h2>Descubrir cuesta cero en régimen normal</h2>
 * El {@code /health} se consulta al arrancar y cuando algo se rompe, nunca antes de cada
 * operación: en el camino feliz, cada {@code tomar} y cada {@code responder} es un POST
 * directo al master cacheado.
 *
 * <h2>HTTP/1.1 explícito</h2>
 * El {@code HttpClient} del JDK negocia HTTP/2 por defecto. El servicio de colas es Python
 * sobre {@code ThreadingHTTPServer}, que no lo habla: el intento de upgrade se paga en cada
 * request y con algunos servidores simples directamente falla. Acá no hay streams que
 * multiplexar, así que se fija 1.1 y listo.
 */
public final class ClienteCola {

    /** La cola no respondió, o respondió algo que no se entiende. */
    public static final class ColaNoDisponible extends RuntimeException {
        public ColaNoDisponible(String mensaje) {
            super(mensaje);
        }

        public ColaNoDisponible(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }

    /** Qué pasó al devolver la respuesta. */
    public enum Entrega {
        /** La cola la aceptó y se la va a dar a su destinatario. */
        ENTREGADA,
        /**
         * La cola ya no conoce esa tarea ({@code 409 desconocido}). No es un error del
         * worker: la reserva venció, otra réplica la resolvió y la nuestra llegó segunda.
         * La cola descarta la segunda a propósito ("gana la primera respuesta"), así que
         * reintentar no tiene sentido.
         */
        DESCARTADA,
        /**
         * El destinatario está saturado ({@code 409 destinatario-saturado}): el balanceador
         * que espera esta respuesta hace rato que no recolecta, casi siempre porque se cayó.
         * A diferencia de DESCARTADA, <b>acá reintentar sí puede servir</b> —el balanceador
         * puede volver— así que se reintenta con backoff antes de darla por perdida.
         */
        SATURADA,
        /** No se pudo entregar: la cola no responde y se agotaron los reintentos. */
        PERDIDA
    }

    /** Techo del long-poll que fija el contrato. Pedir más no da más. */
    private static final int ESPERA_MAXIMA = 30;

    /** Una respuesta HTTP ya parseada. El cuerpo puede ser null: un 204 no lo tiene. */
    private static final class Respuesta {
        final int codigo;
        final JsonObject cuerpo;

        Respuesta(int codigo, JsonObject cuerpo) {
            this.codigo = codigo;
            this.cuerpo = cuerpo;
        }

        String texto(String clave) {
            return ClienteCola.texto(cuerpo, clave);
        }
    }

    private final List<String> semillas;
    private final String token;
    private final String consumidor;
    private final HttpClient http;

    /**
     * La URL del master, cacheada. {@code volatile} porque la escriben y la leen todos los
     * hilos consumidores: sin eso, el que descubre el master nuevo lo deja en su caché de
     * CPU y los demás siguen pegándole al viejo.
     */
    private volatile String master = null;

    /** Para avisar una sola vez que la cola no expone devolución, y no en cada apagado. */
    private final AtomicBoolean avisoDevolucion = new AtomicBoolean(false);

    public ClienteCola(String urls, String token, String consumidor) {
        this.semillas = separar(urls);
        if (this.semillas.isEmpty()) {
            throw new IllegalArgumentException("la lista de nodos de cola está vacía");
        }
        this.token = token == null ? "" : token.trim();
        this.consumidor = consumidor;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** Las semillas, para mostrarlas al arrancar. */
    public List<String> nodos() {
        return semillas;
    }

    /** El master vigente según la caché, o null si todavía no se descubrió. */
    public String master() {
        return master;
    }

    public String destino() {
        String actual = master;
        return actual != null ? actual : String.join(",", semillas);
    }

    // --- las rutas del worker ------------------------------------------------------------

    /**
     * Toma el próximo pedido. Devuelve null si no hubo nada en {@code espera} segundos —
     * que es lo normal y no un error.
     *
     * <p>El timeout de la request es la espera más un margen: si fuera igual, el worker
     * cortaría por su cuenta justo cuando la cola está por contestar que no hay nada, y cada
     * vuelta en vacío parecería una caída de la cola.
     */
    public Tarea tomar(int espera) {
        int segundos = Math.max(0, Math.min(espera, ESPERA_MAXIMA));
        JsonObject cuerpo = new JsonObject();
        cuerpo.addProperty("consumidor", consumidor);
        cuerpo.addProperty("espera", segundos);

        Respuesta respuesta = pedir("/pedidos/tomar", cuerpo, Duration.ofSeconds(segundos + 10L));

        // 204 y sólo 204 significa "no hubo trabajo". Un 404 acá NO es cola vacía: es la
        // ruta equivocada, y tratarlo como vacío deja al worker girando en silencio —sano
        // para el healthcheck y sin resolver nada—. Es la peor falla posible porque no se
        // ve, así que sale por la excepción y el worker la registra.
        if (respuesta.codigo == 204) {
            return null;
        }
        if (respuesta.codigo != 200) {
            throw new ColaNoDisponible("/pedidos/tomar devolvió " + respuesta.codigo
                    + ": " + recortar(respuesta.cuerpo));
        }
        if (respuesta.cuerpo == null) {
            throw new ColaNoDisponible("/pedidos/tomar devolvió 200 con el cuerpo vacío");
        }

        Tarea tarea = Tarea.desde(respuesta.cuerpo);
        if (!tarea.valida()) {
            throw new ColaNoDisponible("la cola entregó un pedido sin id o sin operación: "
                    + recortar(respuesta.cuerpo));
        }
        return tarea;
    }

    /**
     * Devuelve la tarea resuelta. El cuerpo lleva sólo lo que el worker sabe: quién la
     * atendió y con qué resultado. El {@code destinatario} y los {@code intentos} los
     * completa la cola con lo que guardó del pedido — el worker no tiene por qué saber quién
     * lo pidió, y si se lo preguntáramos podría apuntar a otro balanceador y meterle una
     * respuesta ajena.
     */
    public Entrega responder(Tarea tarea, Ejecutor.Resuelta resuelta) {
        JsonObject cuerpo = new JsonObject();
        cuerpo.addProperty("id", tarea.id);
        cuerpo.addProperty("estado", resuelta.estado);
        cuerpo.add("contenido", resuelta.contenido);
        cuerpo.addProperty("atendidoPor", consumidor);
        cuerpo.addProperty("app", Config.APP);

        Respuesta respuesta = pedir("/respuestas", cuerpo, Duration.ofSeconds(10));

        if (respuesta.codigo >= 200 && respuesta.codigo < 300) {
            return Entrega.ENTREGADA;
        }
        // Los dos 409 de esta ruta se distinguen POR EL CUERPO y se tratan distinto. El
        // redirect de master ya no comparte código con ellos —es 421 desde la versión
        // vigente del contrato—, pero estos dos siguen compartiendo el 409 entre sí.
        if (respuesta.codigo == 409) {
            String resultado = respuesta.texto("resultado");
            if ("destinatario-saturado".equals(resultado)) {
                return Entrega.SATURADA;
            }
            return Entrega.DESCARTADA;   // "desconocido": llegamos segundos, hay que tirarla
        }
        throw new ColaNoDisponible("/respuestas devolvió " + respuesta.codigo
                + ": " + recortar(respuesta.cuerpo));
    }

    /**
     * Suelta un pedido sin resolver. Se usa al apagarse: soltarlo lo manda al frente de la
     * cola y otro worker lo toma en el acto, mientras que quedarse callado obliga a esperar
     * a que venza la reserva —y, si la operación no es idempotente, la cola ni siquiera la
     * reintenta: el cliente se come un DEADLINE_EXCEEDED por una réplica que se apagó
     * ordenadamente.
     *
     * <p>Es una optimización, no una garantía: si no se llega a devolver, el recuperador de
     * la cola lo hace igual, sólo que más tarde.
     */
    public boolean devolver(Tarea tarea) {
        try {
            JsonObject cuerpo = new JsonObject();
            cuerpo.addProperty("id", tarea.id);
            cuerpo.addProperty("consumidor", consumidor);

            Respuesta respuesta = pedir("/pedidos/devolver", cuerpo, Duration.ofSeconds(5));
            if (respuesta.codigo >= 200 && respuesta.codigo < 300) {
                return true;
            }
            // 409 "no-estaba-en-vuelo": la reserva ya venció y el recuperador se nos
            // adelantó. Es normal y no hay nada que hacer.
            if (respuesta.codigo == 409) {
                return false;
            }
            if (avisoDevolucion.compareAndSet(false, true)) {
                System.out.println("[cola] /pedidos/devolver respondió " + respuesta.codigo
                        + ": los tomados sin resolver esperan a que venza la reserva");
            }
            return false;
        } catch (RuntimeException e) {
            System.out.println("[cola] no se pudo devolver la " + tarea + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * El {@code /health} del primer nodo que conteste, o null si no contesta ninguno.
     * Sin token: el contrato lo deja abierto justamente para que sirva de descubrimiento.
     */
    public JsonObject salud() {
        for (String url : semillas) {
            try {
                Respuesta respuesta = consultarSalud(url);
                if (respuesta.codigo == 200 && respuesta.cuerpo != null) {
                    return respuesta.cuerpo;
                }
            } catch (ColaNoDisponible e) {
                // Un nodo caído en la seed list es esperable: se prueba el siguiente.
            }
        }
        return null;
    }

    // --- descubrimiento del master -------------------------------------------------------

    /**
     * El corazón del cliente: manda la operación al master y, si el caché quedó viejo, sigue
     * el redirect una vez.
     *
     * <p>Dos intentos y no un bucle: un redirect alcanza para el caso real —el master
     * cambió—, y un bucle abierto contra un clúster que está en elección gira sin dormir y
     * le pone la CPU al 100%. Cuando los dos intentos fallan se lanza la excepción, y el
     * backoff exponencial lo hace el worker, en un solo lugar.
     */
    private Respuesta pedir(String ruta, JsonObject cuerpo, Duration timeout) {
        ColaNoDisponible ultima = null;

        for (int intento = 1; intento <= 2; intento++) {
            String nodo = master;
            if (nodo == null) {
                nodo = descubrirMaster();
                if (nodo == null) {
                    throw new ColaNoDisponible("el clúster de colas no tiene master "
                            + "(elección en curso o nodos caídos): " + String.join(",", semillas));
                }
                master = nodo;
            }

            Respuesta respuesta;
            try {
                respuesta = enviar(nodo + ruta, cuerpo, timeout);
            } catch (ColaNoDisponible e) {
                // El master se cayó: se olvida y en la vuelta siguiente se redescubre.
                master = null;
                ultima = e;
                continue;
            }

            // 421 Misdirected Request: le pegamos a un nodo que no es el master. El cuerpo
            // trae quién lo es — o null, si hay una elección en curso y nadie lo sabe
            // todavía, en cuyo caso se vuelve a la seed list en la próxima vuelta.
            if (respuesta.codigo == 421) {
                String nuevo = respuesta.texto("master");
                master = (nuevo == null || nuevo.isBlank()) ? null : normalizar(nuevo);
                ultima = new ColaNoDisponible("el nodo contestó 421 y el master quedó sin resolver");
                continue;
            }

            // El 403 no es "la cola está mal": es "este worker no tiene el token de
            // consumidor". Se dice con esas palabras porque es un error de configuración y
            // el mensaje es lo único que lo delata — el síntoma es idéntico al de una cola
            // caída, y sin esto se buscaría en el lugar equivocado.
            if (respuesta.codigo == 403) {
                throw new ColaNoDisponible("403 en " + ruta + ": token de consumidor "
                        + (token.isEmpty() ? "ausente (falta TP_COLA_TOKEN)" : "inválido"));
            }

            return respuesta;
        }

        throw ultima != null ? ultima
                : new ColaNoDisponible("no se pudo resolver el master para " + ruta);
    }

    /**
     * Recorre la seed list hasta encontrar quién es el master. Devuelve null si el clúster
     * está en elección o si no contesta nadie — las dos son situaciones transitorias, y el
     * que llama duerme con backoff.
     */
    private String descubrirMaster() {
        for (String url : semillas) {
            Respuesta respuesta;
            try {
                respuesta = consultarSalud(url);
            } catch (ColaNoDisponible e) {
                continue;               // nodo caído: el siguiente
            }
            if (respuesta.codigo != 200 || respuesta.cuerpo == null) {
                continue;
            }

            String rol = respuesta.texto("rol");
            if ("master".equals(rol)) {
                return url;
            }
            String conocido = respuesta.texto("masterConocido");
            if (conocido != null && !conocido.isBlank()) {
                return normalizar(conocido);
            }
            // Un nodo sano que no declara rol ni master es el nodo único de hoy, anterior al
            // clúster: es el master por definición, porque no hay otro. Sin este caso el
            // worker no arrancaría contra la cola que está desplegada ahora mismo.
            if (rol == null && respuesta.cuerpo.has("cola")) {
                return url;
            }
        }
        return null;
    }

    // --- HTTP ----------------------------------------------------------------------------

    private Respuesta enviar(String url, JsonObject cuerpo, Duration timeout) {
        HttpRequest.Builder constructor = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(cuerpo.toString(), StandardCharsets.UTF_8));
        if (!token.isEmpty()) {
            constructor.header("X-Cola-Token", token);
        }
        return recibir(constructor.build(), url);
    }

    /** El {@code /health} va sin token: el contrato lo deja abierto para el descubrimiento. */
    private Respuesta consultarSalud(String url) {
        return recibir(HttpRequest.newBuilder()
                .uri(URI.create(url + "/health"))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .GET()
                .build(), url + "/health");
    }

    private Respuesta recibir(HttpRequest pedido, String url) {
        HttpResponse<String> respuesta;
        try {
            respuesta = http.send(pedido, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ColaNoDisponible("interrumpido hablando con " + url, e);
        } catch (Exception e) {
            throw new ColaNoDisponible("no se pudo hablar con " + url + ": " + e, e);
        }
        return new Respuesta(respuesta.statusCode(), objetoDe(respuesta.body()));
    }

    // --- interno -------------------------------------------------------------------------

    /**
     * La seed list, normalizada. Se sacan la barra final y los vacíos: la ruta se concatena
     * después ({@code url + "/pedidos/tomar"}) y una barra de más da un 404 que cuesta un
     * rato entender.
     */
    private static List<String> separar(String urls) {
        List<String> lista = new ArrayList<>();
        if (urls == null) {
            return lista;
        }
        for (String parte : urls.split(",")) {
            String limpia = normalizar(parte);
            if (!limpia.isEmpty() && !lista.contains(limpia)) {
                lista.add(limpia);
            }
        }
        return Collections.unmodifiableList(lista);
    }

    private static String normalizar(String url) {
        String limpia = url == null ? "" : url.trim();
        while (limpia.endsWith("/")) {
            limpia = limpia.substring(0, limpia.length() - 1);
        }
        return limpia;
    }

    /** El objeto del cuerpo, o null si no hay cuerpo (un 204) o si no es un objeto. */
    private static JsonObject objetoDe(String cuerpo) {
        if (cuerpo == null || cuerpo.isBlank()) {
            return null;
        }
        JsonElement raiz;
        try {
            raiz = JsonParser.parseString(cuerpo);
        } catch (RuntimeException e) {
            throw new ColaNoDisponible("la cola devolvió algo que no es JSON: " + recortar(cuerpo), e);
        }
        if (!raiz.isJsonObject()) {
            return null;
        }
        JsonObject objeto = raiz.getAsJsonObject();
        return objeto.isEmpty() ? null : objeto;
    }

    private static String texto(JsonObject objeto, String clave) {
        if (objeto == null) {
            return null;
        }
        JsonElement e = objeto.get(clave);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
            return null;
        }
        return e.getAsString();
    }

    private static String recortar(JsonObject cuerpo) {
        return recortar(cuerpo == null ? null : cuerpo.toString());
    }

    /** Para que un cuerpo de error enorme no llene la bitácora de una línea. */
    private static String recortar(String texto) {
        if (texto == null) {
            return "(vacío)";
        }
        String limpio = texto.strip().replaceAll("\\s+", " ");
        return limpio.length() <= 200 ? limpio : limpio.substring(0, 200) + "…";
    }
}
