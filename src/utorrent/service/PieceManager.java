package utorrent.service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerArray;
import utorrent.model.TorrentMetadata;

/**
 * Capa de Servicio (F1). Lleva el estado de cada pieza y aplica el algoritmo
 * <b>rarest-first</b>: prioriza la descarga de las piezas con menos copias en el swarm,
 * maximizando la diversidad y evitando que piezas escasas desaparezcan si el seeder se va.
 *
 * <p>Estados: 0 = NOT_STARTED, 1 = IN_PROGRESS, 2 = COMPLETED.
 *
 * <p>Las transiciones se hacen con métodos {@code synchronized} para impedir que dos
 * {@link PeerConnectionHandler} reclamen la misma pieza simultáneamente (race condition).
 */
public class PieceManager {

    public static final int NOT_STARTED = 0;
    public static final int IN_PROGRESS = 1;
    public static final int COMPLETED   = 2;

    private final TorrentMetadata torrent;
    private final AtomicIntegerArray state;
    /** Frecuencia de cada pieza en el swarm (rarest-first). */
    private final int[] availability;
    /** peerId → Set<pieceIndex> que ese peer tiene. */
    private final Map<String, Set<Integer>> peerBitfields = new ConcurrentHashMap<>();

    public PieceManager(TorrentMetadata torrent, boolean alreadyComplete) {
        this.torrent = torrent;
        this.state = new AtomicIntegerArray(torrent.totalPieces());
        this.availability = new int[torrent.totalPieces()];
        if (alreadyComplete) {
            for (int i = 0; i < state.length(); i++) state.set(i, COMPLETED);
        }
    }

    /** Llamado por {@link BitfieldHandler} cuando se recibe el bitfield de un peer. */
    public synchronized void updateAvailability(String peerId, Set<Integer> piecesOfPeer) {
        Set<Integer> previous = peerBitfields.put(peerId, new HashSet<>(piecesOfPeer));
        if (previous != null) for (int p : previous) availability[p]--;
        for (int p : piecesOfPeer) availability[p]++;
    }

    public synchronized void removePeer(String peerId) {
        Set<Integer> bf = peerBitfields.remove(peerId);
        if (bf != null) for (int p : bf) availability[p]--;
    }

    /**
     * Selecciona la siguiente pieza a pedir aplicando rarest-first. Marca la pieza
     * como {@code IN_PROGRESS} de forma atómica con la selección. Retorna {@code -1}
     * si el peer no tiene piezas útiles.
     */
    public synchronized int selectNextPiece(Set<Integer> piecesAvailableOnPeer) {
        int chosen = -1;
        int minAvail = Integer.MAX_VALUE;
        for (int idx : piecesAvailableOnPeer) {
            if (state.get(idx) != NOT_STARTED) continue;
            if (availability[idx] < minAvail) {
                minAvail = availability[idx];
                chosen = idx;
            }
        }
        if (chosen != -1) state.set(chosen, IN_PROGRESS);
        return chosen;
    }

    /** Marca como completada. Llamada solo después de pasar IntegrityChecker. */
    public void markCompleted(int pieceIndex) { state.set(pieceIndex, COMPLETED); }

    /** Falla de valor: pieza con hash incorrecto. Vuelve al pool. */
    public void requeue(int pieceIndex) {
        state.compareAndSet(pieceIndex, IN_PROGRESS, NOT_STARTED);
    }

    public boolean hasPiece(int pieceIndex) { return state.get(pieceIndex) == COMPLETED; }

    public boolean isComplete() {
        for (int i = 0; i < state.length(); i++) if (state.get(i) != COMPLETED) return false;
        return true;
    }

    public int totalPieces() { return torrent.totalPieces(); }

    public byte[] expectedHash(int index) { return torrent.pieceHashes.get(index); }

    public int completedCount() {
        int n = 0;
        for (int i = 0; i < state.length(); i++) if (state.get(i) == COMPLETED) n++;
        return n;
    }
}