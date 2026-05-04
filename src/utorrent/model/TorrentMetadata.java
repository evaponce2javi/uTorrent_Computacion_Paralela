package utorrent.model;

import java.util.List;

/**
 * Estructura inmutable que representa los metadatos extraídos de un archivo .torrent.
 * Construida por TorrentParser tras decodificar el bencode.
 */
public class TorrentMetadata {
    public final String announce;          // host:port del tracker
    public final String name;              // nombre del archivo de salida
    public final long pieceLength;         // bytes por pieza (default 256 KB)
    public final long totalLength;         // tamaño total del archivo
    public final List<byte[]> pieceHashes; // SHA-1 de cada pieza (20 bytes c/u)
    public final String infoHash;          // SHA-1 hex del diccionario info — identificador del torrent

    public TorrentMetadata(String announce, String name, long pieceLength, long totalLength,
                           List<byte[]> pieceHashes, String infoHash) {
        this.announce = announce;
        this.name = name;
        this.pieceLength = pieceLength;
        this.totalLength = totalLength;
        this.pieceHashes = pieceHashes;
        this.infoHash = infoHash;
    }

    public int totalPieces() { return pieceHashes.size(); }

    public long pieceLengthAt(int index) {
        if (index == totalPieces() - 1) {
            long rem = totalLength % pieceLength;
            return rem == 0 ? pieceLength : rem;
        }
        return pieceLength;
    }

    @Override public String toString() {
        return String.format("Torrent[name=%s, pieces=%d, pieceLen=%d, totalLen=%d, infoHash=%s]",
                name, totalPieces(), pieceLength, totalLength, infoHash.substring(0, 12) + "...");
    }
}