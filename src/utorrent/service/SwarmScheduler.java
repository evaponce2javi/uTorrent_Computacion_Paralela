package utorrent.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import utorrent.dto.PeerInfo;
import utorrent.util.JsonReader;

/**
 * Capa de Servicio (F2). Mantiene la lista de peers disponibles para
 * {@link utorrent.app.TorrentManager} y centraliza la resolución peerId → ip:port,
 * cumpliendo la <b>transparencia de ubicación</b>: ningún componente fuera de
 * SwarmScheduler conoce las direcciones físicas.
 *
 * <p>Si el tracker es inalcanzable (tras 3 reintentos con backoff), se activa el
 * fallback cargando {@code ./bootstrapPeers.json}. El formato esperado es un array
 * JSON de objetos con campos {@code peerId}, {@code ip}, {@code port}:
 *
 * <pre>{@code
 * [
 *   {"peerId": "SEED-0000000000000001", "ip": "127.0.0.1", "port": 6881},
 *   {"peerId": "LEECH-A00000000000001", "ip": "127.0.0.1", "port": 6882}
 * ]
 * }</pre>
 *
 * <p>Se admiten comentarios {@code //} y {@code /* *}{@code /} en el archivo para
 * permitir anotaciones humanas (extensión del JSON estricto, parseada por
 * {@link JsonReader}).
 */
public class SwarmScheduler {

    private static final Path BOOTSTRAP_FILE = Paths.get("./bootstrapPeers.json");

    private volatile List<PeerInfo> currentPeers = Collections.emptyList();
    private final Set<String> activeConnections = ConcurrentHashMap.newKeySet();

    public synchronized void updatePeers(List<PeerInfo> peers) {
        currentPeers = new ArrayList<>(peers);
    }

    public List<PeerInfo> getPeers() { return currentPeers; }

    public boolean isAlreadyConnected(String peerId)  { return activeConnections.contains(peerId); }
    public void    markConnected(String peerId)       { activeConnections.add(peerId); }
    public void    markDisconnected(String peerId)    { activeConnections.remove(peerId); }

    /**
     * Carga la lista estática de peers de respaldo si el tracker es inalcanzable.
     * Mitigación de Eclipse Attack: peers conocidos y confiables, definidos fuera
     * de banda respecto al tracker.
     */
    @SuppressWarnings("unchecked")
    public void activateBootstrapFallback() {
        if (!Files.exists(BOOTSTRAP_FILE)) {
            System.err.println("[Swarm] No existe " + BOOTSTRAP_FILE + " — sin peers de respaldo.");
            return;
        }
        try {
            Object root = JsonReader.readFile(BOOTSTRAP_FILE);
            if (!(root instanceof List)) {
                System.err.println("[Swarm] bootstrapPeers.json: raíz no es array");
                return;
            }
            List<PeerInfo> boot = new ArrayList<>();
            for (Object item : (List<Object>) root) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> obj = (Map<String, Object>) item;
                String peerId = (String) obj.get("peerId");
                String ip     = (String) obj.get("ip");
                Object portObj = obj.get("port");
                if (peerId == null || ip == null || portObj == null) continue;
                int port = ((Number) portObj).intValue();
                boot.add(new PeerInfo(peerId, ip, port));
            }
            updatePeers(boot);
            System.out.println("[Swarm] Bootstrap activado con " + boot.size() + " peers desde "
                    + BOOTSTRAP_FILE);
        } catch (IOException e) {
            System.err.println("[Swarm] Error leyendo bootstrap: " + e);
        } catch (RuntimeException e) {
            System.err.println("[Swarm] bootstrapPeers.json malformado: " + e.getMessage());
        }
    }
}