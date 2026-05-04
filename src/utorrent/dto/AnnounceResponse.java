package utorrent.dto;

import java.io.Serializable;
import java.util.List;

/** Respuesta del tracker con la lista de peers activos. */
public class AnnounceResponse implements Serializable {
    private static final long serialVersionUID = 1L;

    public final boolean ok;
    public final String message;
    public final int interval;            // segundos hasta el próximo announce
    public final List<PeerInfo> peers;

    public AnnounceResponse(boolean ok, String message, int interval, List<PeerInfo> peers) {
        this.ok = ok; this.message = message; this.interval = interval; this.peers = peers;
    }
}