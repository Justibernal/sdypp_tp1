package ar.edu.unlu.sdypp;

import java.net.InetAddress;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Configuración de la instancia. Los valores de acá son contrato: ver CONTRATO.md.
 * La App Python devuelve los mismos campos, con los mismos números de campo del .proto.
 */
public final class Config {

    public static final String APP = "java";
    public static final String LENGUAJE = "Java " + System.getProperty("java.version");
    public static final int VERSION = 8;
    public static final String MENSAJE = "hola mundo java v8";

    /**
     * Un Integrante por persona: el legajo es un campo propio y no un dato embutido
     * en el string del nombre, que obligaría al cliente a parsear por paréntesis.
     * TODO: falta el legajo de Federico. El campo es int32, así que un legajo ausente
     * llega como 0 — no se puede distinguir de "no lo mandaron".
     */
    public static final String[][] EQUIPO = {
            {"María Agustina", "Ortiz", "199523"},
            {"Justino", "Bernal", "190118"},
            {"Federico Nicolás", "Kasparian", "0"},
    };

    /**
     * Zona horaria fija y no la del sistema: si una casa tuviera el reloj en UTC y
     * otra en -03:00, los timestamps de la bitácora no se podrían cruzar entre casas.
     * Es contrato (CONTRATO.md §1).
     */
    public static final ZoneId ZONA = ZoneId.of("America/Argentina/Buenos_Aires");

    /**
     * Formato explícito y no OffsetDateTime.toString(): el ISO por defecto de Java
     * OMITE los segundos cuando valen cero ("...T16:35-03:00"), y el contrato pide
     * precisión de segundos siempre. Una de cada sesenta líneas saldría distinta.
     */
    private static final DateTimeFormatter ISO_SEGUNDOS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    public static final String HOST = env("HOST_NAME", hostnameDelSistema());
    public static final String CASA = env("CASA", "casa-desconocida");
    public static final String REDIS_URL = env("TP_REDIS_URL", "");
    public static final String DIRECTORIO_LOGS = env("TP_LOGS", "logs");
    public static final int WORKERS = Integer.parseInt(env("TP_WORKERS", "10"));

    /**
     * Worker de la cola. La cola es un servicio de otro equipo, así que TODO lo que
     * depende de su especificación entra por variable de entorno: el día que publiquen una
     * URL distinta, o cambien el nombre de un parámetro, no hay que recompilar nada.
     *
     * COLA_CONSUMIDOR identifica a este worker ante la cola. Lleva el HOST_NAME y no la
     * CASA porque la cola cuenta los pedidos en vuelo por consumidor: dos workers de la
     * misma casa tienen que poder distinguirse, igual que las réplicas en la bitácora
     * (CONTRATO.md §5).
     *
     * La <b>seed list</b>: los nodos del clúster de colas, separados por coma. No son
     * réplicas equivalentes — sólo el <b>master</b> atiende pedidos y respuestas, y los
     * demás redirigen con 421. La lista sirve para encontrarlo al arrancar y para volver a
     * encontrarlo cuando el master cambia. Con un solo elemento funciona igual.
     *
     * <p>Se lee de {@code TP_COLA_URLS}, que es el nombre que usa la guía de arranque del
     * grupo, y si no está se cae a {@code TP_COLA_URL}, que es como se llamaba acá antes de
     * que la cola fuera un clúster. Aceptar los dos cuesta una línea y evita el peor tipo de
     * fallo de configuración: el que no dice nada y deja al worker sin arrancar porque una
     * variable se llama en singular.
     */
    public static final String COLA_URL = env("TP_COLA_URLS", env("TP_COLA_URL", ""));

    /**
     * Cómo se identifica este worker ante la cola.
     *
     * <p><b>No es un nombre libre.</b> El contrato del equipo de colas pide el
     * {@code host:puerto} gRPC de la réplica, porque es el mismo string que el balanceador
     * tiene como {@code destino} en su registro: es lo que le permite cruzar "esta réplica
     * está sana" con "esta réplica consumió N pedidos". Un string inventado deja a la
     * réplica figurando como sana y sin consumir nada.
     *
     * <p>El default viejo ({@code java@host}) ya no sirve, y por eso no hay default: es
     * preferible que el worker no arranque a que arranque mintiendo un identificador que
     * nadie va a poder cruzar.
     */
    public static final String COLA_CONSUMIDOR = env("TP_COLA_CONSUMIDOR", "");

    /**
     * Credencial contra la cola: va en el header {@code X-Cola-Token}, sin prefijo. El
     * nombre y el esquema quedan configurables igual, porque siguen siendo especificación
     * de otro equipo y cambiarlos no tiene por qué costar una recompilación.
     *
     * El token NO va al repo: entra por el entorno de cada casa, igual que la contraseña
     * de la base.
     */
    public static final String COLA_TOKEN = env("TP_COLA_TOKEN", "");
    public static final String COLA_AUTH = env("TP_COLA_AUTH", "X-Cola-Token");
    public static final String COLA_ESQUEMA = env("TP_COLA_ESQUEMA", "");

    /**
     * Major del contrato de la cola contra el que está programado este worker. Al arrancar
     * se compara con el que declara {@code GET /health}: si difiere, el worker falla
     * ruidosamente en vez de operar contra un contrato desconocido, que es lo que pide la
     * sección de versionado. Un major distinto significa que algún campo cambió de nombre o
     * que un código cambió de significado — seguir adelante sería adivinar.
     */
    public static final String COLA_CONTRATO = env("TP_COLA_CONTRATO", "1");

    public static final int COLA_HILOS = Integer.parseInt(env("TP_COLA_HILOS", "2"));

    /** Segundos de long-polling. El contrato pone el techo en 30. */
    public static final int COLA_ESPERA =
            Math.min(30, Math.max(1, Integer.parseInt(env("TP_COLA_ESPERA", "20"))));

    public static final int COLA_ADMIN = Integer.parseInt(env("TP_COLA_ADMIN", "9091"));

    /** Momento de arranque de esta réplica. Se calcula una sola vez. */
    public static final String ARRANCADO = ahoraIso();

    // Límites de validación de CONTRATO.md §3.
    public static final int LEGAJO_MIN = 1;
    public static final int LEGAJO_MAX = 2147483647; // tope de int32, el tipo del campo
    public static final int NOMBRE_MAX = 120;

    private Config() {
    }

    public static String ahoraIso() {
        return OffsetDateTime.now(ZONA).format(ISO_SEGUNDOS);
    }

    /** Puerto: primer argumento de CLI; si no, PORT; si no, 8080 (CONTRATO.md §1). */
    public static int puerto(String[] args) {
        if (args.length > 0) {
            return Integer.parseInt(args[0]);
        }
        return Integer.parseInt(env("PORT", "8080"));
    }

    /**
     * Recorta la contraseña de una URL antes de escribirla en el log: TP_REDIS_URL
     * lleva la credencial adentro y el log va a un archivo del disco de la casa.
     */
    public static String urlSinCredenciales(String url) {
        try {
            URI u = URI.create(url);
            String puerto = u.getPort() > 0 ? ":" + u.getPort() : "";
            return u.getScheme() + "://" + (u.getHost() == null ? "?" : u.getHost()) + puerto;
        } catch (RuntimeException e) {
            return "(url ilegible)";
        }
    }

    private static String env(String clave, String porDefecto) {
        String valor = System.getenv(clave);
        return (valor == null || valor.isBlank()) ? porDefecto : valor;
    }

    private static String hostnameDelSistema() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "host-desconocido";
        }
    }
}
