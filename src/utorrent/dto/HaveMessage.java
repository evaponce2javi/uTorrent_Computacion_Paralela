package utorrent.dto;

/** Notificación de que un peer ha completado una pieza nueva. Permite actualización incremental del bitfield. */
public class HaveMessage extends PeerMessage {
    private static final long serialVersionUID = 1L;
    public final int pieceIndex;

    public HaveMessage(int pieceIndex) { this.pieceIndex = pieceIndex; }
}