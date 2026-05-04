package utorrent.transport;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Capa de Transporte. Fábrica centralizada de pools de hilos del sistema.
 *
 * <p>Cumple la especificación del documento (P3, Capa de Transporte y Serialización):
 * <ul>
 *   <li>{@code ExecutorService} con pool fijo</li>
 *   <li>Un hilo por conexión de peer entrante</li>
 *   <li>Evita crear y destruir hilos constantemente</li>
 * </ul>
 *
 * <p>Centralizar la creación de pools en una sola clase permite:
 * <ul>
 *   <li>Nombrar los hilos para depuración (visible en stack traces y profilers).</li>
 *   <li>Ajustar políticas globales (tamaño, prioridad, daemon) en un solo punto.</li>
 *   <li>Documentar coherentemente cuántos hilos consume cada subsistema.</li>
 * </ul>
 */
public final class ThreadPool {

    /** Tamaño por defecto del pool de PeerConnectionHandler en cada cliente. */
    public static final int DEFAULT_PEER_POOL_SIZE = 16;

    /** Tamaño por defecto del pool del TrackerServer. */
    public static final int DEFAULT_TRACKER_POOL_SIZE = 32;

    private ThreadPool() { /* utilidad */ }

    /** Pool fijo para los PeerConnectionHandler de un peer cliente. */
    public static ExecutorService newPeerPool() {
        return newPeerPool(DEFAULT_PEER_POOL_SIZE);
    }

    public static ExecutorService newPeerPool(int size) {
        return Executors.newFixedThreadPool(size, namedThreadFactory("peer-handler"));
    }

    /** Pool fijo del TrackerServer (un hilo por conexión entrante). */
    public static ExecutorService newTrackerPool() {
        return newTrackerPool(DEFAULT_TRACKER_POOL_SIZE);
    }

    public static ExecutorService newTrackerPool(int size) {
        return Executors.newFixedThreadPool(size, namedThreadFactory("tracker-handler"));
    }

    /** Single-threaded executor para el accept-loop del servidor de peers entrantes. */
    public static ExecutorService newAcceptExecutor(String name) {
        return Executors.newSingleThreadExecutor(namedThreadFactory(name));
    }

    /** ThreadFactory que asigna nombres legibles a los hilos para diagnóstico. */
    private static ThreadFactory namedThreadFactory(final String prefix) {
        return new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(1);
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, prefix + "-" + counter.getAndIncrement());
                t.setDaemon(false);
                return t;
            }
        };
    }
}