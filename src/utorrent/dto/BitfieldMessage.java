package utorrent.dto;

/** Mapa de bits enviado tras el handshake: indica qué piezas tiene el peer remoto. */
public class BitfieldMessage extends PeerMessage {
    private static final long serialVersionUID = 1L;
    public final byte[] bitfield;
    public final int totalPieces;

    public BitfieldMessage(byte[] bitfield, int totalPieces) {
        this.bitfield = bitfield;
        this.totalPieces = totalPieces;
    }
}