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
    public static final int VERSION = 3;
    public static final String MENSAJE = "hola mundo java";

    /**
     * Un Integrante por persona: el legajo es un campo propio y no un dato embutido
     * en el string del nombre, que obligaría al cliente a parsear por paréntesis.
     * TODO: completar los legajos reales antes de la demo.
     */
    public static final String[][] EQUIPO = {
            {"María Agustina", "Ortiz", "0"},
            {"Justino", "Bernal", "0"},
            {"Sebastián", "—", "0"},
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
