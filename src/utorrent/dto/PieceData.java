package utorrent.dto;

/**
 * Mensaje del protocolo P2P que transporta un bloque de datos entre peers.
 *
 * <p>Coherente con el diagrama UML F1 (mensaje 9: {@code piece(pieceIndex, data)}) y con
 * la tabla de DTOs del documento (campos: {@code pieceIndex}, {@code offset}, {@code data}).
 *
 * <p>Reemplaza a la antigua clase {@code PieceMessage}: {@code PieceData} es ahora a la
 * vez DTO documentado y mensaje del protocolo, eliminando duplicación.
 *
 * <p>Flujo: {@link utorrent.service.PeerConnectionHandler} recibe esta instancia,
 * {@link utorrent.service.IntegrityChecker} verifica el SHA-1 de {@link #data}, y si
 * pasa la verificación {@link utorrent.service.FileAssembler} la escribe a disco.
 */
public class PieceData extends PeerMessage {
    private static final long serialVersionUID = 1L;

    public final int pieceIndex;
    public final int offset;
    public final byte[] data;

    public PieceData(int pieceIndex, int offset, byte[] data) {
        this.pieceIndex = pieceIndex;
        this.offset = offset;
        this.data = data;
    }

    @Override
    public String toString() {
        return String.format("PIECE[piece=%d, offset=%d, bytes=%d]",
                pieceIndex, offset, data == null ? 0 : data.length);
    }
}