package utorrent.service;

import utorrent.dto.AnnounceRequest;
import utorrent.dto.AnnounceResponse;
import utorrent.transport.FaultHandler;
import utorrent.transport.SocketDataChannel;

import java.net.Socket;
import java.net.InetSocketAddress;

/**
 * Capa de Servicio (F2). Anuncia el peer al tracker y recibe la lista de peers.
 *
 * <p>Aplica la política de reintentos con backoff exponencial (5s/15s/30s) gestionada
 * por {@link FaultHandler}. Si los tres intentos fallan, deja que {@link SwarmScheduler}
 * use la lista bootstrap estática.
 */
public class TrackerClient {

    private final String trackerHost;
    private final int trackerPort;
    private final String infoHash;
    private final String peerId;
    private final int listenPort;
    private final SwarmScheduler swarmScheduler;

    public TrackerClient(String trackerHost, int trackerPort, String infoHash,
                         String peerId, int listenPort, SwarmScheduler scheduler) {
        this.trackerHost = trackerHost;
        this.trackerPort = trackerPort;
        this.infoHash = infoHash;
        this.peerId = peerId;
        this.listenPort = listenPort;
        this.swarmScheduler = scheduler;
    }

    public AnnounceResponse announce(long uploaded, long downloaded, long left, String event) {
        AnnounceRequest req = new AnnounceRequest(infoHash, peerId, listenPort,
                uploaded, downloaded, left, event);

        AnnounceResponse resp = FaultHandler.withRetries(() -> {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(trackerHost, trackerPort), FaultHandler.CONNECT_TIMEOUT_MS);
            try (SocketDataChannel ch = new SocketDataChannel(s)) {
                ch.setReadTimeout(FaultHandler.READ_TIMEOUT_MS);
                ch.send(req);
                Object reply = ch.receive();
                if (!(reply instanceof AnnounceResponse))
                    throw new IllegalStateException("Respuesta inválida del tracker");
                return (AnnounceResponse) reply;
            }
        }, "Announce '" + event + "' a " + trackerHost + ":" + trackerPort);

        if (resp != null && resp.ok) {
            swarmScheduler.updatePeers(resp.peers);
            System.out.printf("[TrackerClient] OK · peers recibidos=%d · próximo announce en %ds%n",
                    resp.peers.size(), resp.interval);
        } else {
            System.err.println("[TrackerClient] Fallback a bootstrap peers");
            swarmScheduler.activateBootstrapFallback();
        }
        return resp;
    }
}