package utorrent.dto;

import java.io.Serializable;
import java.util.Objects;

/**
 * Información de un peer registrado en el swarm.
 * Identificado únicamente por peerId — la transparencia de ubicación
 * se logra porque solo SwarmScheduler conoce ip:port.
 */
public class PeerInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    public final String peerId;
    public final String ip;
    public final int port;

    public PeerInfo(String peerId, String ip, int port) {
        this.peerId = peerId;
        this.ip = ip;
        this.port = port;
    }

    @Override public boolean equals(Object o) {
        return o instanceof PeerInfo && Objects.equals(peerId, ((PeerInfo) o).peerId);
    }
    @Override public int hashCode() { return Objects.hash(peerId); }
    @Override public String toString() {
        return String.format("Peer[%s@%s:%d]", peerId.substring(0, Math.min(8, peerId.length())), ip, port);
    }
}