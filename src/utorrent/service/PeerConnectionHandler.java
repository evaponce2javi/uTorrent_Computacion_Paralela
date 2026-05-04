package utorrent.service;

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.HashSet;
import java.util.Set;
import utorrent.dto.BitfieldMessage;
import utorrent.dto.HandshakeMessage;
import utorrent.dto.HaveMessage;
import utorrent.dto.PeerMessage;
import utorrent.dto.PieceData;
import utorrent.dto.RequestMessage;
import utorrent.model.TorrentMetadata;
import utorrent.transport.FaultHandler;
import utorrent.transport.SocketDataChannel;

/**
 * Capa de Servicio (F1). Un objeto de esta clase corre por cada peer remoto en su
 * propio hilo del pool. Ejecuta:
 *
 * <ol>
 *   <li>Handshake TCP (validación de infoHash).</li>
 *   <li>Intercambio de bitfields para conocer las piezas del peer.</li>
 *   <li>Loop de solicitud/recepción de piezas (rarest-first vía PieceManager).</li>
 *   <li>Verificación SHA-1 antes de escribir al FileAssembler.</li>
 *   <li>Manejo de fallos: SocketException/EOFException → marca peer caído y re-encola pieza.</li>
 * </ol>
 *
 * <p>Coherente con el diagrama UML F1: las solicitudes usan
 * {@link RequestMessage}{@code (pieceIndex, offset, blockLength)} (mensaje 8 del UML)
 * y las respuestas usan {@link PieceData} (mensaje 9 del UML).
 *
 * <p>El bucle de descarga ({@link #downloadLoop()}) sigue activo mientras existan
 * piezas que el peer remoto pueda servir y la descarga global no esté completa,
 * desacoplando la condición de salida ("este peer no tiene piezas útiles ahora")
 * de la condición global ("descarga completa"). Cuando el peer remoto agota sus
 * piezas útiles, el handler pasa a {@link #serveLoop()} para seguir sirviendo.
 */
public class PeerConnectionHandler implements Runnable {

    private final SocketDataChannel channel;
    private final TorrentMetadata torrent;
    private final String myPeerId;
    private final PieceManager pieceManager;
    private final IntegrityChecker integrityChecker;
    private final FileAssembler fileAssembler;
    private final ChokeManager chokeManager;
    private final boolean inbound;

    private final BitfieldHandler bitfieldHandler = new BitfieldHandler();
    private String remotePeerId;
    private Set<Integer> remoteAvailable = new HashSet<>();

    public PeerConnectionHandler(SocketDataChannel channel, TorrentMetadata torrent,
                                  String myPeerId, PieceManager pm, IntegrityChecker ic,
                                  FileAssembler fa, ChokeManager cm, boolean inbound) {
        this.channel = channel;
        this.torrent = torrent;
        this.myPeerId = myPeerId;
        this.pieceManager = pm;
        this.integrityChecker = ic;
        this.fileAssembler = fa;
        this.chokeManager = cm;
        this.inbound = inbound;
    }

    @Override
    public void run() {
        try {
            channel.setReadTimeout(FaultHandler.READ_TIMEOUT_MS);
            doHandshake();
            exchangeBitfields();

            // El leecher conduce el loop de pedidos; el seeder solo escucha.
            if (!inbound) downloadLoop();
            serveLoop();

        } catch (SocketTimeoutException ste) {
            System.err.println("[PCH] Timeout con " + remotePeerId + ": " + ste.getMessage());
        } catch (SocketException | EOFException ce) {
            System.err.println("[PCH] Peer " + remotePeerId + " cayó: " + ce.getMessage());
        } catch (Exception e) {
            System.err.println("[PCH] Error con " + remotePeerId + ": " + e);
        } finally {
            if (remotePeerId != null) pieceManager.removePeer(remotePeerId);
            channel.close();
        }
    }

    /* --------------------------------- handshake --------------------------------- */

    private void doHandshake() throws Exception {
        if (inbound) {
            HandshakeMessage their = (HandshakeMessage) channel.receive();
            if (!their.infoHash.equals(torrent.infoHash))
                throw new SecurityException("infoHash no coincide en handshake entrante");
            remotePeerId = their.peerId;
            channel.send(new HandshakeMessage(torrent.infoHash, myPeerId));
        } else {
            channel.send(new HandshakeMessage(torrent.infoHash, myPeerId));
            HandshakeMessage their = (HandshakeMessage) channel.receive();
            if (!their.infoHash.equals(torrent.infoHash))
                throw new SecurityException("infoHash no coincide en handshake saliente");
            remotePeerId = their.peerId;
        }
        chokeManager.registerPeer(remotePeerId);
        System.out.println("[PCH] Handshake OK con " + shortId(remotePeerId));
    }

    private void exchangeBitfields() throws Exception {
        // Yo envío mi bitfield primero
        byte[] myBitfield = bitfieldHandler.build(pieceManager);
        channel.send(new BitfieldMessage(myBitfield, pieceManager.totalPieces()));

        // Recibo el suyo
        BitfieldMessage their = (BitfieldMessage) channel.receive();
        remoteAvailable = bitfieldHandler.parse(their.bitfield, their.totalPieces);
        pieceManager.updateAvailability(remotePeerId, remoteAvailable);
        System.out.println("[PCH] " + shortId(remotePeerId) + " tiene " + remoteAvailable.size() + " piezas");
    }

