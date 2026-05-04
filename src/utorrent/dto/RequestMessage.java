package utorrent.dto;

/**
 * Solicitud de un bloque de una pieza al peer remoto.
 *
 * <p>Coherente con el diagrama UML F1 (mensaje 8): {@code request(pieceIndex, offset, blockLength)}.
 *
 * <p>Una pieza ({@code piece_length} bytes, típicamente 256 KB) se subdivide en bloques
 * de {@code blockLength} bytes (típicamente 16 KB = 16 384). El campo {@code offset}
 * indica la posición del bloque dentro de la pieza.
 *
 * <p>En la implementación actual, una sola RequestMessage puede pedir la pieza completa
 * usando {@code offset = 0} y {@code blockLength = pieceLengthAt(pieceIndex)}. La
 * granularidad por bloques se mantiene a nivel de protocolo para fidelidad con el
 * estándar BitTorrent y con el diagrama UML.
 */
public class RequestMessage extends PeerMessage {
    private static final long serialVersionUID = 1L;

    public final int pieceIndex;
    public final int offset;
    public final int blockLength;

    public RequestMessage(int pieceIndex, int offset, int blockLength) {
        this.pieceIndex = pieceIndex;
        this.offset = offset;
        this.blockLength = blockLength;
    }

    @Override
    public String toString() {
        return String.format("REQUEST[piece=%d, offset=%d, len=%d]", pieceIndex, offset, blockLength);
    }
}