package utorrent.service;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import utorrent.dto.AnnounceRequest;
import utorrent.dto.AnnounceResponse;
import utorrent.dto.PeerInfo;
import utorrent.transport.SocketDataChannel;
import utorrent.transport.ThreadPool;

/**
 * Capa de Servicio (F2). Servidor centralizado que coordina el descubrimiento de peers.
 *
 * <p>Arquitectura: {@link ServerSocket} + {@link ExecutorService} provisto por
 * {@link ThreadPool#newTrackerPool()} (un hilo por conexión entrante). Cada hilo lee
 * un {@link AnnounceRequest}, actualiza el {@link PeerRegistry} de forma thread-safe
 * y responde con {@link AnnounceResponse} conteniendo la lista de peers activos.
 *
 * <p>Argumentos CLI:
 * <ul>
 *   <li>{@code --port <n>} (default 6969)</li>
 *   <li>{@code --max-peers-per-ip <n>} (default {@link PeerRegistry#DEFAULT_MAX_PEERS_PER_IP})</li>
 *   <li>{@code --pool-size <n>} (default {@link ThreadPool#DEFAULT_TRACKER_POOL_SIZE})</li>
 * </ul>
 */
public class TrackerServer {

    public static final int DEFAULT_PORT = 6969;
    private static final int ANNOUNCE_INTERVAL_SECONDS = 60;

    private final int port;
    private final ExecutorService pool;
    private final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor();
    private final PeerRegistry registry;
    private volatile boolean running = false;
    private ServerSocket serverSocket;

    public TrackerServer(int port) {
        this(port, PeerRegistry.DEFAULT_MAX_PEERS_PER_IP, ThreadPool.DEFAULT_TRACKER_POOL_SIZE);
    }

    public TrackerServer(int port, int maxPeersPerIp, int poolSize) {
        this.port = port;
        this.registry = new PeerRegistry(maxPeersPerIp);
        this.pool = ThreadPool.newTrackerPool(poolSize);
    }

    public void start() throws IOException {
        running = true;
        serverSocket = new ServerSocket(port);
        System.out.printf("[Tracker] Escuchando en puerto %d · maxPeersPerIp=%d%n",
                port, registry.getMaxPeersPerIp());

        // Reset de ventana anti-Sybil cada 60s.
        maintenance.scheduleAtFixedRate(registry::resetWindow, 60, 60, TimeUnit.SECONDS);

        while (running) {
            try {
                Socket client = serverSocket.accept();
                pool.submit(() -> handle(client));
            } catch (IOException e) {
                if (running) System.err.println("[Tracker] accept error: " + e);
            }
        }
    }

    private void handle(Socket socket) {
        try (SocketDataChannel ch = new SocketDataChannel(socket)) {
            ch.setReadTimeout(10_000);
            Object msg = ch.receive();
            if (!(msg instanceof AnnounceRequest)) {
                ch.send(new AnnounceResponse(false, "Mensaje inválido", 0, null));
                return;
            }
            AnnounceRequest req = (AnnounceRequest) msg;
            String ip = ch.getRemoteIp();
            PeerInfo peer = new PeerInfo(req.peerId, ip, req.port);

            try {
                if ("stopped".equals(req.event)) {
                    registry.unregister(req.infoHash, req.peerId);
                } else {
                    registry.register(req.infoHash, peer);
                }
                List<PeerInfo> peers = registry.getPeers(req.infoHash, req.peerId);
                ch.send(new AnnounceResponse(true, "OK", ANNOUNCE_INTERVAL_SECONDS, peers));
                System.out.printf("[Tracker] %-9s peer=%s ip=%s swarm=%s peers_activos=%d%n",
                        req.event, req.peerId.substring(0, Math.min(8, req.peerId.length())),
                        ip, req.infoHash.substring(0, 12) + "...", peers.size() + 1);
            } catch (SecurityException se) {
                ch.send(new AnnounceResponse(false, se.getMessage(), 0, null));
                System.err.println("[Tracker] Sybil bloqueado: " + se.getMessage());
            }
        } catch (Exception e) {
            System.err.println("[Tracker] handler error: " + e.getMessage());
        }
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        pool.shutdownNow();
        maintenance.shutdownNow();
    }

    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        int maxPeersPerIp = PeerRegistry.DEFAULT_MAX_PEERS_PER_IP;
        int poolSize = ThreadPool.DEFAULT_TRACKER_POOL_SIZE;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port":              port = Integer.parseInt(args[++i]); break;
                case "--max-peers-per-ip":  maxPeersPerIp = Integer.parseInt(args[++i]); break;
                case "--pool-size":         poolSize = Integer.parseInt(args[++i]); break;
            }
        }

        TrackerServer ts = new TrackerServer(port, maxPeersPerIp, poolSize);
        Runtime.getRuntime().addShutdownHook(new Thread(ts::stop));
        ts.start();
    }
}