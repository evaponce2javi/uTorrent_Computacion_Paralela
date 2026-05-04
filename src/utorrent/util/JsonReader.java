package utorrent.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser JSON minimalista implementado en Java estándar (sin librerías externas).
 *
 * <p>Cumple la restricción del proyecto de no usar org.json, GSON, Jackson, etc.
 *
 * <p>Tipos soportados:
 * <ul>
 *   <li>Objeto: {@code {...}} → {@link LinkedHashMap}{@code <String, Object>}</li>
 *   <li>Array: {@code [...]} → {@link List}{@code <Object>}</li>
 *   <li>String: {@code "..."} con escapes {@code \" \\ \/ \n \r \t \b \f}</li>
 *   <li>Number: entero ({@link Long}) o decimal ({@link Double})</li>
 *   <li>Boolean ({@link Boolean}) y null</li>
 * </ul>
 *
 * <p>Limitaciones intencionales (suficientes para {@code bootstrapPeers.json}):
 * permite comentarios {@code //} y {@code /* *}{@code /} de una línea para que el
 * archivo de configuración admita anotaciones humanas, lo cual JSON estricto no
 * acepta. Si algún día se requiere JSON estricto, basta con omitir los comentarios.
 */
public final class JsonReader {

    private final String text;
    private int pos;

    private JsonReader(String text) { this.text = text; this.pos = 0; }

    /** Lee y parsea un archivo JSON. */
    public static Object readFile(Path file) throws IOException {
        String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        return parse(content);
    }

    /** Parsea una cadena JSON. */
    public static Object parse(String text) {
        JsonReader r = new JsonReader(text);
        r.skipWhitespace();
        Object result = r.readValue();
        r.skipWhitespace();
        if (r.pos < r.text.length()) {
            throw new IllegalStateException("Contenido extra después del valor JSON en pos " + r.pos);
        }
        return result;
    }

    /* ============================== núcleo ============================== */

    private Object readValue() {
        skipWhitespace();
        if (pos >= text.length()) throw new IllegalStateException("EOF inesperado");
        char c = text.charAt(pos);
        if (c == '{') return readObject();
        if (c == '[') return readArray();
        if (c == '"') return readString();
        if (c == 't' || c == 'f') return readBoolean();
        if (c == 'n') return readNull();
        if (c == '-' || Character.isDigit(c)) return readNumber();
        throw new IllegalStateException("Token JSON inválido '" + c + "' en pos " + pos);
    }

    private Map<String, Object> readObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // consume '{'
        skipWhitespace();
        if (peek() == '}') { pos++; return map; }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            expect(':');
            Object value = readValue();
            map.put(key, value);
            skipWhitespace();
            char c = text.charAt(pos);
            if (c == ',') { pos++; continue; }
            if (c == '}') { pos++; return map; }
            throw new IllegalStateException("Se esperaba ',' o '}' en pos " + pos + " (vio '" + c + "')");
        }
    }

    private List<Object> readArray() {
        List<Object> list = new ArrayList<>();
        pos++; // consume '['
        skipWhitespace();
        if (peek() == ']') { pos++; return list; }
        while (true) {
            list.add(readValue());
            skipWhitespace();
            char c = text.charAt(pos);
            if (c == ',') { pos++; continue; }
            if (c == ']') { pos++; return list; }
            throw new IllegalStateException("Se esperaba ',' o ']' en pos " + pos + " (vio '" + c + "')");
        }
    }

    private String readString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (pos < text.length()) {
            char c = text.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                char esc = text.charAt(pos++);
                switch (esc) {
                    case '"':  sb.append('"');  break;
                    case '\\': sb.append('\\'); break;
                    case '/':  sb.append('/');  break;
                    case 'n':  sb.append('\n'); break;
                    case 'r':  sb.append('\r'); break;
                    case 't':  sb.append('\t'); break;
                    case 'b':  sb.append('\b'); break;
                    case 'f':  sb.append('\f'); break;
                    case 'u':
                        String hex = text.substring(pos, pos + 4);
                        sb.append((char) Integer.parseInt(hex, 16));
                        pos += 4;
                        break;
                    default: throw new IllegalStateException("Escape inválido: \\" + esc);
                }
            } else {
                sb.append(c);
            }
        }
        throw new IllegalStateException("String JSON sin cierre");
    }

    private Object readNumber() {
        int start = pos;
        if (text.charAt(pos) == '-') pos++;
        while (pos < text.length() && (Character.isDigit(text.charAt(pos)) || ".eE+-".indexOf(text.charAt(pos)) >= 0)) {
            pos++;
        }
        String num = text.substring(start, pos);
        if (num.contains(".") || num.contains("e") || num.contains("E")) return Double.parseDouble(num);
        return Long.parseLong(num);
    }

    private Boolean readBoolean() {
        if (text.startsWith("true", pos))  { pos += 4; return Boolean.TRUE;  }
        if (text.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
        throw new IllegalStateException("Boolean inválido en pos " + pos);
    }

    private Object readNull() {
        if (text.startsWith("null", pos)) { pos += 4; return null; }
        throw new IllegalStateException("'null' inválido en pos " + pos);
    }

    /* ============================== utilidades ============================== */

    private void skipWhitespace() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (Character.isWhitespace(c)) { pos++; continue; }
            // Comentarios estilo C — extensión no estándar, útil para configuración humana.
            if (c == '/' && pos + 1 < text.length()) {
                char n = text.charAt(pos + 1);
                if (n == '/') {
                    while (pos < text.length() && text.charAt(pos) != '\n') pos++;
                    continue;
                }
                if (n == '*') {
                    pos += 2;
                    while (pos + 1 < text.length() && !(text.charAt(pos) == '*' && text.charAt(pos + 1) == '/')) pos++;
                    pos += 2;
                    continue;
                }
            }
            return;
        }
    }

    private char peek() { return text.charAt(pos); }

    private void expect(char c) {
        if (pos >= text.length() || text.charAt(pos) != c)
            throw new IllegalStateException("Se esperaba '" + c + "' en pos " + pos);
        pos++;
    }
}