package ar.edu.unlu.sdypp;

import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;

import java.util.concurrent.TimeUnit;

/**
 * HEALTHCHECK del contenedor. Consulta grpc.health.v1.Health — el estándar — y no el RPC
 * Salud del contrato: es el mismo chequeo que hace el balanceador, así el contenedor y el
 * balanceador no pueden opinar distinto sobre si la réplica está sana.
 *
 * No se puede usar curl: no hay HTTP que consultar, el mensaje va en binario sobre HTTP/2.
 *
 * Sale 0 si SERVING, 1 en cualquier otro caso (incluido NOT_SERVING durante el drenado).
 */
public final class Healthcheck {

    private Healthcheck() {
    }

    public static void main(String[] args) {
        String destino = args.length > 0 ? args[0] : "localhost:8080";
        ManagedChannel canal = Grpc.newChannelBuilder(destino, InsecureChannelCredentials.create()).build();
        int salida = 1;
        try {
            HealthCheckResponse r = HealthGrpc.newBlockingStub(canal)
                    .withDeadlineAfter(3, TimeUnit.SECONDS)
                    .check(HealthCheckRequest.newBuilder().setService("").build());
            System.out.println(r.getStatus());
            salida = r.getStatus() == HealthCheckResponse.ServingStatus.SERVING ? 0 : 1;
        } catch (Exception e) {
            System.out.println("NO_RESPONDE: " + e.getMessage());
        } finally {
            canal.shutdownNow();
        }
        System.exit(salida);
    }
}
