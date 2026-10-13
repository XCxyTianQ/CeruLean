package io.cerulean.regionizer;

/**
 * 段坐标 ↔ 打包 key 的纯数学（无状态，可独立测试）。
 *
 * <p>约定：key = (x &amp; 0xFFFFFFFFL) | ((long) z &lt;&lt; 32)，与 Minecraft 的 ChunkPos 打包方式一致，
 * 因此 MC 侧接线时无需转换成本（见 study/folia/notes/11 的接线面）。</p>
 */
public final class SectionKey {

    private SectionKey() {
    }

    public static long pack(final int x, final int z) {
        return (x & 0xFFFFFFFFL) | ((long) z << 32);
    }

    public static int unpackX(final long key) {
        return (int) key;
    }

    public static int unpackZ(final long key) {
        return (int) (key >>> 32);
    }

    /** 区块坐标 → 段坐标（算术右移，负数坐标向负无穷取整）。 */
    public static int chunkToSection(final int chunkCoord, final int regionShift) {
        return chunkCoord >> regionShift;
    }

    /** 段坐标 → 该段左下角区块坐标。 */
    public static int sectionToChunk(final int sectionCoord, final int regionShift) {
        return sectionCoord << regionShift;
    }

    /** 以段为单位的 Chebyshev 距离。 */
    public static int sectionDistance(final int ax, final int az, final int bx, final int bz) {
        return Math.max(Math.abs(ax - bx), Math.abs(az - bz));
    }

    public static String toString(final long key) {
        return "(" + unpackX(key) + "," + unpackZ(key) + ")";
    }
}
