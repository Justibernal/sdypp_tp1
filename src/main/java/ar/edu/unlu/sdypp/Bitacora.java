package ar.edu.unlu.sdypp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Bitácora a disco (CONTRATO.md §5). Una línea por RPC atendido, en el disco local
 * del nodo — no en la base: si la bitácora dependiera de Redis, una caída de la base
 * dejaría al nodo sin poder registrar nada, justo cuando más falta hace el registro.
 *
 * Un archivo por réplica: dos réplicas del mismo nodo escribiendo el mismo archivo no
 * se pueden distinguir después, y distinguirlas es lo que la auditoría tiene que
 * demostrar cruzando este archivo con el log del balanceador.
 */
public final class Bitacora {

    private static final Path ARCHIVO =
            Path.of(Config.DIRECTORIO_LOGS, "bitacora-" + Config.HOST + ".log");

    /**
     * Monitor propio y no el objeto entero: varios hilos del pool gRPC escriben a la
     * vez y sin exclusión mutua dos líneas se intercalan a mitad de renglón. Es la
     * sección crítica del enunciado, acá en chiquito.
     */
    private static final Object CANDADO = new Object();

    private Bitacora() {
    }

    public static Path archivo() {
        return ARCHIVO;
    }

    public static void registrar(String rpc, String codigo) {
        registrar(rpc, codigo, null);
    }

    public static void registrar(String rpc, String codigo, Integer id) {
        registrar(rpc, codigo, id, null);
    }

    /**
     * Formato de CONTRATO.md §5, campo por campo:
     * {@code 2026-09-08T14:03:22-03:00 | java@casa-agustina | CrearPersona | OK | id=7}
     *
     * <p>El {@code extra} es un sexto campo opcional, y hoy lo usa sólo el worker para
     * anotar {@code tarea=<id>}. Los cinco campos del contrato quedan intactos y en el mismo
     * orden —lo que sigue funcionando para el que corta por columnas— pero la línea del
     * worker lleva además el id que le dio la cola: es el <b>id de correlación</b> que el
     * contrato no tiene y que la auditoría necesita para cruzar dos bitácoras sin depender
     * de que los relojes de dos casas coincidan. Es la mejora nº2 que le levantamos al
     * enunciado, resuelta acá porque la cola nos dio el id que faltaba.
     */
    public static void registrar(String rpc, String codigo, Integer id, String extra) {
        String linea = Config.ahoraIso()
                + " | " + Config.APP + "@" + Config.CASA
                + " | " + rpc
                + " | " + codigo
                + " | " + (id != null ? "id=" + id : "-")
                + (extra != null ? " | " + extra : "");
        try {
            synchronized (CANDADO) {
                if (ARCHIVO.getParent() != null) {
                    Files.createDirectories(ARCHIVO.getParent());
                }
                Files.writeString(ARCHIVO, linea + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            // Que no se pueda escribir la bitácora no puede tumbar el servicio.
            System.out.println("[bitacora] no se pudo escribir: " + e);
        }
        System.out.println(linea);
    }
}
