package utorrent.dto;

/** Primer mensaje del protocolo P2P; valida que ambos peers compartan el mismo torrent. */
public class HandshakeMessage extends PeerMessage {
    private static final long serialVersionUID = 1L;
    public final String infoHash;
    public final String peerId;

    public HandshakeMessage(String infoHash, String peerId) {
        this.infoHash = infoHash;
        this.peerId = peerId;
    }
}