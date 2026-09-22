package ar.edu.unlu.sdypp.worker;

import ar.edu.unlu.sdypp.Config;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * El cliente HTTP contra el servicio de colas. <b>Todo lo que depende del contrato del otro
 * equipo está acá adentro</b>: las rutas, la credencial, los códigos, la forma del cuerpo y
 * el descubrimiento del master. El resto del worker habla de tareas, no de HTTP.
 *
 * <p>Implementa {@code contrato-worker.md v1} del repositorio del balanceador.
 *
 * <pre>
 *   POST {master}/pedidos/tomar      {consumidor, espera}     toma el próximo (long-poll)
 *   POST {master}/respuestas         {id, estado, contenido, atendidoPor, app}
 *   POST {master}/pedidos/devolver   {id, consumidor}         lo suelta sin atenderlo
 *   GET  {nodo}/health                                        quién es el master (sin token)
 * </pre>
 *
 * <h2>Hay un master, y hay que encontrarlo</h2>
 * {@code TP_COLA_URLS} no es una lista de réplicas equivalentes: es una <b>seed list</b> de
 * nodos de un clúster donde <b>sólo el master atiende</b>. La razón no es arbitraria —
 * {@code tomar} no es una lectura, es una mutación: reserva el pedido y lo saca del pool. Si
 * dos workers pudieran tomar contra dos nodos distintos, el mismo pedido se entregaría dos
 * veces.
 *
 * <p>Por eso acá <b>no hay reparto entre nodos</b>, que sería lo natural si fueran réplicas:
 * se descubre el master una vez, se cachea, y se le habla sólo a él. Pegarle a un slave a
 * propósito no da más throughput, da 421.
 *
 * <p>El caché se invalida solo, de dos maneras: un {@code 421} que trae la dirección nueva, o
 * una conexión que se corta. En los dos casos se vuelve a la seed list. Mientras el clúster
 * está eligiendo líder no hay master que valga, y lo único sensato es esperar con backoff —
 * nadie puede inventar un master que todavía no fue electo.
 *
 * <h2>Reintentar lo que se puede reintentar</h2>
 * Un {@code tomar} que falla <b>después</b> de que la request salió no se reintenta: si la
 * cola llegó a procesarlo, reservó un pedido que este worker nunca vio, y pedir otro dejaría
 * dos reservados sin saberlo. Sólo se reintenta cuando el fallo es de conexión —
 * {@link ConnectException}, {@link UnknownHostException}— que son los casos en que la request
 * <b>no salió</b>. Un {@code responder}, en cambio, se reintenta siempre: el segundo intento
 * lo descarta la cola con un {@code 409 desconocido}, que es inofensivo.
 *
 * <h2>HTTP/1.1 explícito</h2>
 * El {@code HttpClient} del JDK negocia HTTP/2 por defecto. El servicio de colas es Python y
 * puede estar sobre un servidor que no lo hable: el intento de upgrade se paga en cada
 * request y, con algunos servidores simples, directamente falla. Acá no hace falta HTTP/2 —
 * no hay streams que multiplexar— así que se fija 1.1 y listo.
 */
public final class ClienteCola {

    /** La cola no respondió, o respondió algo que no se entiende. Se reintenta con backoff. */
    public static final class ColaNoDisponible extends RuntimeException {
        public ColaNoDisponible(String mensaje) {
            super(mensaje);
        }

        public ColaNoDisponible(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }

    /**
     * El clúster declara un major de contrato distinto del que implementa este worker. No se
     * reintenta: es fatal a propósito. Operar contra un contrato desconocido significa que
     * algún campo cambió de nombre o que un código cambió de significado, y seguir adelante
     * sería adivinar en silencio — exactamente lo que la sección de versionado pide no hacer.
     */
    public static final class ContratoIncompatible extends RuntimeException {
        public ContratoIncompatible(String mensaje) {
            super(mensaje);
        }
    }

    /** Qué pasó al devolver la respuesta. */
    public enum Entrega {
        /** {@code 202 entregada}. La cola la aceptó y se la va a dar al balanceador. */
        ENTREGADA,
        /**
         * {@code 409 desconocido}. El pedido ya lo contestó otro: se nos venció la reserva y
         * llegamos segundos. La cola se queda con la primera a propósito, así que reintentar
         * no tiene sentido. <b>No es un error del worker.</b>
         */
        DESCARTADA,
        /**
         * {@code 409 destinatario-saturado}. El balanceador no está recolectando. La
         * respuesta es buena; el que no la está levantando es el otro extremo.
         */
        SATURADO
    }

