package utorrent.transport;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * Capa de Transporte. Abstrae un socket TCP exponiendo {@code send(Object)}/{@code receive()}.
 * Las capas superiores (PeerConnectionHandler, TrackerClient, TrackerServer) no manipulan
 * sockets directamente — esto facilita la transparencia de acceso.
 */
public class SocketDataChannel implements Closeable {

    private final Socket socket;
    private final ObjectOutputStream out;
    private final ObjectInputStream in;

    public SocketDataChannel(Socket socket) throws IOException {
        this.socket = socket;
        // Importante: crear OOS antes que OIS para evitar deadlock en handshake.
        this.out = new ObjectOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        this.out.flush();
        this.in = new ObjectInputStream(new BufferedInputStream(socket.getInputStream()));
    }

    public synchronized void send(Object msg) throws IOException {
        out.writeObject(msg);
        out.flush();
        out.reset(); // evitar acumulación en cache de referencias del stream
    }

    public Object receive() throws IOException, ClassNotFoundException {
        return in.readObject();
    }

    /** Falla de omisión: si no llegan datos en {@code timeoutMs}, lanza SocketTimeoutException. */
    public void setReadTimeout(int timeoutMs) throws IOException {
        socket.setSoTimeout(timeoutMs);
    }

    public String getRemoteIp()   { return socket.getInetAddress().getHostAddress(); }
    public int    getRemotePort() { return socket.getPort(); }

    @Override public void close() {
        try { in.close();    } catch (IOException ignored) {}
        try { out.close();   } catch (IOException ignored) {}
        try { socket.close();} catch (IOException ignored) {}
    }

    /** Helper estático: traduce SocketTimeoutException en log limpio. */
    public static boolean isTimeout(Throwable t) { return t instanceof SocketTimeoutException; }
}