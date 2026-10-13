package io.cerulean.regionizer;

/**
 * 段：区域归属的最小单位（见 docs/06 §2）。
 *
 * <p>存活判据（不变量 I5）：{@code chunkCount > 0 || nonEmptyNeighbours > 0}，
 * 其中 {@code nonEmptyNeighbours} = **四邻（上下左右）中含区块的段数**。
 * 这是一个"一级"定义（不是递归闭包），保证不会无限扩张。</p>
 */
public final class Section<D> {

    public final long key;
    public final int x;
    public final int z;

    int chunkCount;
    int nonEmptyNeighbours;
    Region<D> region;

    Section(final long key, final int x, final int z) {
        this.key = key;
        this.x = x;
        this.z = z;
    }

    public long key() {
        return this.key;
    }

    public int x() {
        return this.x;
    }

    public int z() {
        return this.z;
    }

    public int chunkCount() {
        return this.chunkCount;
    }

    public int nonEmptyNeighbours() {
        return this.nonEmptyNeighbours;
    }

    /** 不变量 I5。 */
    public boolean isAlive() {
        return this.chunkCount > 0 || this.nonEmptyNeighbours > 0;
    }

    public Region<D> region() {
        return this.region;
    }

    @Override
    public String toString() {
        return "Section" + SectionKey.toString(this.key) + "{chunks=" + this.chunkCount
                + ", nonEmptyNb=" + this.nonEmptyNeighbours + ", region=" + (this.region == null ? "-" : this.region.id()) + '}';
    }
}
