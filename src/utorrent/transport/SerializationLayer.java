package utorrent.transport;

import java.io.*;

/**
 * Encapsula el marshalling vía {@link ObjectOutputStream}/{@link ObjectInputStream}.
 * Centraliza la conversión Java-objeto ↔ bytes para el transporte.
 */
public class SerializationLayer {

    public static byte[] toBytes(Object obj) throws IOException {
        ByteArrayOutputStream bao = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bao)) {
            oos.writeObject(obj);
        }
        return bao.toByteArray();
    }

    public static Object fromBytes(byte[] data) throws IOException, ClassNotFoundException {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(data))) {
            return ois.readObject();
        }
    }
}