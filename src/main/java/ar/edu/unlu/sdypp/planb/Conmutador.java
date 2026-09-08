package ar.edu.unlu.sdypp.planb;

import com.sun.net.httpserver.HttpServer;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PLAN B — conmutador/balanceador propio para la demo local.
 *
 * <p><b>Esto no reemplaza al balanceador del equipo Plataforma.</b> Es el plan B declarado
 * del enunciado: nos permite demostrar las Etapas 1 y 2 desde una sola máquina si la red
 * entre casas —o el balanceador— no llega. Las "casas" quedan simuladas como contenedores
 * separados, y lo que se pierde con eso va dicho en la presentación.
 *
 * <h2>Plano de datos: proxy TCP (L4), no proxy gRPC (L7)</h2>
 * Reenvía bytes sin entender el protocolo. Un proxy gRPC real tendría que hablar HTTP/2 y
 * multiplexar streams, que es bastante más que las "menos de cien líneas" que estima el
 * enunciado. Lo que se paga por el camino corto (CONTRATO.md §7):
 * <ul>
 *   <li>No sabe qué RPC pasó: la bitácora registra <b>conexiones</b>, no operaciones.</li>
 *   <li>Reparte <b>por conexión</b>, no por RPC. Un cliente gRPC abre un canal y lo reusa
 *       para todo, así que queda pegado a una réplica. Es exactamente lo que avisa el
 *       §7.3 del contrato — y en la demo se ve.</li>
 * </ul>
 *
 * <h2>Plano de control: HTTP/JSON</h2>
 * El plano de datos es TCP crudo, pero el de administración es un HttpServer del JDK — el
 * mismo {@code com.sun.net.httpserver} de la App de la Clase 1. Separar los dos planos es
 * lo que permite cambiar de destino <b>sin reiniciar</b>: el deploy le habla al admin
 * mientras el proxy sigue atendiendo.
 *
 * <pre>
 *   java -cp target/app-java.jar ar.edu.unlu.sdypp.planb.Conmutador 8080 9090 localhost:8111,localhost:8112
 *
 *   curl -s localhost:9090/estado
 *   curl -s -X POST localhost:9090/backends -d '{"backends":["localhost:8121","localhost:8122"]}'
 * </pre>
 */
public final class Conmutador {

    /** Un backend del pool y su estado de salud. */
    private static final class Backend {
        final String destino;
        final String host;
        final int puerto;
        volatile boolean sano = false;   // arranca caído: nadie recibe tráfico sin pasar un health check
        volatile long conexiones = 0;

        /** Fallos seguidos del health check. Ver UMBRAL_FALLOS. */
        volatile int fallosSeguidos = 0;

        /**
         * Un canal por backend, abierto una vez y reusado. Crear un ManagedChannel en cada
         * chequeo cuesta un handshake HTTP/2 completo cada 3 segundos por réplica, y bajo
         * carga ese costo hace que el propio chequeo se pase de deadline y declare muerto a
         * un backend que está perfectamente vivo.
         */
        final ManagedChannel canal;

        Backend(String destino) {
            this.destino = destino;
            String[] partes = destino.split(":");
            this.host = partes[0];
            this.puerto = Integer.parseInt(partes[1]);
            this.canal = Grpc.newChannelBuilder(destino, InsecureChannelCredentials.create()).build();
        }

        void cerrar() {
            canal.shutdownNow();
        }
    }

    /**
     * Cuántos chequeos seguidos tienen que fallar para sacar un backend de rotación.
     *
     * Con umbral 1, un pico de latencia o un GC de la réplica bastan para expulsarla, y el
     * balanceador termina apagando el servicio que tenía que sostener — nos pasó: bajo carga
     * los chequeos se pasaban de deadline y el pool quedaba vacío con las dos réplicas sanas.
     * Es la pregunta del enunciado: cuánto se espera antes de declararlo muerto.
     */
    private static final int UMBRAL_FALLOS = 3;

    private static final DateTimeFormatter ISO_SEGUNDOS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final ZoneId ZONA = ZoneId.of("America/Argentina/Buenos_Aires");

    private static final String CASA = env("CASA", "casa-desconocida");
    private static final Path BITACORA =
            Path.of(env("TP_LOGS", "logs"), "bitacora-conmutador.log");

    /**
     * La lista viva de backends. CopyOnWriteArrayList: el hilo del admin la reemplaza
     * entera mientras decenas de hilos de conexión la están recorriendo. Con un ArrayList
     * común, una conmutación en medio de una ráfaga tira ConcurrentModificationException
     * justo cuando el servicio tiene que seguir respondiendo.
     */
    private static final CopyOnWriteArrayList<Backend> BACKENDS = new CopyOnWriteArrayList<>();

