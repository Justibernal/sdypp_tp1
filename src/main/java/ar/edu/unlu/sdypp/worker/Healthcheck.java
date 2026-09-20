package ar.edu.unlu.sdypp.worker;

import ar.edu.unlu.sdypp.Config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * HEALTHCHECK del contenedor del worker. Consulta su propio panel, no la cola.
 *
 * <p>El worker no expone un servicio gRPC, así que no sirve el {@code Healthcheck} del
 * servidor: no hay {@code grpc.health.v1.Health} que consultar. Y tampoco alcanza con "el
 * proceso vive", que es lo único que sabe Docker sin un chequeo: un worker con todos sus
 * consumidores muertos por una excepción sigue teniendo el proceso arriba y no toma un solo
 * pedido más.
 *
 * <p>Se usa el cliente HTTP del JDK y no curl por lo mismo que el otro: no agrega una
 * descarga externa a la imagen.
 *
 * <p>Sale 0 si el panel contesta 200, y 1 en cualquier otro caso — incluido el 503 del
 * apagado, que es justo lo que hace que el orquestador deje de contarlo como vivo mientras
 * drena.
 */
public final class Healthcheck {

    private Healthcheck() {
    }

    public static void main(String[] args) {
        String destino = args.length > 0
                ? args[0]
                : "http://localhost:" + Config.COLA_ADMIN + "/salud";
        int salida = 1;
        try {
            HttpResponse<String> respuesta = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(3))
                    .build()
                    .send(HttpRequest.newBuilder()
                                    .uri(URI.create(destino))
                                    .timeout(Duration.ofSeconds(3))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            System.out.println(respuesta.statusCode() + " " + respuesta.body());
            salida = respuesta.statusCode() == 200 ? 0 : 1;
        } catch (Exception e) {
            System.out.println("NO_RESPONDE: " + e.getMessage());
        }
        System.exit(salida);
    }
}
