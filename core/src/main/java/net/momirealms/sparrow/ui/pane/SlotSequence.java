package net.momirealms.sparrow.ui.pane;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.function.IntConsumer;

public final class SlotSequence {
    private final PaneSize paneSize;  // 这些槽号属于哪个 Pane 尺寸
    private final int[] slots;        // 按使用顺序排的槽号, 不重复
    private final int minX;           // 选中槽位里最靠左的那一列, 空选择是 -1
    private final int minY;           // 选中槽位里最靠上的那一行, 空选择是 -1

    SlotSequence(PaneSize paneSize, int[] slots) {
        this.paneSize = paneSize;
        this.slots = slots;

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int width = paneSize.width();
        for (int slot : slots) {
            minX = Math.min(minX, slot % width);
            minY = Math.min(minY, slot / width);
        }
        // 空选择没有最小坐标, 用 -1 顶着, 免得调用方去猜 Integer.MAX_VALUE 之类的巧合值算不算空
        this.minX = slots.length == 0 ? -1 : minX;
        this.minY = slots.length == 0 ? -1 : minY;
    }

    /**
     * 按给的顺序选中这些槽位, 顺序就是要用的顺序.
     *
     * @param paneSize 槽位所属的 Pane 尺寸
     * @param slots 要选的槽号, <strong>创建时复制一份</strong>
     * @return 槽位选择
     * @throws IndexOutOfBoundsException 槽号超出 Pane 范围时
     * @throws IllegalArgumentException 同一个槽号出现两次时
     */
    @NotNull
    public static SlotSequence of(@NotNull PaneSize paneSize, int... slots) {
        int[] copy = slots.clone();
        // 两个以下不可能是重复, 只查范围, 不为判重白开一张面积大小的位图
        if (copy.length < 2) {
            for (int slot : copy) {
                paneSize.checkSlot(slot);
            }
            return new SlotSequence(paneSize, copy);
        }

        // 两个以上就得判重了, 位图按面积开, 每个槽号一格
        boolean[] seen = new boolean[paneSize.area()];
        for (int slot : copy) {
            paneSize.checkSlot(slot);
            if (seen[slot]) {
                throw new IllegalArgumentException("duplicate slot " + slot);
            }
            seen[slot] = true;
        }
        return new SlotSequence(paneSize, copy);
    }

    /**
     * 整个 Pane 的槽位, 按行优先顺序.
     *
     * @param paneSize Pane 尺寸
     * @return 包含所有槽位的选择
     */
    @NotNull
    public static SlotSequence all(@NotNull PaneSize paneSize) {
        int[] slots = new int[paneSize.area()];
        Arrays.setAll(slots, index -> index);
        return new SlotSequence(paneSize, slots);
    }

    /**
     * 选一段连续的槽号, 左闭右开.
     *
     * @param paneSize Pane 尺寸
     * @param startInclusive 起始槽位, 包含
     * @param endExclusive 结束槽位, 不包含
     * @return 指定范围的槽位选择
     * @throws IndexOutOfBoundsException 范围超出 Pane 时
     */
    @NotNull
    public static SlotSequence range(@NotNull PaneSize paneSize, int startInclusive, int endExclusive) {
        if (startInclusive < 0 || endExclusive < startInclusive || endExclusive > paneSize.area()) {
            throw new IndexOutOfBoundsException(
                    "range [" + startInclusive + ", " + endExclusive + ") is outside " + paneSize
            );
        }
        int[] slots = new int[endExclusive - startInclusive];
        Arrays.setAll(slots, index -> startInclusive + index);
        return new SlotSequence(paneSize, slots);
    }

    /**
     * 一整行槽位, 从左到右.
     *
     * @param paneSize Pane 尺寸
     * @param row 行号, 从 0 开始
     * @return 一整行槽位
     * @throws IndexOutOfBoundsException 行号超出 Pane 高度时
     */
    @NotNull
    public static SlotSequence row(@NotNull PaneSize paneSize, int row) {
        if (row < 0 || row >= paneSize.height()) {
            throw new IndexOutOfBoundsException("row " + row + " is outside " + paneSize);
        }
        int[] slots = new int[paneSize.width()];
        int start = row * paneSize.width();
        Arrays.setAll(slots, index -> start + index);
        return new SlotSequence(paneSize, slots);
    }

    /**
     * 一整列槽位, 从上到下.
     *
     * @param paneSize Pane 尺寸
     * @param column 列号, 从 0 开始
     * @return 一整列槽位
     * @throws IndexOutOfBoundsException 列号超出 Pane 宽度时
     */
    @NotNull
    public static SlotSequence column(@NotNull PaneSize paneSize, int column) {
        if (column < 0 || column >= paneSize.width()) {
            throw new IndexOutOfBoundsException("column " + column + " is outside " + paneSize);
        }
        int[] slots = new int[paneSize.height()];
        int width = paneSize.width();
        Arrays.setAll(slots, index -> column + index * width);
        return new SlotSequence(paneSize, slots);
    }

