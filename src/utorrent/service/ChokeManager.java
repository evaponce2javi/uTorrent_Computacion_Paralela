package utorrent.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Capa de Servicio (compartida F1/F2). Implementa el algoritmo tit-for-tat:
 *
 * <ul>
 *   <li>Mantiene 4 slots de unchoke regular para los peers que más datos nos aportan.</li>
 *   <li>Rota un unchoke optimista cada 30 s entre los peers chocados (descubrimiento).</li>
 *   <li>Recalcula los slots regulares cada 10 s en base a las tasas de descarga reportadas.</li>
 * </ul>
 *
 * <h3>Fase de warm-up (bootstrap)</h3>
 *
 * <p>Al iniciar la sesión no existe historial de tasas de descarga, por lo que tit-for-tat
 * "puro" rechazaría a todos los peers nuevos hasta el primer recalculo (10 s). Para evitar
 * un arranque frío, se aplica una <b>fase de warm-up</b>: los primeros peers que se
 * registran (hasta llenar los 4 slots regulares) reciben un unchoke inicial gratuito.
 *
 * <p>Esta concesión es estándar en clientes BitTorrent reales (libtorrent, qBittorrent,
 * Transmission) y se conoce como "<i>initial unchoke seed</i>". A partir del primer
 * recalculo de slots ({@link #recomputeRegular()}), el comportamiento es tit-for-tat
 * estricto: solo permanecen unchoked los peers que efectivamente nos están aportando
 * bytes.
 *
 * <p>Solo los peers en {@link #unchokedPeers} (o el {@link #optimisticPeer}) reciben datos
 * cuando piden piezas — ver {@link PeerConnectionHandler#serveLoop()}.
 */
public class ChokeManager {

    private static final int REGULAR_SLOTS = 4;
    private static final int REGULAR_INTERVAL_S = 10;
    private static final int OPTIMISTIC_INTERVAL_S = 30;

    /** peerId → bytes descargados desde el último ciclo (para ranking tit-for-tat). */
    private final ConcurrentHashMap<String, Long> downloadRate = new ConcurrentHashMap<>();
    private final Set<String> unchokedPeers = ConcurrentHashMap.newKeySet();
    private final Set<String> knownPeers    = ConcurrentHashMap.newKeySet();
    private volatile String optimisticPeer;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public void start() {
        scheduler.scheduleAtFixedRate(this::recomputeRegular,
                REGULAR_INTERVAL_S, REGULAR_INTERVAL_S, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::rotateOptimistic,
                OPTIMISTIC_INTERVAL_S, OPTIMISTIC_INTERVAL_S, TimeUnit.SECONDS);
    }

    public void stop() { scheduler.shutdownNow(); }

    /**
     * Registra un peer recién conectado. Durante la fase de warm-up (mientras haya
     * slots libres antes del primer recalculo), recibe unchoke inmediato. Después
     * del primer ciclo, los nuevos peers entran chocados hasta demostrar reciprocidad
     * o ser elegidos como unchoke optimista.
     */
    public void registerPeer(String peerId) {
        knownPeers.add(peerId);
        downloadRate.putIfAbsent(peerId, 0L);
        if (unchokedPeers.size() < REGULAR_SLOTS) {
            unchokedPeers.add(peerId); // warm-up: slot inicial
        }
    }

    public void recordDownloadFromPeer(String peerId, long bytes) {
        downloadRate.merge(peerId, bytes, Long::sum);
    }

    public boolean isUnchoked(String peerId) {
        return unchokedPeers.contains(peerId) || peerId.equals(optimisticPeer);
    }

    /** Tit-for-tat estricto: top-4 por bytes descargados desde el último ciclo. */
    private void recomputeRegular() {
        List<Map.Entry<String, Long>> ranked = new ArrayList<>(downloadRate.entrySet());
        ranked.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

        Set<String> next = new HashSet<>();
        for (int i = 0; i < Math.min(REGULAR_SLOTS, ranked.size()); i++) {
            // Solo entran al top quienes efectivamente aportaron bytes (>0).
            if (ranked.get(i).getValue() > 0) next.add(ranked.get(i).getKey());
        }

        unchokedPeers.clear();
        unchokedPeers.addAll(next);
        downloadRate.replaceAll((k, v) -> 0L); // reset ventana

        System.out.println("[Choke] Unchoke regular (tit-for-tat estricto): " + next);
    }

    /** Selecciona aleatoriamente un peer chocado para darle una oportunidad. */
    private void rotateOptimistic() {
        List<String> candidates = new ArrayList<>();
        for (String p : knownPeers) if (!unchokedPeers.contains(p)) candidates.add(p);
        if (candidates.isEmpty()) { optimisticPeer = null; return; }
        optimisticPeer = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        System.out.println("[Choke] Unchoke optimista: " + optimisticPeer);
    }
}