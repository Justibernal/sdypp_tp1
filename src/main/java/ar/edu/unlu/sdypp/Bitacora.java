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

    /**
     * Formato de CONTRATO.md §5, campo por campo:
     * {@code 2026-09-08T14:03:22-03:00 | java@casa-agustina | CrearPersona | OK | id=7}
     */
    public static void registrar(String rpc, String codigo, Integer id) {
        String linea = Config.ahoraIso()
                + " | " + Config.APP + "@" + Config.CASA
                + " | " + rpc
                + " | " + codigo
                + " | " + (id != null ? "id=" + id : "-");
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
