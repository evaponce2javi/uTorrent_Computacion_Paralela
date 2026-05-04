package utorrent.util;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Genera un archivo de datos sintético y su correspondiente .torrent (bencode válido)
 * para poder probar el sistema sin necesidad de torrents reales.
 *
 * <p>Salida:
 * <ul>
 *   <li>{@code <out>/mock_data.bin} — archivo binario aleatorio reproducible</li>
 *   <li>{@code <out>/mock.torrent}  — metadatos en bencode con SHA-1 por pieza</li>
 * </ul>
 *
 * <p>Uso:  {@code java utorrent.util.MockTorrentGenerator [out_dir] [size_bytes] [piece_length]}
 */
public class MockTorrentGenerator {

    public static void main(String[] args) throws Exception {
        String outDir = args.length >= 1 ? args[0] : "./mock";
        long size = args.length >= 2 ? Long.parseLong(args[1]) : 1_048_576L; // 1 MB
        int pieceLength = args.length >= 3 ? Integer.parseInt(args[2]) : 262_144;  // 256 KB
        String trackerHost = "localhost";
        int trackerPort = 6969;

        Files.createDirectories(Paths.get(outDir));
        Path dataPath = Paths.get(outDir, "mock_data.bin");
        Path torrentPath = Paths.get(outDir, "mock.torrent");

        // 1) Generar archivo de datos determinístico (semilla fija → mismo archivo siempre).
        Random rnd = new Random(42L);
        byte[] data = new byte[(int) size];
        rnd.nextBytes(data);
        Files.write(dataPath, data);
        System.out.println("[Mock] Archivo generado: " + dataPath + " (" + size + " bytes)");

        // 2) Calcular SHA-1 por pieza y concatenarlo.
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        ByteArrayOutputStream piecesConcat = new ByteArrayOutputStream();
        int totalPieces = (int) Math.ceil((double) size / pieceLength);
        for (int i = 0; i < totalPieces; i++) {
            int off = i * pieceLength;
            int len = (int) Math.min(pieceLength, size - off);
            byte[] hash = sha1.digest(Arrays.copyOfRange(data, off, off + len));
            piecesConcat.write(hash);
        }

        // 3) Construir el diccionario "info" del torrent.
        LinkedHashMap<String, Object> info = new LinkedHashMap<>();
        info.put("length", (long) size);
        info.put("name", dataPath.getFileName().toString().getBytes());
        info.put("piece length", (long) pieceLength);
        info.put("pieces", piecesConcat.toByteArray());

        // 4) Calcular el infoHash (SHA-1 del bencode del diccionario "info").
        byte[] infoBencoded = BencodeCodec.encode(info);
        byte[] infoHash = sha1.digest(infoBencoded);
        String infoHashHex = bytesToHex(infoHash);

        // 5) Construir el torrent completo.
        LinkedHashMap<String, Object> torrent = new LinkedHashMap<>();
        torrent.put("announce", (trackerHost + ":" + trackerPort).getBytes());
        torrent.put("info", info);

        Files.write(torrentPath, BencodeCodec.encode(torrent));
        System.out.println("[Mock] Torrent generado: " + torrentPath);
        System.out.println("[Mock] infoHash = " + infoHashHex);
        System.out.println("[Mock] piezas   = " + totalPieces + " x " + pieceLength + " bytes");
    }

    public static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}