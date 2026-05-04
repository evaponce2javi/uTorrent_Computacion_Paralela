package utorrent.app;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import utorrent.dto.PeerInfo;
import utorrent.model.TorrentMetadata;
import utorrent.service.*;
import utorrent.transport.SocketDataChannel;
import utorrent.transport.ThreadPool;

/**
 * Capa de Aplicación. Orquestador central. Coordina F1 (descarga) y F2 (announce)
 * y arranca un servidor de peers entrantes para servir piezas a otros nodos.
 * Es el único componente que conoce el estado global de la sesión.
 *
 * <p>Los pools de hilos son provistos por {@link ThreadPool}, centralizando la
 * gestión de concurrencia en la capa de transporte.
 */
public class TorrentManager {

    public enum Mode { SEEDER, LEECHER }

    private final TorrentMetadata torrent;
    private final String peerId;
    private final int listenPort;
    private final String trackerHost;
    private final int trackerPort;
    private final Mode mode;
    private final String dataDir;

    // Componentes inyectados
    private PieceManager pieceManager;
    private FileAssembler fileAssembler;
    private IntegrityChecker integrityChecker;
    private ChokeManager chokeManager;
    private TrackerClient trackerClient;
    private SwarmScheduler swarmScheduler;

    // Pools provistos por ThreadPool centralizado
    private final ExecutorService peerHandlerPool   = ThreadPool.newPeerPool();
    private final ExecutorService inboundAcceptPool = ThreadPool.newAcceptExecutor("inbound-accept");
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket inboundServer;

    public TorrentManager(TorrentMetadata torrent, String peerId, int listenPort,
                          String trackerHost, int trackerPort, Mode mode, String dataDir) {
        this.torrent = torrent;
        this.peerId = peerId;
        this.listenPort = listenPort;
        this.trackerHost = trackerHost;
        this.trackerPort = trackerPort;
        this.mode = mode;
        this.dataDir = dataDir;
    }

    public void start() throws Exception {
        running.set(true);

        // Construcción de la cadena F1
        fileAssembler = new FileAssembler(dataDir, torrent);
        if (mode == Mode.SEEDER) fileAssembler.openExistingForReading();
        else fileAssembler.openNewForWriting();

        integrityChecker = new IntegrityChecker();
        pieceManager = new PieceManager(torrent, mode == Mode.SEEDER);
        chokeManager = new ChokeManager();
        chokeManager.start();

        // Construcción de la cadena F2
        swarmScheduler = new SwarmScheduler();
        trackerClient = new TrackerClient(trackerHost, trackerPort, torrent.infoHash, peerId, listenPort, swarmScheduler);

        // Servidor de peers entrantes
        startInboundServer();

        // F2 — announce inicial
        long left = mode == Mode.SEEDER ? 0 : torrent.totalLength;
        trackerClient.announce(0, 0, left, "started");

        // F1 — solo si somos leecher
        if (mode == Mode.LEECHER) startDownloadLoop();
    }

    private void startInboundServer() throws IOException {
        inboundServer = new ServerSocket(listenPort);
        inboundAcceptPool.submit(() -> {
            System.out.println("[Manager] Escuchando peers entrantes en puerto " + listenPort);
            while (running.get()) {
                try {
                    Socket s = inboundServer.accept();
                    SocketDataChannel ch = new SocketDataChannel(s);
                    peerHandlerPool.submit(new PeerConnectionHandler(
                            ch, torrent, peerId, pieceManager, integrityChecker,
                            fileAssembler, chokeManager, true /*inbound*/));
                } catch (IOException e) {
                    if (running.get()) System.err.println("[Manager] accept error: " + e);
                }
            }
        });
    }

    private void startDownloadLoop() {
        Thread orchestrator = new Thread(() -> {
            while (running.get() && !pieceManager.isComplete()) {
                List<PeerInfo> peers = swarmScheduler.getPeers();
                for (PeerInfo p : peers) {
                    if (p.peerId.equals(peerId)) continue;
                    if (swarmScheduler.isAlreadyConnected(p.peerId)) continue;
                    swarmScheduler.markConnected(p.peerId);
                    peerHandlerPool.submit(() -> {
                        try {
                            Socket s = new Socket(p.ip, p.port);
                            SocketDataChannel ch = new SocketDataChannel(s);
                            new PeerConnectionHandler(ch, torrent, peerId, pieceManager,
                                    integrityChecker, fileAssembler, chokeManager, false).run();
                        } catch (IOException e) {
                            System.err.println("[Manager] No se pudo conectar a " + p + ": " + e.getMessage());
                        } finally {
                            swarmScheduler.markDisconnected(p.peerId);
                        }
                    });
                }
                try { Thread.sleep(2000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (pieceManager.isComplete()) {
                System.out.println("[Manager] ✓ Descarga completa. Anunciando 'completed'.");
                trackerClient.announce(0, torrent.totalLength, 0, "completed");
                fileAssembler.flush();
            }
        }, "download-orchestrator");
        orchestrator.setDaemon(false);
        orchestrator.start();
    }

    public void stop() {
        running.set(false);
        try { trackerClient.announce(0, 0, 0, "stopped"); } catch (Exception ignored) {}
        chokeManager.stop();
        try { if (inboundServer != null) inboundServer.close(); } catch (IOException ignored) {}
        peerHandlerPool.shutdownNow();
        inboundAcceptPool.shutdownNow();
        fileAssembler.close();
    }

    public TorrentMetadata getTorrent()       { return torrent; }
    public PieceManager   getPieceManager()   { return pieceManager; }
}