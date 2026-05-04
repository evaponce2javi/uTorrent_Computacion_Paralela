package utorrent.dto;

import java.io.Serializable;

/**
 * Mensaje que el cliente envía al tracker en F2.
 * Reporta estado y solicita lista de peers para el infoHash dado.
 */
public class AnnounceRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    public final String infoHash;
    public final String peerId;
    public final int port;
    public final long uploaded;
    public final long downloaded;
    public final long left;
    public final String event; // "started" | "completed" | "stopped"

    public AnnounceRequest(String infoHash, String peerId, int port,
                           long uploaded, long downloaded, long left, String event) {
        this.infoHash = infoHash;
        this.peerId = peerId;
        this.port = port;
        this.uploaded = uploaded;
        this.downloaded = downloaded;
        this.left = left;
        this.event = event;
    }
}