    /**
     * El contador del round-robin: un dato compartido entre todos los hilos que atienden.
     * AtomicInteger y no int: con un entero común, dos hilos leen el mismo valor, los dos
     * mandan al mismo backend y el incremento de uno se pierde. Es la condición de carrera
     * que pregunta el enunciado, y la respuesta es exclusión mutua — acá resuelta con una
     * instrucción atómica del hardware en vez de un lock, que sería más caro.
     */
    private static final AtomicInteger PROXIMO = new AtomicInteger(0);

    private static final Object CANDADO_BITACORA = new Object();

    /**
     * Writer abierto una vez y reusado, en vez de abrir y cerrar el archivo en cada línea.
     * Con una conexión por request y un open/write/close por conexión, el balanceador se
     * pasa más tiempo hablando con el disco que reenviando bytes: nos tiró el servicio
     * entero bajo carga. El buffer se vuelca cada segundo, así el log sigue siendo útil en
     * vivo durante la demo sin costar una syscall por línea.
     */
    private static java.io.BufferedWriter ESCRITOR;

    private Conmutador() {
    }

    public static void main(String[] args) throws Exception {
        int puertoPublico = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        int puertoAdmin = args.length > 1 ? Integer.parseInt(args[1]) : 9090;
        if (args.length > 2) {
            reemplazarBackends(List.of(args[2].split(",")));
        }

        abrirBitacora();
        arrancarChequeosDeSalud();
        arrancarAdmin(puertoAdmin);

        System.out.printf("Conmutador (PLAN B) escuchando en 0.0.0.0:%d · admin en :%d%n",
                puertoPublico, puertoAdmin);
        System.out.println("[conmutador] backends: " + destinos());
        System.out.println("[bitacora] " + BITACORA.toAbsolutePath());

        // Un hilo por conexión. Con pool acotado, N conexiones simultáneas mayores al pool
        // se quedan esperando sin que nadie las atienda; acá cada conexión vive lo que dura.
        ExecutorService hilos = Executors.newCachedThreadPool();
        try (ServerSocket servidor = new ServerSocket(puertoPublico)) {
            while (true) {
                Socket cliente = servidor.accept();
                hilos.submit(() -> atender(cliente));
            }
        }
    }

    // --- plano de datos -------------------------------------------------------