    /** Lo que dice {@code GET /health} de un nodo. */
    public static final class Salud {
        public final String rol;
        public final String masterConocido;
        public final String contrato;
        public final String instancia;

        Salud(String rol, String masterConocido, String contrato, String instancia) {
            this.rol = rol;
            this.masterConocido = masterConocido;
            this.contrato = contrato;
            this.instancia = instancia;
        }
    }

    private static final String RUTA_TOMAR = "/pedidos/tomar";
    private static final String RUTA_RESPONDER = "/respuestas";
    private static final String RUTA_DEVOLVER = "/pedidos/devolver";
    private static final String RUTA_SALUD = "/health";

    private final List<URI> semillas;
    private final String consumidor;
    private final HttpClient http;

    /**
     * El master, cacheado. {@code volatile} y no por hilo: los consumidores comparten el
     * descubrimiento, así que cuando uno se entera de que el master cambió, los demás dejan
     * de pegarle al viejo sin tener que chocarse cada uno con su propio 421.
     */
    private volatile URI maestro;

    /** Para avisar una sola vez del contrato que no se pudo verificar al arrancar. */
    private final AtomicBoolean avisoContrato = new AtomicBoolean(false);

    public ClienteCola(String urls, String consumidor) {
        this.semillas = semillasDe(urls);
        this.consumidor = consumidor;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** Los nodos de la seed list: uno por coma. Con uno solo funciona igual. */
    static List<URI> semillasDe(String urls) {
        List<URI> lista = new ArrayList<>();
        for (String parte : urls.split(",")) {
            String base = parte.strip();
            if (base.isEmpty()) {
                continue;
            }
            while (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            URI uri = URI.create(base);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException(
                        "URL de cola sin esquema o sin host: " + parte.strip());
            }
            lista.add(uri);
        }
        if (lista.isEmpty()) {
            throw new IllegalArgumentException("TP_COLA_URLS no trae ninguna URL");
        }
        return List.copyOf(lista);
    }

    public String destino() {
        return semillas.stream().map(URI::toString).collect(Collectors.joining(" · "));
    }

    public int cuantosNodos() {
        return semillas.size();
    }

    /** El master que se está usando, o null si todavía no se lo encontró. */
    public String maestroActual() {
        URI m = maestro;
        return m == null ? null : m.toString();
    }

    // --- arranque ----------------------------------------------------------------------

    /**
     * Ubica el master y compara el major del contrato. Devuelve una línea para el log.
     *
     * <p>Un major distinto es <b>fatal</b>. No llegar a ninguno, en cambio, no lo es: el
     * clúster puede estar eligiendo líder o esta casa puede haber arrancado antes que la
     * cola, y un worker que se niega a arrancar porque la cola no está todavía es un worker
     * que hay que levantar a mano justo cuando la cola vuelve. Se avisa y se sigue; el ciclo
     * normal reintenta con backoff y verifica de nuevo cuando por fin conteste.
     */
    public String verificar() {
        Salud salud = buscarSalud();
        if (salud == null) {
            return "no se pudo hablar con ningún nodo todavía — se reintenta con backoff";
        }
        exigirContrato(salud);
        URI m = maestro;
        return "contrato " + salud.contrato + " · master " + (m == null ? "(en elección)" : m)
                + (salud.instancia == null ? "" : " · " + salud.instancia);
    }

    /**
     * Compara el major declarado con el que implementa este worker. El minor no se mira: por
     * la regla de versionado de ellos, un minor nuevo sólo agrega campos opcionales, y
     * campos de más no rompen a nadie.
     */
    private void exigirContrato(Salud salud) {
        if (salud.contrato == null || salud.contrato.isBlank()) {
            if (avisoContrato.compareAndSet(false, true)) {
                System.out.println("[cola] el nodo no declara versión de contrato: se asume "
                        + Config.COLA_CONTRATO + ".x");
            }
            return;
        }
        String major = salud.contrato.split("\\.")[0].strip();
        if (!major.equals(Config.COLA_CONTRATO)) {
            throw new ContratoIncompatible("la cola habla contrato " + salud.contrato
                    + " y este worker implementa el " + Config.COLA_CONTRATO + ".x."
                    + " Un major distinto significa que algún campo cambió de nombre o que un"
                    + " código cambió de significado: hay que actualizar el worker, no forzarlo"
                    + " (o TP_COLA_CONTRATO=" + major + " si ya se revisó que es compatible).");
        }
    }

    // --- las tres operaciones ----------------------------------------------------------

    /**
     * Toma el próximo pedido. Devuelve null si no hubo trabajo en {@code espera} segundos —
     * que es lo normal y no un error.
     *
     * <p>El timeout de la request es la espera más un margen: si fuera igual, el worker
     * cortaría por su cuenta justo cuando la cola está por contestar que no hay nada, y cada
     * vuelta en vacío parecería una caída del clúster.
     */
    public Tarea tomar(int espera) {
        JsonObject cuerpo = new JsonObject();
        cuerpo.addProperty("consumidor", consumidor);
        cuerpo.addProperty("espera", espera);

        // reintentarSiSeCorta = false: un tomar que se corta después de salir pudo haber
        // reservado un pedido que nunca vimos.
        HttpResponse<String> respuesta = pedir(RUTA_TOMAR, cuerpo.toString(),
                Duration.ofSeconds(espera + 10L), false);
        int codigo = respuesta.statusCode();

        if (codigo == 204) {
            return null;
        }
        if (codigo != 200) {
            throw new ColaNoDisponible(explicar(codigo, respuesta.body(), "pedir trabajo"));
        }

        JsonObject sobre = objetoDe(respuesta.body());
        if (sobre == null) {
            // 200 con cuerpo vacío no está en el contrato, pero significar otra cosa que "no
            // hay nada" sería peor: se trata como un 204 y se vuelve a llamar.
            return null;
        }
        Tarea tarea = Tarea.desde(sobre, maestro);
        if (!tarea.valida()) {
            throw new ColaNoDisponible("la cola entregó un pedido sin id o sin operación: "
                    + recortar(respuesta.body()));
        }
        return tarea;
    }

    /**
     * Devuelve la tarea resuelta.
     *
     * <p>No lleva {@code destinatario}: lo pone la cola con lo que guardó del pedido. El
     * worker no tiene por qué saber quién lo pidió, y si se lo preguntáramos podría apuntar a
     * otro balanceador y meterle una respuesta ajena.
     */
    public Entrega responder(Tarea tarea, Ejecutor.Resuelta resuelta) {
        JsonObject cuerpo = new JsonObject();
        cuerpo.addProperty("id", tarea.id);
        cuerpo.addProperty("estado", resuelta.estado);
        cuerpo.add("contenido", resuelta.contenido);
        cuerpo.addProperty("atendidoPor", consumidor);
        cuerpo.addProperty("app", Config.APP);

        // Acá sí se reintenta si la conexión se corta: si la cola llegó a procesarla, el
        // segundo intento vuelve con 409 desconocido y no pasa nada.
        HttpResponse<String> respuesta = pedir(RUTA_RESPONDER, cuerpo.toString(),
                Duration.ofSeconds(10), true);
        int codigo = respuesta.statusCode();

        if (codigo >= 200 && codigo < 300) {
            return Entrega.ENTREGADA;
        }
        if (codigo == 409) {
            // Los dos 409 se distinguen POR EL CUERPO y no por el status: uno dice "llegaste
            // tarde, tirala" y el otro "el de enfrente no está levantando respuestas".
            // Tratarlos igual haría desaparecer en silencio respuestas que sí valían.
            String resultado = textoDe(objetoDe(respuesta.body()), "resultado");
            if ("destinatario-saturado".equals(resultado)) {
                return Entrega.SATURADO;
            }
            return Entrega.DESCARTADA;
        }
        throw new ColaNoDisponible(explicar(codigo, respuesta.body(), "entregar la respuesta"));
    }

    /**
     * Suelta un pedido sin atenderlo. Se usa al apagarse: soltarlo lo devuelve al pool y otro
     * worker lo toma en el acto, mientras que quedarse callado obliga a esperar a que venza
     * la reserva — y, si la operación no es idempotente, la cola ni siquiera la reintenta: el
     * cliente se come un DEADLINE_EXCEEDED por una réplica que se apagó ordenadamente.
     *
     * <p>Es una optimización, no una garantía: si no se llega a devolver, el recuperador de
     * la cola lo hace igual, sólo más tarde. Por eso nunca tira excepción.
     */
    public boolean devolver(Tarea tarea) {
        try {
            JsonObject cuerpo = new JsonObject();
            cuerpo.addProperty("id", tarea.id);
            cuerpo.addProperty("consumidor", consumidor);

            HttpResponse<String> respuesta = pedir(RUTA_DEVOLVER, cuerpo.toString(),
                    Duration.ofSeconds(5), true);
            int codigo = respuesta.statusCode();
            if (codigo >= 200 && codigo < 300) {
                return true;
            }
            if (codigo == 409) {
                // "no-estaba-en-vuelo": la reserva ya venció. No hay nada que hacer.
                return false;
            }
            System.out.println("[cola] no se pudo devolver la " + tarea + ": "
                    + explicar(codigo, respuesta.body(), "devolver el pedido"));
            return false;
        } catch (RuntimeException e) {
            System.out.println("[cola] no se pudo devolver la " + tarea + ": " + e.getMessage());
            return false;
        }
    }

    // --- descubrimiento del master -----------------------------------------------------

    /**
     * Un POST al master, siguiendo el redirect si el caché quedó viejo. Dos vueltas como
     * mucho: una para el intento normal y otra para el redirect o el redescubrimiento. Más
     * vueltas no ayudarían — si el master cambió dos veces en el tiempo de una request, lo
     * que hace falta es esperar, y de eso se encarga el backoff del consumidor.
     */
    private HttpResponse<String> pedir(String ruta, String cuerpo, Duration timeout,
                                       boolean reintentarSiSeCorta) {
        RuntimeException ultimo = null;

        for (int vuelta = 0; vuelta < 2; vuelta++) {
            URI actual = maestro;
            if (actual == null) {
                actual = ubicarMaestro();
                if (actual == null) {
                    throw new ColaNoDisponible(
                            "el clúster no tiene master (elección en curso o todos caídos)");
                }
            }

            HttpResponse<String> respuesta;
            try {
                respuesta = enviar(HttpRequest.newBuilder()
                        .uri(actual.resolve(ruta))
                        .timeout(timeout)
                        .header("Content-Type", "application/json; charset=utf-8")
                        .header("Accept", "application/json")
                        .header(Config.COLA_AUTH, valorDelToken())
                        .POST(HttpRequest.BodyPublishers.ofString(cuerpo, StandardCharsets.UTF_8))
                        .build());
            } catch (ColaNoDisponible e) {
                // El master se cayó: hay que redescubrirlo. Pero sólo se repite la operación
                // si el fallo dice que la request NO salió.
                maestro = null;
                if (Thread.currentThread().isInterrupted()) {
                    throw e;
                }
                if (!reintentarSiSeCorta && !noSalio(e)) {
                    throw e;
                }
                ultimo = e;
                continue;
            }

            if (respuesta.statusCode() == 421) {
                // "no-soy-master": el cuerpo trae a quién hay que preguntarle.
                String nuevo = textoDe(objetoDe(respuesta.body()), "master");
                maestro = (nuevo == null || nuevo.isBlank()) ? null : URI.create(nuevo);
                if (maestro == null) {
                    throw new ColaNoDisponible("hay un cambio de master en curso");
                }
                ultimo = new ColaNoDisponible("el master cambió a " + maestro);
                continue;
            }

            maestro = actual;
            return respuesta;
        }
        throw ultimo != null ? ultimo : new ColaNoDisponible("no se pudo ubicar al master");
    }

    /** Recorre la seed list hasta dar con el master. null si el clúster está en elección. */
    private URI ubicarMaestro() {
        Salud salud = buscarSalud();
        if (salud != null) {
            exigirContrato(salud);
        }
        return maestro;
    }

    /**
     * Pregunta {@code /health} nodo por nodo. Deja el master en el caché si lo encuentra y
     * devuelve la salud del primer nodo que haya contestado — que sirve para la versión del
     * contrato aunque ese nodo sea un slave.
     */
    private Salud buscarSalud() {
        Salud primera = null;
        for (URI semilla : semillas) {
            Salud salud;
            try {
                salud = salud(semilla);
            } catch (RuntimeException e) {
                continue; // nodo caído: el siguiente
            }
            if (primera == null) {
                primera = salud;
            }
            if ("master".equals(salud.rol)) {
                maestro = semilla;
                return salud;
            }
            if (salud.masterConocido != null && !salud.masterConocido.isBlank()) {
                maestro = URI.create(salud.masterConocido);
                return salud;
            }
        }
        return primera; // alguien contestó pero nadie sabe quién manda: elección en curso
    }

    /** {@code GET /health} de un nodo. No lleva token: es la ruta de descubrimiento. */
    public Salud salud(URI nodo) {
        HttpResponse<String> respuesta = enviar(HttpRequest.newBuilder()
                .uri(nodo.resolve(RUTA_SALUD))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .GET()
                .build());
        if (respuesta.statusCode() != 200) {
            throw new ColaNoDisponible(nodo.getHost() + " respondió "
                    + respuesta.statusCode() + " en " + RUTA_SALUD);
        }
        JsonObject o = objetoDe(respuesta.body());
        if (o == null) {
            throw new ColaNoDisponible(nodo.getHost() + " devolvió un " + RUTA_SALUD + " vacío");
        }
        return new Salud(textoDe(o, "rol"), textoDe(o, "masterConocido"),
                textoDe(o, "contrato"), textoDe(o, "instancia"));
    }

    // --- interno -----------------------------------------------------------------------

    /**
     * El valor del header de la credencial. Por el contrato va pelado
     * ({@code X-Cola-Token: <token>}); el esquema queda configurable por si cambian de idea,
     * porque sigue siendo especificación de otro equipo.
     */
    private static String valorDelToken() {
        return Config.COLA_ESQUEMA.isBlank()
                ? Config.COLA_TOKEN
                : Config.COLA_ESQUEMA + " " + Config.COLA_TOKEN;
    }

    /**
     * Un mensaje que dice qué hacer, no sólo qué pasó. El {@code 403} es el que más se va a
     * ver mientras se ajusta el entorno, y el que peor se diagnostica solo: el token de
     * consumidor habilita únicamente las rutas del worker.
     *
     * <p>Nunca imprime el token: el log va a un archivo del disco de la casa y una credencial
     * en claro ahí no se borra nunca más.
     */
    private static String explicar(int codigo, String cuerpo, String que) {
        if (codigo == 403) {
            return "la cola rechazó la credencial al " + que + " (403)"
                    + (Config.COLA_TOKEN.isBlank()
                       ? " y no se mandó ninguna: falta TP_COLA_TOKEN"
                       : " — revisar TP_COLA_TOKEN (va en el header " + Config.COLA_AUTH + ")");
        }
        return "la cola respondió " + codigo + " al " + que + ": " + recortar(cuerpo);
    }

    /**
     * ¿El fallo garantiza que la request no llegó a salir? Sólo entonces se puede repetir un
     * {@code tomar} sin arriesgar dos pedidos reservados. Un timeout no cuenta: la request
     * salió y no sabemos si la procesaron.
     */
    private static boolean noSalio(Throwable e) {
        for (Throwable causa = e; causa != null; causa = causa.getCause()) {
            if (causa instanceof ConnectException || causa instanceof UnknownHostException) {
                return true;
            }
            if (causa == causa.getCause()) {
                break;
            }
        }
        return false;
    }

    private HttpResponse<String> enviar(HttpRequest pedido) {
        try {
            return http.send(pedido, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ColaNoDisponible("interrumpido hablando con la cola", e);
        } catch (Exception e) {
            throw new ColaNoDisponible(e.toString(), e);
        }
    }

    /** El objeto del cuerpo, o null si no hay nada que leer. */
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

    private static String textoDe(JsonObject o, String clave) {
        if (o == null) {
            return null;
        }
        JsonElement e = o.get(clave);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
            return null;
        }
        return e.getAsString();
    }

    /** Para que un cuerpo de error enorme no llene la bitácora de una línea. */
    private static String recortar(String texto) {
        if (texto == null || texto.isBlank()) {
            return "(vacío)";
        }
        String limpio = texto.strip().replaceAll("\\s+", " ");
        return limpio.length() <= 200 ? limpio : limpio.substring(0, 200) + "…";
    }
}
