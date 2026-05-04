package utorrent.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Codec Bencode nativo (sin librerías externas) para archivos .torrent.
 *
 * <p>Tipos soportados:
 * <ul>
 *   <li>Integer: {@code i<n>e}  (representado como {@link Long})</li>
 *   <li>String:  {@code <len>:<bytes>} (representado como {@code byte[]})</li>
 *   <li>List:    {@code l...e} (representado como {@link List}{@code <Object>})</li>
 *   <li>Dict:    {@code d...e} (representado como {@link LinkedHashMap}{@code <String,Object>})</li>
 * </ul>
 *
 * <p>Las claves de los diccionarios deben emitirse en orden lexicográfico (requisito
 * del estándar BEP-3) para que el infoHash sea reproducible.
 */
public class BencodeCodec {

    /* --------------------------------- DECODE --------------------------------- */

    private final byte[] buf;
    private int pos;

    private BencodeCodec(byte[] data) { this.buf = data; this.pos = 0; }

    /** Punto de entrada: decodifica un payload Bencode completo. */
    public static Object decode(byte[] data) {
        BencodeCodec d = new BencodeCodec(data);
        Object result = d.readValue();
        return result;
    }

    private Object readValue() {
        if (pos >= buf.length) throw new IllegalStateException("EOF inesperado en bencode");
        char c = (char) buf[pos];
        if (c == 'i') return readInteger();
        if (c == 'l') return readList();
        if (c == 'd') return readDict();
        if (Character.isDigit(c)) return readString();
        throw new IllegalStateException("Token bencode desconocido: '" + c + "' en pos " + pos);
    }

    private Long readInteger() {
        pos++; // consume 'i'
        int end = indexOf((byte) 'e', pos);
        long value = Long.parseLong(new String(buf, pos, end - pos, StandardCharsets.US_ASCII));
        pos = end + 1;
        return value;
    }

    private byte[] readString() {
        int colon = indexOf((byte) ':', pos);
        int len = Integer.parseInt(new String(buf, pos, colon - pos, StandardCharsets.US_ASCII));
        pos = colon + 1;
        byte[] str = Arrays.copyOfRange(buf, pos, pos + len);
        pos += len;
        return str;
    }

    private List<Object> readList() {
        pos++; // consume 'l'
        List<Object> list = new ArrayList<>();
        while (buf[pos] != 'e') list.add(readValue());
        pos++; // consume 'e'
        return list;
    }

    private LinkedHashMap<String, Object> readDict() {
        pos++; // consume 'd'
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        while (buf[pos] != 'e') {
            String key = new String(readString(), StandardCharsets.UTF_8);
            map.put(key, readValue());
        }
        pos++; // consume 'e'
        return map;
    }

    private int indexOf(byte target, int from) {
        for (int i = from; i < buf.length; i++) if (buf[i] == target) return i;
        throw new IllegalStateException("Delimitador '" + (char) target + "' no encontrado");
    }

    /* --------------------------------- ENCODE --------------------------------- */

    /** Codifica un objeto Java a su forma bencode canónica. */
    public static byte[] encode(Object value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try { writeValue(out, value); } catch (IOException e) { throw new RuntimeException(e); }
        return out.toByteArray();
    }

    private static void writeValue(ByteArrayOutputStream out, Object value) throws IOException {
        if (value instanceof Long || value instanceof Integer) {
            out.write(('i'));
            out.write(value.toString().getBytes(StandardCharsets.US_ASCII));
            out.write('e');
        } else if (value instanceof byte[]) {
            byte[] b = (byte[]) value;
            out.write(Integer.toString(b.length).getBytes(StandardCharsets.US_ASCII));
            out.write(':');
            out.write(b);
        } else if (value instanceof String) {
            byte[] b = ((String) value).getBytes(StandardCharsets.UTF_8);
            out.write(Integer.toString(b.length).getBytes(StandardCharsets.US_ASCII));
            out.write(':');
            out.write(b);
        } else if (value instanceof List) {
            out.write('l');
            for (Object item : (List<?>) value) writeValue(out, item);
            out.write('e');
        } else if (value instanceof Map) {
            out.write('d');
            // Ordenar claves lexicográficamente — requisito BEP-3 para infoHash reproducible.
            Map<String, Object> map = (Map<String, Object>) value;
            List<String> keys = new ArrayList<>(map.keySet());
            Collections.sort(keys);
            for (String k : keys) {
                writeValue(out, k);
                writeValue(out, map.get(k));
            }
            out.write('e');
        } else {
            throw new IllegalArgumentException("Tipo no soportado en bencode: " + value.getClass());
        }
    }
}