    private static void atender(Socket cliente) {
        // Reintento corto: durante una conmutación hay unos milisegundos en los que el
        // pool nuevo todavía no pasó su primer chequeo. Rechazar en seco ahí convierte el
        // deploy sin downtime en un deploy con downtime cortito, que es lo mismo.
        Backend destino = null;
        for (int intento = 0; intento < 20 && destino == null; intento++) {
            destino = elegir();
            if (destino == null) {
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (destino == null) {
            // Ningún backend sano: se cierra la conexión en vez de dejarla colgada. El
            // cliente ve UNAVAILABLE enseguida y puede reintentar, que es mejor que un
            // timeout de 30 segundos sin saber por qué.
            bitacora("CONEXION", "SIN_BACKENDS", "-");
            cerrar(cliente);
            return;
        }

        try (Socket cliente_ = cliente;
             Socket backend = new Socket(destino.host, destino.puerto)) {
            destino.conexiones++;
            bitacora("CONEXION", "OK", destino.destino);

            // TCP es full-duplex: los dos sentidos van a la vez. Un hilo bombea de ida y
            // este mismo hilo bombea de vuelta; si se hiciera secuencial, el proxy se
            // trabaría esperando a que un lado terminase de hablar.
            Thread ida = new Thread(() -> bombear(cliente_, backend));
            ida.start();
            bombear(backend, cliente_);
            ida.join();
        } catch (Exception e) {
            // El backend se cayó entre el health check y esta conexión: se lo marca caído
            // ya mismo (detección pasiva) y el chequeo activo confirmará después.
            // Detección pasiva: se falló al conectar. Suma un strike, no lo mata de una:
            // una conexión que se cae puede ser un pico, y el chequeo activo confirma.
            if (++destino.fallosSeguidos >= UMBRAL_FALLOS) {
                destino.sano = false;
            }
            bitacora("CONEXION", "BACKEND_CAIDO", destino.destino);
        }
    }

    /** Round-robin sobre los sanos. La que muere sale de rotación sola. */
    private static Backend elegir() {
        List<Backend> sanos = new ArrayList<>();
        for (Backend b : BACKENDS) {
            if (b.sano) {
                sanos.add(b);
            }
        }
        if (sanos.isEmpty()) {
            return null;
        }
        // getAndIncrement es atómico; el módulo se hace sobre el valor ya reservado, así
        // dos hilos nunca se llevan el mismo índice.
        int i = Math.floorMod(PROXIMO.getAndIncrement(), sanos.size());
        return sanos.get(i);
    }

    private static void bombear(Socket desde, Socket hacia) {
        byte[] buffer = new byte[16 * 1024];
        try (InputStream in = desde.getInputStream(); OutputStream out = hacia.getOutputStream()) {
            int leidos;
            while ((leidos = in.read(buffer)) != -1) {
                out.write(buffer, 0, leidos);
                out.flush();
            }
        } catch (IOException e) {
            // Fin de conexión: normal cuando cualquiera de las dos puntas cierra.
        } finally {
            cerrar(hacia);
        }
    }

    private static void cerrar(Socket s) {
        try {
            s.close();
        } catch (IOException ignorado) {
        }
    }

    // --- chequeos de salud ----------------------------------------------------

    /**
     * Detección activa: pregunta cada 3 segundos en vez de esperar a que falle una request.
     * Se usa {@code grpc.health.v1.Health} y no un simple "¿abre el socket TCP?": un proceso
     * puede tener el puerto abierto y estar sin base, o drenando. Es además lo que pide el
     * contrato (§7.2) y lo mismo que consulta el HEALTHCHECK del contenedor: así el
     * contenedor y el balanceador no pueden opinar distinto sobre si la réplica está sana.
     */
    private static void arrancarChequeosDeSalud() {
        ScheduledExecutorService reloj = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "health");
            t.setDaemon(true);
            return t;
        });
        reloj.scheduleAtFixedRate(() -> {
            for (Backend b : BACKENDS) {
                boolean antes = b.sano;
                if (consultarSalud(b)) {
                    // Una sola respuesta buena alcanza para volver: reincorporar rápido es
                    // barato (si sigue mal, el próximo chequeo la vuelve a sacar), mientras
                    // que expulsar rápido es caro porque achica el pool.
                    b.fallosSeguidos = 0;
                    b.sano = true;
                } else if (++b.fallosSeguidos >= UMBRAL_FALLOS) {
                    b.sano = false;
                }
                if (antes != b.sano) {
                    System.out.printf("[health] %s -> %s%n", b.destino, b.sano ? "SANO" : "CAIDO");
                    bitacora("HEALTH", b.sano ? "SERVING" : "NOT_SERVING", b.destino);
                }
            }
        }, 0, 3, TimeUnit.SECONDS);
    }

    private static boolean consultarSalud(Backend b) {
        try {
            HealthCheckResponse r = HealthGrpc.newBlockingStub(b.canal)
                    .withDeadlineAfter(2, TimeUnit.SECONDS)
                    .check(HealthCheckRequest.newBuilder().setService("").build());
            return r.getStatus() == HealthCheckResponse.ServingStatus.SERVING;
        } catch (Exception e) {
            return false;
        }
    }

    // --- plano de control -----------------------------------------------------

    /**
     * Admin HTTP. Es lo que hace que el conmutador cambie de destino <b>sin reiniciarse</b>:
     * el deploy.sh le hace un POST cuando la versión nueva ya pasó el verify.
     */
    private static void arrancarAdmin(int puerto) throws IOException {
        HttpServer admin = HttpServer.create(new InetSocketAddress("127.0.0.1", puerto), 0);
        // Sólo 127.0.0.1: el plano de control no se expone a la red. Un POST a /backends
        // redirige TODO el tráfico del servicio a donde quiera el que lo mande.

        admin.createContext("/estado", intercambio -> {
            StringBuilder json = new StringBuilder("{\"backends\":[");
            for (int i = 0; i < BACKENDS.size(); i++) {
                Backend b = BACKENDS.get(i);
                json.append(i > 0 ? "," : "")
                        .append("{\"destino\":\"").append(b.destino)
                        .append("\",\"sano\":").append(b.sano)
                        .append(",\"conexiones\":").append(b.conexiones).append("}");
            }
            json.append("]}");
            responder(intercambio, 200, json.toString());
        });

        admin.createContext("/backends", intercambio -> {
            if (!"POST".equals(intercambio.getRequestMethod())) {
                responder(intercambio, 405, "{\"error\":\"metodo no permitido\"}");
                return;
            }
            String cuerpo = new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            List<String> nuevos = new ArrayList<>();
            Matcher m = Pattern.compile("\"([^\"]+:\\d+)\"").matcher(cuerpo);
            while (m.find()) {
                nuevos.add(m.group(1));
            }
            if (nuevos.isEmpty()) {
                responder(intercambio, 400, "{\"error\":\"se requiere backends\"}");
                return;
            }
            reemplazarBackends(nuevos);
            System.out.println("[conmutador] destino cambiado a " + nuevos);
            bitacora("CONMUTAR", "OK", String.join(",", nuevos));
            responder(intercambio, 200, "{\"ok\":true}");
        });

        admin.setExecutor(Executors.newSingleThreadExecutor());
        admin.start();
    }

    private static void responder(com.sun.net.httpserver.HttpExchange intercambio, int codigo, String json)
            throws IOException {
        byte[] cuerpo = json.getBytes(StandardCharsets.UTF_8);
        intercambio.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        intercambio.sendResponseHeaders(codigo, cuerpo.length);
        try (OutputStream out = intercambio.getResponseBody()) {
            out.write(cuerpo);
        }
    }

    /**
     * Reemplaza la lista entera de una. Los backends nuevos entran con {@code sano=false} y
     * no reciben nada hasta pasar un health check: conmutar a un destino que todavía no
     * respondió sería cambiar downtime por downtime.
     */
    private static void reemplazarBackends(List<String> destinos) {
        List<Backend> nuevos = new ArrayList<>();
        for (String d : destinos) {
            nuevos.add(new Backend(d.trim()));
        }
        List<Backend> viejos = new ArrayList<>(BACKENDS);

        // Los nuevos se chequean ANTES de publicarlos: si se publicaran caídos, el pool
        // queda vacío hasta el primer chequeo y las requests de esos milisegundos se caen.
        for (Backend b : nuevos) {
            b.sano = consultarSalud(b);
        }
        BACKENDS.clear();
        BACKENDS.addAll(nuevos);

        // Los canales viejos se cierran después de la conmutación, no antes: cerrarlos
        // primero dejaría al pool sin chequeos durante el cambio.
        for (Backend b : viejos) {
            b.cerrar();
        }
    }

    private static String destinos() {
        List<String> ds = new ArrayList<>();
        for (Backend b : BACKENDS) {
            ds.add(b.destino + (b.sano ? "(sano)" : "(caido)"));
        }
        return String.join(", ", ds);
    }

    // --- bitácora -------------------------------------------------------------

    /**
     * Mismo formato que el de las apps (CONTRATO.md §5), para poder cruzarlos. La diferencia
     * la impone el L4: la tercera columna dice CONEXION y no el nombre del RPC, porque a
     * este nivel no se ve qué RPC pasó. La quinta columna lleva a quién se derivó, que es lo
     * que la auditoría necesita.
     */
    private static void abrirBitacora() throws IOException {
        if (BITACORA.getParent() != null) {
            Files.createDirectories(BITACORA.getParent());
        }
        ESCRITOR = Files.newBufferedWriter(BITACORA, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        ScheduledExecutorService volcado = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bitacora-flush");
            t.setDaemon(true);
            return t;
        });
        volcado.scheduleAtFixedRate(Conmutador::volcar, 1, 1, TimeUnit.SECONDS);
        // Sin este hook, lo que quedó en el buffer al cortar el proceso se pierde — y
        // justo las últimas líneas son las que interesan cuando algo salió mal.
        Runtime.getRuntime().addShutdownHook(new Thread(Conmutador::volcar));
    }

    private static void volcar() {
        try {
            synchronized (CANDADO_BITACORA) {
                if (ESCRITOR != null) {
                    ESCRITOR.flush();
                }
            }
        } catch (IOException ignorado) {
        }
    }

    private static void bitacora(String evento, String codigo, String backend) {
        String linea = OffsetDateTime.now(ZONA).format(ISO_SEGUNDOS)
                + " | conmutador@" + CASA
                + " | " + evento
                + " | " + codigo
                + " | backend=" + backend;
        try {
            synchronized (CANDADO_BITACORA) {
                if (ESCRITOR != null) {
                    ESCRITOR.write(linea);
                    ESCRITOR.newLine();
                }
            }
        } catch (IOException e) {
            System.out.println("[bitacora] no se pudo escribir: " + e);
        }
        // Los eventos raros sí van a pantalla; las conexiones no: un println por conexión
        // serializa todos los hilos sobre el lock de System.out.
        if (!"CONEXION".equals(evento) || !"OK".equals(codigo)) {
            System.out.println(linea);
        }
    }

    private static String env(String clave, String porDefecto) {
        String v = System.getenv(clave);
        return (v == null || v.isBlank()) ? porDefecto : v;
    }
}