    /**
     * 选一块矩形区域, 从左上角开始一行一行收.
     *
     * @param paneSize Pane 尺寸
     * @param x 矩形左上角在第几列
     * @param y 矩形左上角在第几行
     * @param width 矩形宽度
     * @param height 矩形高度
     * @return 矩形内的槽位选择
     * @throws IllegalArgumentException 矩形的宽或高不是正数时
     * @throws IndexOutOfBoundsException 矩形探出 Pane 时
     */
    @NotNull
    public static SlotSequence rectangle(@NotNull PaneSize paneSize, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("rectangle dimensions must be positive");
        }
        if (x < 0 || y < 0 || x + width > paneSize.width() || y + height > paneSize.height()) {
            throw new IndexOutOfBoundsException(
                    "rectangle (" + x + ", " + y + ", " + width + ", " + height + ") is outside " + paneSize
            );
        }

        // 一行一行收: 行首那个槽号拿到之后, 这一行剩下的就是挨着的
        int[] slots = new int[Math.multiplyExact(width, height)];
        int index = 0;
        for (int row = y; row < y + height; row++) {
            int rowStart = paneSize.indexOfTrusted(x, row);
            for (int column = 0; column < width; column++) {
                slots[index++] = rowStart + column;
            }
        }
        return new SlotSequence(paneSize, slots);
    }

    /**
     * Pane 四周那一圈槽位.
     *
     * @param paneSize Pane 尺寸
     * @return 边框槽位选择
     */
    @NotNull
    public static SlotSequence borders(@NotNull PaneSize paneSize) {
        int width = paneSize.width();
        int height = paneSize.height();
        // 零面积和只有一行或一列的 Pane, 每一格都在边框上
        if (paneSize.area() == 0 || width == 1 || height == 1) {
            return all(paneSize);
        }

        int[] slots = new int[2 * width + 2 * (height - 2)];
        int index = 0;
        // 顶行
        for (int x = 0; x < width; x++) {
            slots[index++] = x;
        }
        // 中间行的左右两列
        for (int y = 1; y < height - 1; y++) {
            slots[index++] = y * width;
            slots[index++] = y * width + width - 1;
        }
        // 底行
        int bottom = (height - 1) * width;
        for (int x = 0; x < width; x++) {
            slots[index++] = bottom + x;
        }
        return new SlotSequence(paneSize, slots);
    }

    /**
     * 按给的顺序把几组槽位接成一组.
     * <p>每组必须属于同一个 Pane 尺寸, 而且彼此之间不能有重复的格.
     *
     * @param sequences 要合并的槽位选择
     * @return 接好的一整组槽位
     * @throws IllegalArgumentException 一组都没给, 尺寸对不上, 或者有重复槽位时
     */
    @NotNull
    public static SlotSequence concat(@NotNull SlotSequence... sequences) {
        if (sequences.length == 0) {
            throw new IllegalArgumentException("at least one slot sequence is required");
        }

        // 先确认它们属于同一个 Pane 尺寸, 顺便算一下一共多少格
        PaneSize size = sequences[0].paneSize;
        int length = 0;
        for (SlotSequence sequence : sequences) {
            if (!size.equals(sequence.paneSize)) {
                throw new IllegalArgumentException("slot sequences use different Pane sizes");
            }
            length = Math.addExact(length, sequence.slots.length);
        }

        // 按给的顺序收格, 跨组的重复当场拒绝
        int[] slots = new int[length];
        boolean[] seen = new boolean[size.area()];
        int index = 0;
        for (SlotSequence sequence : sequences) {
            for (int slot : sequence.slots) {
                if (seen[slot]) {
                    throw new IllegalArgumentException("duplicate slot " + slot + " across sequences");
                }
                seen[slot] = true;
                slots[index++] = slot;
            }
        }
        return new SlotSequence(size, slots);
    }

    @NotNull
    public PaneSize paneSize() {
        return this.paneSize;
    }

    public int length() {
        return this.slots.length;
    }

    public boolean isEmpty() {
        return this.slots.length == 0;
    }

    /**
     * 按使用顺序取第 occurrence 个槽号.
     *
     * @param occurrence 使用顺序里的位置
     * @return Pane 槽号
     * @throws IndexOutOfBoundsException occurrence 越界时
     */
    public int slotAt(int occurrence) {
        return this.slots[occurrence];
    }

    /**
     * 第 occurrence 个槽位在第几列.
     *
     * @param occurrence 使用顺序里的位置
     * @return 列号
     * @throws IndexOutOfBoundsException occurrence 越界时
     */
    public int xAt(int occurrence) {
        return this.slots[occurrence] % this.paneSize.width();
    }

    /**
     * 第 occurrence 个槽位在第几行.
     *
     * @param occurrence 使用顺序里的位置
     * @return 行号
     * @throws IndexOutOfBoundsException occurrence 越界时
     */
    public int yAt(int occurrence) {
        return this.slots[occurrence] / this.paneSize.width();
    }

    /**
     * 选中槽位里最靠左的那一列.
     *
     * @return 最左的列号; 空选择是 -1
     */
    public int minX() {
        return this.minX;
    }

    /**
     * 选中槽位里最靠上的那一行.
     *
     * @return 最上的行号; 空选择是 -1
     */
    public int minY() {
        return this.minY;
    }

    /**
     * 按使用顺序把槽号复制成数组.
     *
     * @return 槽位数组副本
     */
    public int @NotNull [] toArray() {
        return this.slots.clone();
    }

    /**
     * 按使用顺序把每个槽号交给 action.
     *
     * @param action 槽位访问器
     */
    public void forEach(@NotNull IntConsumer action) {
        for (int slot : this.slots) {
            action.accept(slot);
        }
    }

    /**
     * 拿 Pattern 在这组槽位上再挑一遍, 或者重新排个序.
     * <p>Pattern 只能动现有槽位: 输出越界槽号, 没被选中的格, 或者重复槽号都会当场失败.
     * 而且 output 只在 {@link SlotPattern#emit} 返回之前能用, 之后再输出会抛 IllegalStateException.
     *
     * @param pattern 槽位选择方式
     * @return 新的槽位选择; 内容和顺序都没变时还给原来这个实例
     * @throws IndexOutOfBoundsException Pattern 输出了越界槽位时
     * @throws IllegalArgumentException Pattern 输出了非候选或重复槽位时
     */
    @NotNull
    public SlotSequence transform(@NotNull SlotPattern pattern) {
        PatternCollector collector = new PatternCollector(this);
        try {
            // 行优先和列优先走收集器自己的快路径, 别的 pattern 才走回调
            if (pattern == SlotPatterns.ROW_MAJOR) {
                collector.emitRowMajor();
            } else if (pattern == SlotPatterns.COLUMN_MAJOR) {
                collector.emitColumnMajor();
            } else {
                pattern.emit(this, collector);
            }
        } finally {
            // emit 一返回就把输出口关掉, 留着回调以后再写会被拒
            collector.deactivate();
        }
        return collector.finish();
    }

    // 内部数组原样给出, 同包的快路径读它不做复制; 拿到的数组不许改.
    int[] unsafeSlots() {
        return this.slots;
    }

    /**
     * 接 Pattern 的输出, 一边收一边盯着它别越界, 别重复, 也别写非候选的格.
     */
    private static final class PatternCollector implements IntConsumer {
        private final SlotSequence candidates; // 允许输出的那组槽位
        private final byte[] states;           // 每个 Pane 槽位一个字节: 0 非候选, 1 还没输出, 2 已经输出
        private final int[] result;            // 按输出顺序收下来的槽号
        private int size;                      // 已经输出几个
        private boolean active = true;         // 输出口还开着吗

        private PatternCollector(SlotSequence candidates) {
            this.candidates = candidates;
            this.states = new byte[candidates.paneSize.area()];
            this.result = new int[candidates.slots.length];
            for (int index = 0; index < candidates.slots.length; index++) {
                this.states[candidates.slots[index]] = 1;
            }
        }

        // 收一个槽号: 先看输出口开没开, 再看它在不在候选里, 最后看有没有重复.
        @Override
        public void accept(int slot) {
            if (!this.active) {
                throw new IllegalStateException("slot pattern output is no longer active");
            }
            if (slot < 0 || slot >= this.states.length) {
                throw new IndexOutOfBoundsException(
                        "slot " + slot + " is outside " + this.candidates.paneSize
                );
            }
            if (this.states[slot] == 0) {
                throw new IllegalArgumentException("slot " + slot + " is not a candidate");
            }
            if (this.states[slot] == 2) {
                throw new IllegalArgumentException("slot " + slot + " was emitted more than once");
            }
            this.states[slot] = 2;
            this.result[this.size++] = slot;
        }

        // 之后的输出一律拒绝.
        private void deactivate() {
            this.active = false;
        }

        // 行优先快路径: 按槽号升序把候选全部输出, 等于按行优先排了一遍
        private void emitRowMajor() {
            for (int slot = 0; slot < this.states.length; slot++) {
                if (this.states[slot] == 1) {
                    this.accept(slot);
                }
            }
        }

        // 列优先快路径: 外层走列, 内层走行, 把候选全部输出
        private void emitColumnMajor() {
            PaneSize size = this.candidates.paneSize;
            for (int x = 0; x < size.width(); x++) {
                for (int y = 0; y < size.height(); y++) {
                    int slot = size.indexOfTrusted(x, y);
                    if (this.states[slot] == 1) {
                        this.accept(slot);
                    }
                }
            }
        }

        // 用收下来的槽位组装新的选择.
        private SlotSequence finish() {
            // 内容和顺序都没变就把原实例还回去, 省一次分配
            if (this.size == this.candidates.slots.length
                    && Arrays.equals(this.result, this.candidates.slots)) {
                return this.candidates;
            }
            return new SlotSequence(this.candidates.paneSize, Arrays.copyOf(this.result, this.size));
        }
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof SlotSequence sequence
                && this.paneSize.equals(sequence.paneSize)
                && Arrays.equals(this.slots, sequence.slots);
    }

    @Override
    public int hashCode() {
        return 31 * this.paneSize.hashCode() + Arrays.hashCode(this.slots);
    }

    @Override
    public String toString() {
        return "SlotSequence" + Arrays.toString(this.slots);
    }
}
