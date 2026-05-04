package utorrent.service;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.*;
import java.util.concurrent.locks.ReentrantLock;
import utorrent.model.TorrentMetadata;

/**
 * Capa de Servicio (F1). Escribe piezas verificadas al disco en su offset correcto
 * y, en modo seeder, lee piezas para servirlas a otros peers.
 *
 * <p>Usa un {@link ReentrantLock} alrededor del {@link RandomAccessFile} porque
 * {@code seek+write} no es atómico y la API no es thread-safe para escrituras
 * concurrentes desde múltiples PeerConnectionHandler.
 */
public class FileAssembler {

    private final TorrentMetadata torrent;
    private final Path outputPath;
    private final ReentrantLock fileLock = new ReentrantLock();
    private RandomAccessFile raf;

    public FileAssembler(String dataDir, TorrentMetadata torrent) throws IOException {
        this.torrent = torrent;
        Files.createDirectories(Paths.get(dataDir));
        this.outputPath = Paths.get(dataDir, torrent.name);
    }

    /** Modo SEEDER: el archivo ya existe completo en disco, solo se lee. */
    public void openExistingForReading() throws IOException {
        if (!Files.exists(outputPath))
            throw new IOException("Modo seeder requiere archivo existente: " + outputPath);
        raf = new RandomAccessFile(outputPath.toFile(), "r");
        System.out.println("[FileAssembler] Seeder · sirviendo " + outputPath);
    }

    /** Modo LEECHER: archivo vacío pre-asignado al tamaño total. */
    public void openNewForWriting() throws IOException {
        raf = new RandomAccessFile(outputPath.toFile(), "rw");
        raf.setLength(torrent.totalLength);
        System.out.println("[FileAssembler] Leecher · escribiendo en " + outputPath);
    }

    public void writePiece(int pieceIndex, byte[] data) throws IOException {
        long offset = (long) pieceIndex * torrent.pieceLength;
        fileLock.lock();
        try {
            raf.seek(offset);
            raf.write(data);
        } finally {
            fileLock.unlock();
        }
    }

    public byte[] readPiece(int pieceIndex) throws IOException {
        long offset = (long) pieceIndex * torrent.pieceLength;
        int len = (int) torrent.pieceLengthAt(pieceIndex);
        byte[] buf = new byte[len];
        fileLock.lock();
        try {
            raf.seek(offset);
            raf.readFully(buf);
        } finally {
            fileLock.unlock();
        }
        return buf;
    }

    public void flush() {
        fileLock.lock();
        try { if (raf != null) raf.getFD().sync(); }
        catch (IOException e) { System.err.println("[FileAssembler] flush error: " + e); }
        finally { fileLock.unlock(); }
    }

    public void close() {
        fileLock.lock();
        try { if (raf != null) raf.close(); }
        catch (IOException ignored) {}
        finally { fileLock.unlock(); }
    }
}