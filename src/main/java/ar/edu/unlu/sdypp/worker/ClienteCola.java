package ar.edu.unlu.sdypp.worker;

import ar.edu.unlu.sdypp.Config;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * El cliente HTTP contra el servicio de cola. <b>Todo lo que depende de la especificación
 * del otro equipo está acá adentro</b>: las rutas, los parámetros, los códigos y la forma
 * del cuerpo. El resto del worker habla de tareas, no de HTTP.
 *
 * <p>Tres operaciones, las tres contra la <b>misma URL</b> ({@code TP_COLA_URL}), que es
 * como quedó acordado:
 *
 * <pre>
 *   GET    ?consumidor=&lt;id&gt;&amp;espera=&lt;s&gt;   toma el próximo pedido (long-polling)
 *   POST                                    devuelve la tarea resuelta
 *   DELETE ?id=&lt;id&gt;&amp;consumidor=&lt;id&gt;      suelta un pedido sin resolver (al apagarse)
 * </pre>
 *
 * <h2>Por qué el GET es de long-polling</h2>
 * La cola implementa {@code tomar(consumidor, espera)}: el que pide trabajo se queda
 * esperando hasta {@code espera} segundos y lo despiertan apenas hay algo. Con polling
 * corto —preguntar cada 200 ms— el worker haría cientos de requests por minuto sin trabajo
 * y vería cada pedido con hasta 200 ms de retraso; con una sola request que espera 20 s no
 * hay ni latencia ni ruido.
 *
 * <h2>HTTP/1.1 explícito</h2>
 * El {@code HttpClient} del JDK negocia HTTP/2 por defecto. El servicio de cola es Python y
 * puede estar sobre un servidor que no lo hable: el intento de upgrade se paga en cada
 * request y, con algunos servidores simples, directamente falla. Acá no hace falta HTTP/2 —
 * no hay streams que multiplexar— así que se fija 1.1 y listo.
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
         * La cola ya no conoce esa tarea. No es un error del worker: la reserva venció, otra
         * réplica la resolvió y la nuestra llegó segunda. La cola descarta la segunda a
         * propósito ("gana la primera respuesta"), así que reintentar no tiene sentido.
         */
        DESCARTADA,
        /** No se pudo entregar: la cola no responde o el destinatario está saturado. */
        PERDIDA
    }

    private final URI url;
    private final String consumidor;
    private final HttpClient http;

    /** Para avisar una sola vez que la cola no expone devolución, y no en cada apagado. */
    private final AtomicBoolean avisoDevolucion = new AtomicBoolean(false);

    public ClienteCola(String url, String consumidor) {
        this.url = URI.create(url);
        this.consumidor = consumidor;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public String destino() {
        return url.toString();
    }

    /**
     * Toma el próximo pedido. Devuelve null si no hubo nada en {@code espera} segundos —
     * que es lo normal y no un error.
     *
     * <p>El timeout de la request es la espera más un margen: si fuera igual, el worker
     * cortaría por su cuenta justo cuando la cola está por contestar que no hay nada, y cada
     * vuelta en vacío parecería una caída de la cola.
     */
    public Tarea tomar(int espera) {
        HttpRequest pedido = HttpRequest.newBuilder()
                .uri(con("consumidor", consumidor, "espera", String.valueOf(espera)))
                .timeout(Duration.ofSeconds(espera + 10L))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> respuesta = enviar(pedido, "tomar");
        int codigo = respuesta.statusCode();

        // 204 es lo que corresponde para "no hay trabajo", pero también se aceptan un 200
        // con cuerpo vacío y un 404: el servicio todavía no publicó su especificación y las
        // tres respuestas significan lo mismo. Ninguna es motivo para gritar en el log.
        if (codigo == 204 || codigo == 404) {
            return null;
        }
        if (codigo == 429) {
            throw new ColaNoDisponible("la cola pidió bajar el ritmo (429)");
        }
        if (codigo != 200) {
            throw new ColaNoDisponible("la cola respondió " + codigo + " al pedir trabajo");
        }

        JsonObject sobre = objetoDe(respuesta.body());
        if (sobre == null) {
            return null;
        }
        Tarea tarea = Tarea.desde(sobre);
        if (!tarea.valida()) {
            throw new ColaNoDisponible("la cola entregó un pedido sin id o sin operación: "
                    + recortar(respuesta.body()));
        }
        return tarea;
    }

    /**
     * Devuelve la tarea resuelta. El cuerpo lleva sólo lo que el worker sabe: quién la
     * atendió y con qué resultado. El destinatario, los intentos y la espera los completa la
     * cola con lo que guardó del pedido — el worker no tiene por qué saber quién lo pidió, y
     * si se lo preguntáramos podría mentir.
     */
    public Entrega responder(Tarea tarea, Ejecutor.Resuelta resuelta) {
        JsonObject cuerpo = new JsonObject();
        cuerpo.addProperty("id", tarea.id);
        cuerpo.addProperty("estado", resuelta.estado);
        cuerpo.add("contenido", resuelta.contenido);
        cuerpo.addProperty("atendidoPor", consumidor);
        cuerpo.addProperty("app", Config.APP);

        HttpRequest pedido = HttpRequest.newBuilder()
                .uri(url)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(cuerpo.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> respuesta = enviar(pedido, "responder");
        int codigo = respuesta.statusCode();

        if (codigo >= 200 && codigo < 300) {
            return Entrega.ENTREGADA;
        }
        // 404/409/410/422: la tarea ya no existe del lado de la cola. Es el caso de la
        // respuesta que llega segunda, y la cola lo resuelve tirándola.
        if (codigo == 404 || codigo == 409 || codigo == 410 || codigo == 422) {
            return Entrega.DESCARTADA;
        }
        throw new ColaNoDisponible("la cola respondió " + codigo + " al recibir la respuesta: "
                + recortar(respuesta.body()));
    }

    /**
     * Suelta un pedido sin resolver. Se usa al apagarse: soltarlo lo manda al frente de la
     * cola y otro worker lo toma en el acto, mientras que quedarse callado obliga a esperar
     * a que venza la reserva —y, si la operación no es idempotente, la cola ni siquiera la
     * reintenta: el cliente se come un DEADLINE_EXCEEDED por una réplica que se apagó
     * ordenadamente.
     *
     * <p>Es lo único de las tres operaciones que la cola podría no exponer todavía. Si
     * contesta que no existe, se avisa una vez y se sigue: la reserva vence igual.
     */
    public boolean devolver(Tarea tarea) {
        try {
            HttpRequest pedido = HttpRequest.newBuilder()
                    .uri(con("id", tarea.id, "consumidor", consumidor))
                    .timeout(Duration.ofSeconds(5))
                    .DELETE()
                    .build();
            HttpResponse<String> respuesta = enviar(pedido, "devolver");
            int codigo = respuesta.statusCode();
            if (codigo >= 200 && codigo < 300) {
                return true;
            }
            if ((codigo == 404 || codigo == 405 || codigo == 501) && avisoDevolucion.compareAndSet(false, true)) {
                System.out.println("[cola] no expone devolución de pedidos (" + codigo
                        + "): los tomados sin resolver esperan a que venza la reserva");
            }
            return false;
        } catch (RuntimeException e) {
            System.out.println("[cola] no se pudo devolver la " + tarea + ": " + e.getMessage());
            return false;
        }
    }

    // --- interno -----------------------------------------------------------------------

    private HttpResponse<String> enviar(HttpRequest pedido, String que) {
        try {
            return http.send(pedido, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ColaNoDisponible("interrumpido al " + que, e);
        } catch (Exception e) {
            throw new ColaNoDisponible("no se pudo " + que + " contra " + url + ": " + e, e);
        }
    }

    /** La misma URL con parámetros de consulta, respetando los que ya trajera. */
    private URI con(String... pares) {
        StringBuilder consulta = new StringBuilder();
        for (int i = 0; i < pares.length; i += 2) {
            if (pares[i + 1] == null) {
                continue;
            }
            consulta.append(consulta.length() == 0 ? "" : "&")
                    .append(pares[i])
                    .append('=')
                    .append(URLEncoder.encode(pares[i + 1], StandardCharsets.UTF_8));
        }
        String base = url.toString();
        return URI.create(base + (base.contains("?") ? "&" : "?") + consulta);
    }

    /**
     * El objeto del cuerpo, o null si no hay pedido. Acepta que venga solo o dentro de una
     * lista: "dame uno" y "dame hasta N" son la misma llamada para el que espera, y no
     * cuesta nada entender las dos.
     */
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
        if (raiz.isJsonArray()) {
            JsonArray lista = raiz.getAsJsonArray();
            if (lista.isEmpty() || !lista.get(0).isJsonObject()) {
                return null;
            }
            return lista.get(0).getAsJsonObject();
        }
        if (!raiz.isJsonObject()) {
            return null;
        }
        JsonObject objeto = raiz.getAsJsonObject();
        if (objeto.isEmpty()) {
            return null;
        }
        return objeto;
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
