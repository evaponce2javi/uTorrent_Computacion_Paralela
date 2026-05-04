package utorrent.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import utorrent.dto.PeerInfo;

/**
 * Capa de Servicio (F2). Tabla concurrente de peers activos por infoHash.
 * Usa {@link ConcurrentHashMap} para evitar bloqueos explícitos en lecturas.
 *
 * <p>Implementa además rate limiting por IP en ventana de 60s para mitigar Sybil:
 * si una IP excede {@code maxPeersPerIp} registros dentro de la ventana, los
 * siguientes son rechazados con {@link SecurityException}.
 *
 * <p>El parámetro {@code maxPeersPerIp} es <b>configurable</b> al instanciar el
 * registry (valor por defecto: 3, alineado con el documento P10).
 */
public class PeerRegistry {

    /** Valor por defecto coherente con el documento (P10, "Configurable (ej. 3)"). */
    public static final int DEFAULT_MAX_PEERS_PER_IP = 3;
    public static final long WINDOW_MS = 60_000L;

    private final int maxPeersPerIp;

    /** infoHash → (peerId → PeerInfo). */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, PeerInfo>> registry = new ConcurrentHashMap<>();

    /** ip → contador en la ventana actual. */
    private final ConcurrentHashMap<String, AtomicInteger> ipCount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> ipWindowStart = new ConcurrentHashMap<>();

    public PeerRegistry() { this(DEFAULT_MAX_PEERS_PER_IP); }

    public PeerRegistry(int maxPeersPerIp) {
        if (maxPeersPerIp < 1) throw new IllegalArgumentException("maxPeersPerIp debe ser >= 1");
        this.maxPeersPerIp = maxPeersPerIp;
    }

    public int getMaxPeersPerIp() { return maxPeersPerIp; }

    public void register(String infoHash, PeerInfo peer) {
        enforceRateLimit(peer.ip);
        registry.computeIfAbsent(infoHash, k -> new ConcurrentHashMap<>())
                .put(peer.peerId, peer);
    }

    public void unregister(String infoHash, String peerId) {
        Map<String, PeerInfo> m = registry.get(infoHash);
        if (m != null) m.remove(peerId);
    }

    public List<PeerInfo> getPeers(String infoHash, String excludePeerId) {
        Map<String, PeerInfo> m = registry.get(infoHash);
        if (m == null) return Collections.emptyList();
        List<PeerInfo> result = new ArrayList<>();
        for (PeerInfo p : m.values()) if (!p.peerId.equals(excludePeerId)) result.add(p);
        return result;
    }

    public int countSwarms() { return registry.size(); }

    public int countPeers(String infoHash) {
        Map<String, PeerInfo> m = registry.get(infoHash);
        return m == null ? 0 : m.size();
    }

    /** Reset de la ventana cada 60 s. Llamado periódicamente por el TrackerServer. */
    public void resetWindow() {
        ipCount.clear();
        ipWindowStart.clear();
    }

    private synchronized void enforceRateLimit(String ip) {
        long now = System.currentTimeMillis();
        long start = ipWindowStart.getOrDefault(ip, 0L);
        if (now - start > WINDOW_MS) {
            ipWindowStart.put(ip, now);
            ipCount.put(ip, new AtomicInteger(0));
        }
        int c = ipCount.computeIfAbsent(ip, k -> new AtomicInteger(0)).incrementAndGet();
        if (c > maxPeersPerIp) {
            throw new SecurityException("Rate limit excedido para IP: " + ip
                    + " (" + c + " > " + maxPeersPerIp + ")");
        }
    }
}