    /* --------------------------------- download --------------------------------- */

    /**
     * Pide piezas al peer remoto hasta que (a) la descarga global esté completa
     * o (b) el peer remoto no tenga ninguna pieza útil que aún no posea este nodo.
     *
     * <p>El UML F1 modela un solo {@code RequestMessage(pieceIndex, offset, blockLength)}
     * por pieza, donde {@code offset = 0} y {@code blockLength = pieceLengthAt(pieceIndex)}.
     * Esto preserva la firma estándar BitTorrent dejando abierta la posibilidad de
     * granularidad por bloques de 16 KB en una iteración futura.
     */
    private void downloadLoop() throws Exception {
        while (!pieceManager.isComplete()) {
            int pieceIndex = pieceManager.selectNextPiece(remoteAvailable);
            if (pieceIndex == -1) {
                // Este peer no tiene piezas útiles ahora; salimos del downloadLoop
                // pero seguimos vivos en serveLoop para subir lo que tengamos.
                System.out.println("[PCH] " + shortId(remotePeerId) + " no aporta más piezas; pasando a serve.");
                return;
            }

            int offset = 0;
            int blockLength = (int) torrent.pieceLengthAt(pieceIndex);

            try {
                channel.send(new RequestMessage(pieceIndex, offset, blockLength));
                Object reply = channel.receive();

                if (!(reply instanceof PieceData)) {
                    pieceManager.requeue(pieceIndex);
                    continue;
                }
                PieceData piece = (PieceData) reply;
                if (piece.pieceIndex != pieceIndex) {
                    // El peer respondió con otra pieza; descartar y reintentar.
                    pieceManager.requeue(pieceIndex);
                    continue;
                }

                byte[] expected = pieceManager.expectedHash(pieceIndex);
                if (integrityChecker.verifySHA1(piece.data, expected)) {
                    fileAssembler.writePiece(pieceIndex, piece.data);
                    pieceManager.markCompleted(pieceIndex);
                    chokeManager.recordDownloadFromPeer(remotePeerId, piece.data.length);
                    System.out.printf("[PCH] ✓ pieza %d/%d desde %s (%d bytes)%n",
                            pieceIndex, pieceManager.totalPieces(), shortId(remotePeerId), piece.data.length);
                } else {
                    System.err.println("[PCH] ✗ Falla de VALOR en pieza " + pieceIndex
                            + " desde " + shortId(remotePeerId) + " — re-encolada");
                    pieceManager.requeue(pieceIndex);
                }
            } catch (SocketTimeoutException ste) {
                // Falla de omisión: re-encolar y reintentar con este u otro peer.
                System.err.println("[PCH] Timeout pidiendo pieza " + pieceIndex
                        + " a " + shortId(remotePeerId) + " — re-encolada");
                pieceManager.requeue(pieceIndex);
            }
        }
        System.out.println("[PCH] Descarga completa observada desde la perspectiva de " + shortId(remotePeerId));
    }

    /* --------------------------------- serve --------------------------------- */

    /** Sirve piezas a quien las pida (siempre que esté unchoked). */
    private void serveLoop() throws Exception {
        while (true) {
            Object msg;
            try {
                msg = channel.receive();
            } catch (SocketTimeoutException ste) {
                continue; // sin pedidos, pero conexión sigue viva
            }
            if (msg instanceof RequestMessage) {
                handleRequest((RequestMessage) msg);
            } else if (msg instanceof HaveMessage) {
                int idx = ((HaveMessage) msg).pieceIndex;
                remoteAvailable.add(idx);
                pieceManager.updateAvailability(remotePeerId, remoteAvailable);
            } else if (msg == null || !(msg instanceof PeerMessage)) {
                break;
            }
        }
    }

    private void handleRequest(RequestMessage req) throws IOException {
        if (!chokeManager.isUnchoked(remotePeerId)) return;
        if (!pieceManager.hasPiece(req.pieceIndex)) return;

        byte[] full = fileAssembler.readPiece(req.pieceIndex);
        // Si el peer pide menos que la pieza completa, recortamos al rango solicitado.
        // Hoy el sistema pide siempre la pieza entera (offset=0, blockLength=pieceLen),
        // pero esta lógica deja el camino abierto para granularidad por bloques.
        byte[] block;
        if (req.offset == 0 && req.blockLength >= full.length) {
            block = full;
        } else {
            int end = Math.min(req.offset + req.blockLength, full.length);
            int len = Math.max(0, end - req.offset);
            block = new byte[len];
            System.arraycopy(full, req.offset, block, 0, len);
        }
        channel.send(new PieceData(req.pieceIndex, req.offset, block));
    }

    /* --------------------------------- utils --------------------------------- */

    private static String shortId(String id) {
        if (id == null) return "?";
        return id.substring(0, Math.min(8, id.length()));
    }
}