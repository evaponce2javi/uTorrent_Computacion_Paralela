package utorrent.service;

import java.util.HashSet;
import java.util.Set;

/**
 * Capa de Servicio (F1). Convierte entre {@code byte[]} bitfield (un bit por pieza,
 * MSB-first dentro de cada byte, según el estándar BitTorrent) y {@code Set<Integer>}
 * con los índices de las piezas presentes.
 */
public class BitfieldHandler {

    public Set<Integer> parse(byte[] bitfield, int totalPieces) {
        Set<Integer> result = new HashSet<>();
        for (int i = 0; i < totalPieces; i++) {
            int byteIdx = i / 8;
            int bitIdx  = 7 - (i % 8);
            if (byteIdx < bitfield.length && ((bitfield[byteIdx] >> bitIdx) & 1) == 1) {
                result.add(i);
            }
        }
        return result;
    }

    public byte[] build(PieceManager pm) {
        int total = pm.totalPieces();
        byte[] bf = new byte[(total + 7) / 8];
        for (int i = 0; i < total; i++) {
            if (pm.hasPiece(i)) {
                int byteIdx = i / 8;
                int bitIdx  = 7 - (i % 8);
                bf[byteIdx] |= (1 << bitIdx);
            }
        }
        return bf;
    }
}