package utorrent.transport;

import java.util.concurrent.Callable;

/**
 * Capa de Transporte. Centraliza la política de reintentos con backoff exponencial:
 * 5s → 15s → 30s. Tras tres fallos consecutivos retorna {@code null}, dejando que
 * el llamador (típicamente {@link utorrent.service.TrackerClient}) active el fallback
 * a bootstrap peers.
 */
public class FaultHandler {

    /** Esperas en milisegundos antes de cada reintento. */
    public static final long[] BACKOFF_MS = {5_000L, 15_000L, 30_000L};

    public static final int CONNECT_TIMEOUT_MS = 5_000;
    public static final int READ_TIMEOUT_MS    = 30_000;

    /**
     * Ejecuta una tarea con la política de backoff. Si todos los intentos fallan,
     * retorna {@code null}.
     */
    public static <T> T withRetries(Callable<T> task, String operationName) {
        for (int attempt = 0; attempt < BACKOFF_MS.length; attempt++) {
            try {
                if (attempt > 0) {
                    long wait = BACKOFF_MS[attempt - 1];
                    System.out.printf("[FaultHandler] %s: esperando %d ms antes del intento %d%n",
                            operationName, wait, attempt + 1);
                    Thread.sleep(wait);
                }
                return task.call();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                System.err.printf("[FaultHandler] %s falló (intento %d/%d): %s%n",
                        operationName, attempt + 1, BACKOFF_MS.length, e.getMessage());
            }
        }
        System.err.printf("[FaultHandler] %s: agotados los reintentos. Fallback activado.%n", operationName);
        return null;
    }
}