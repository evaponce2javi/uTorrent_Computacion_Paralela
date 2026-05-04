package utorrent.app;

import java.nio.file.Paths;
import java.util.Random;
import utorrent.model.TorrentMetadata;

/**
 * Capa de Aplicación. Punto de entrada CLI del peer.
 *
 * <p>Argumentos:
 * <pre>
 *   --torrent <path>      (default: ./mock/mock.torrent)
 *   --port <n>            (default: 6881)
 *   --tracker <host:port> (default: localhost:6969)
 *   --mode seeder|leecher (default: leecher)
 *   --data-dir <path>     (default: ./data/&lt;peerId&gt;)
 *   --peer-id <str>       (default: aleatorio de 20 chars)
 * </pre>
 */
public class TorrentClient {

    public static void main(String[] args) throws Exception {
        // Defaults
        String torrentPath = "./mock/mock.torrent";
        int port = 6881;
        String tracker = "localhost:6969";
        TorrentManager.Mode mode = TorrentManager.Mode.LEECHER;
        String peerId = generatePeerId();
        String dataDir = null;

        // Parseo CLI
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--torrent":  torrentPath = args[++i]; break;
                case "--port":     port = Integer.parseInt(args[++i]); break;
                case "--tracker":  tracker = args[++i]; break;
                case "--mode":     mode = TorrentManager.Mode.valueOf(args[++i].toUpperCase()); break;
                case "--peer-id":  peerId = args[++i]; break;
                case "--data-dir": dataDir = args[++i]; break;
            }
        }
        if (dataDir == null) dataDir = "./data/" + peerId;

        String[] tk = tracker.split(":");
        String trackerHost = tk[0];
        int trackerPort = Integer.parseInt(tk[1]);

        // Lectura del torrent
        TorrentParser parser = new TorrentParser();
        TorrentMetadata meta = parser.parse(Paths.get(torrentPath));
        System.out.println("[Client] " + meta);
        System.out.println("[Client] peerId=" + peerId + " mode=" + mode + " port=" + port);

        TorrentManager manager = new TorrentManager(meta, peerId, port, trackerHost, trackerPort, mode, dataDir);
        Runtime.getRuntime().addShutdownHook(new Thread(manager::stop));
        manager.start();

        // Mantener vivo
        Thread.currentThread().join();
    }

    private static String generatePeerId() {
        Random r = new Random();
        StringBuilder sb = new StringBuilder("-UT0001-");
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (int i = 0; i < 12; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }
}