package utorrent.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import utorrent.model.TorrentMetadata;
import utorrent.util.BencodeCodec;
import utorrent.util.MockTorrentGenerator;

/**
 * Capa de Aplicación. Lee un archivo .torrent en formato Bencode y construye
 * un objeto {@link TorrentMetadata} inmutable que las capas inferiores consumen.
 *
 * <p>El infoHash se calcula como SHA-1 del bencode del diccionario "info" — debe
 * coincidir con el del seeder/leecher para que el handshake P2P y el tracker
 * funcionen.
 */
public class TorrentParser {

    /** Tamaño de pieza por defecto (256 KB) si el .torrent no lo especifica. */
    public static final long DEFAULT_PIECE_LENGTH = 262_144L;

    public TorrentMetadata parse(Path torrentFile) throws IOException {
        byte[] raw = Files.readAllBytes(torrentFile);
        Object decoded = BencodeCodec.decode(raw);
        if (!(decoded instanceof Map)) throw new IOException(".torrent inválido: raíz no es dict");
        Map<String, Object> root = (Map<String, Object>) decoded;

        String announce = new String((byte[]) root.get("announce"));
        Map<String, Object> info = (Map<String, Object>) root.get("info");
        if (info == null) throw new IOException(".torrent inválido: falta el diccionario 'info'");

        String name = new String((byte[]) info.get("name"));
        long pieceLength = info.containsKey("piece length") ? (Long) info.get("piece length") : DEFAULT_PIECE_LENGTH;
        long totalLength = (Long) info.get("length");

        byte[] piecesConcat = (byte[]) info.get("pieces");
        if (piecesConcat.length % 20 != 0) throw new IOException("Campo 'pieces' corrupto");
        List<byte[]> pieceHashes = new ArrayList<>();
        for (int i = 0; i < piecesConcat.length; i += 20) {
            pieceHashes.add(Arrays.copyOfRange(piecesConcat, i, i + 20));
        }

        String infoHash = computeInfoHash(info);
        return new TorrentMetadata(announce, name, pieceLength, totalLength, pieceHashes, infoHash);
    }

    /** infoHash = SHA-1(bencode(info)). Crítico que el bencode tenga claves ordenadas. */
    private String computeInfoHash(Map<String, Object> info) {
        try {
            byte[] infoBytes = BencodeCodec.encode(info);
            byte[] hash = MessageDigest.getInstance("SHA-1").digest(infoBytes);
            return MockTorrentGenerator.bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}