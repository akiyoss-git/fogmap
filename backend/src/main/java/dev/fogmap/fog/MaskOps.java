package dev.fogmap.fog;

/**
 * Операции над маской тайла.
 *
 * <p>Маска grow-only, поэтому слияние — побитовое ИЛИ: идемпотентно и коммутативно. Порядок
 * прихода тайлов с разных устройств не важен, разрешать конфликты не нужно.
 *
 * <p>Всё побайтово, поэтому порядок байтов в клиентской сериализации значения не имеет.
 */
public final class MaskOps {

    private MaskOps() {
    }

    /** Число открытых ячеек. Считается только здесь — значению от клиента не верим. */
    public static int popCount(byte[] mask) {
        int count = 0;
        for (byte b : mask) {
            count += Integer.bitCount(b & 0xFF);
        }
        return count;
    }

    /** Новый массив с результатом ИЛИ. Аргументы не меняются. */
    public static byte[] or(byte[] a, byte[] b) {
        byte[] merged = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            merged[i] = (byte) (a[i] | b[i]);
        }
        return merged;
    }
}
