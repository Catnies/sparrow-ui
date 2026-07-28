package net.momirealms.sparrow.ui.gui;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.function.IntConsumer;

/**
 * 表示从一个 GUI 中按顺序选中的一组槽位.
 * <p>它用来批量填充指定槽位, 也可作为分页, 滚动和标签页 GUI 的内容位置.
 * 同一槽位不会重复出现, 创建后槽位和顺序都不会改变.
 */
public final class SlotSequence {
    private final GuiSize guiSize; // 槽位所属的 GUI 尺寸
    private final int[] slots; // 按选择顺序排列的槽位编号, 不重复
    private final int minX; // 选中槽位的最小 x 坐标, 空选择为 -1
    private final int minY; // 选中槽位的最小 y 坐标, 空选择为 -1

    /**
     * 创建槽位选择并预计算最小坐标. 调用方必须保证槽位数组不被后续修改.
     *
     * @param guiSize 槽位所属的 GUI 尺寸
     * @param slots 按选择顺序排列的槽位编号
     */
    SlotSequence(GuiSize guiSize, int[] slots) {
        this.guiSize = guiSize;
        this.slots = slots;

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int width = guiSize.width();
        for (int slot : slots) {
            minX = Math.min(minX, slot % width);
            minY = Math.min(minY, slot / width);
        }
        // 空选择用 -1 作为哨兵, 表示没有最小坐标
        this.minX = slots.length == 0 ? -1 : minX;
        this.minY = slots.length == 0 ? -1 : minY;
    }

    /**
     * 按参数给出的顺序选择槽位.
     *
     * @param guiSize 槽位所属的 GUI 尺寸
     * @param slots 要选择的槽位编号
     * @return 槽位选择
     * @throws IndexOutOfBoundsException 槽位编号超出 GUI 范围时抛出
     * @throws IllegalArgumentException 槽位编号重复时抛出
     */
    @NotNull
    public static SlotSequence of(@NotNull GuiSize guiSize, int... slots) {
        int[] copy = slots.clone();
        // 校验范围并拒绝重复槽位
        boolean[] seen = new boolean[guiSize.area()];
        for (int slot : copy) {
            guiSize.checkSlot(slot);
            if (seen[slot]) {
                throw new IllegalArgumentException("duplicate slot " + slot);
            }
            seen[slot] = true;
        }
        return new SlotSequence(guiSize, copy);
    }

    /**
     * 选择 GUI 中的所有槽位.
     *
     * @param guiSize GUI 尺寸
     * @return 包含所有槽位的选择
     */
    @NotNull
    public static SlotSequence all(@NotNull GuiSize guiSize) {
        int[] slots = new int[guiSize.area()];
        Arrays.setAll(slots, index -> index);
        return new SlotSequence(guiSize, slots);
    }

    /**
     * 选择 {@code startInclusive} 到 {@code endExclusive} 之间的槽位.
     *
     * @param guiSize GUI 尺寸
     * @param startInclusive 起始槽位, 包含
     * @param endExclusive 结束槽位, 不包含
     * @return 指定范围的槽位选择
     * @throws IndexOutOfBoundsException 范围超出 GUI 时抛出
     */
    @NotNull
    public static SlotSequence range(
            @NotNull GuiSize guiSize,
            int startInclusive,
            int endExclusive
    ) {
        if (startInclusive < 0 || endExclusive < startInclusive || endExclusive > guiSize.area()) {
            throw new IndexOutOfBoundsException(
                    "range [" + startInclusive + ", " + endExclusive + ") is outside " + guiSize
            );
        }
        int[] slots = new int[endExclusive - startInclusive];
        Arrays.setAll(slots, index -> startInclusive + index);
        return new SlotSequence(guiSize, slots);
    }

    /**
     * 从左到右选择一整行槽位.
     *
     * @param guiSize GUI 尺寸
     * @param row 行号, 从 0 开始
     * @return 一整行槽位
     * @throws IndexOutOfBoundsException 行号超出 GUI 高度时抛出
     */
    @NotNull
    public static SlotSequence row(@NotNull GuiSize guiSize, int row) {
        if (row < 0 || row >= guiSize.height()) {
            throw new IndexOutOfBoundsException("row " + row + " is outside " + guiSize);
        }
        int[] slots = new int[guiSize.width()];
        int start = row * guiSize.width();
        Arrays.setAll(slots, index -> start + index);
        return new SlotSequence(guiSize, slots);
    }

    /**
     * 从上到下选择一整列槽位.
     *
     * @param guiSize GUI 尺寸
     * @param column 列号, 从 0 开始
     * @return 一整列槽位
     * @throws IndexOutOfBoundsException 列号超出 GUI 宽度时抛出
     */
    @NotNull
    public static SlotSequence column(@NotNull GuiSize guiSize, int column) {
        if (column < 0 || column >= guiSize.width()) {
            throw new IndexOutOfBoundsException("column " + column + " is outside " + guiSize);
        }
        int[] slots = new int[guiSize.height()];
        int width = guiSize.width();
        Arrays.setAll(slots, index -> column + index * width);
        return new SlotSequence(guiSize, slots);
    }

    /**
     * 从左上角开始, 逐行选择一个矩形范围内的槽位.
     *
     * @param guiSize GUI 尺寸
     * @param x 矩形左上角 x 坐标
     * @param y 矩形左上角 y 坐标
     * @param width 矩形宽度
     * @param height 矩形高度
     * @return 矩形内的槽位选择
     * @throws IllegalArgumentException 矩形宽高不是正数时抛出
     * @throws IndexOutOfBoundsException 矩形超出 GUI 范围时抛出
     */
    @NotNull
    public static SlotSequence rectangle(
            @NotNull GuiSize guiSize,
            int x,
            int y,
            int width,
            int height
    ) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("rectangle dimensions must be positive");
        }
        if (x < 0 || y < 0 || x + width > guiSize.width() || y + height > guiSize.height()) {
            throw new IndexOutOfBoundsException(
                    "rectangle (" + x + ", " + y + ", " + width + ", " + height + ") is outside " + guiSize
            );
        }

        // 逐行收集, 每行从行首槽位连续取 width 个
        int[] slots = new int[Math.multiplyExact(width, height)];
        int index = 0;
        for (int row = y; row < y + height; row++) {
            int rowStart = guiSize.indexOfTrusted(x, row);
            for (int column = 0; column < width; column++) {
                slots[index++] = rowStart + column;
            }
        }
        return new SlotSequence(guiSize, slots);
    }

    /**
     * 选择 GUI 四周的边框槽位.
     *
     * @param guiSize GUI 尺寸
     * @return 边框槽位选择
     */
    @NotNull
    public static SlotSequence borders(@NotNull GuiSize guiSize) {
        int width = guiSize.width();
        int height = guiSize.height();
        // 0维和1槽的 GUI 选中全部槽位作为 borders.
        if (guiSize.area() == 0 || width == 1 || height == 1) {
            return all(guiSize);
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
        return new SlotSequence(guiSize, slots);
    }

    /**
     * 按原有顺序合并多个槽位选择.
     *
     * <p>所有选择必须属于相同尺寸的 GUI, 且不能包含重复槽位.</p>
     *
     * @param sequences 要合并的槽位选择
     * @return 合并后的槽位选择
     * @throws IllegalArgumentException 没有输入, 尺寸不一致或槽位重复时抛出
     */
    @NotNull
    public static SlotSequence concat(@NotNull SlotSequence... sequences) {
        if (sequences.length == 0) {
            throw new IllegalArgumentException("at least one slot sequence is required");
        }

        // 校验所有选择属于同一尺寸, 并统计总长度
        GuiSize size = sequences[0].guiSize;
        int length = 0;
        for (SlotSequence sequence : sequences) {
            if (!size.equals(sequence.guiSize)) {
                throw new IllegalArgumentException("slot sequences use different GUI sizes");
            }
            length = Math.addExact(length, sequence.slots.length);
        }

        // 按原有顺序收集槽位, 同时拒绝跨选择重复
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

    /**
     * 返回这些槽位所属的 GUI 尺寸.
     *
     * @return GUI 尺寸
     */
    @NotNull
    public GuiSize guiSize() {
        return this.guiSize;
    }

    /**
     * 返回选中的槽位数量.
     *
     * @return 槽位数量
     */
    public int length() {
        return this.slots.length;
    }

    /**
     * 返回是否没有选中任何槽位.
     *
     * @return 没有槽位时为 true
     */
    public boolean isEmpty() {
        return this.slots.length == 0;
    }

    /**
     * 返回指定序号处的槽位编号.
     *
     * @param occurrence 槽位在选择中的序号
     * @return 槽位编号
     */
    public int slotAt(int occurrence) {
        return this.slots[occurrence];
    }

    /**
     * 返回指定序号槽位的 x 坐标.
     *
     * @param occurrence 槽位在选择中的序号
     * @return x 坐标
     */
    public int xAt(int occurrence) {
        return this.slots[occurrence] % this.guiSize.width();
    }

    /**
     * 返回指定序号槽位的 y 坐标.
     *
     * @param occurrence 槽位在选择中的序号
     * @return y 坐标
     */
    public int yAt(int occurrence) {
        return this.slots[occurrence] / this.guiSize.width();
    }

    /**
     * 返回选中槽位中最小的 x 坐标, 空选择返回 -1.
     *
     * @return 最小 x 坐标, 空选择为 -1
     */
    public int minX() {
        return this.minX;
    }

    /**
     * 返回选中槽位中最小的 y 坐标, 空选择返回 -1.
     *
     * @return 最小 y 坐标, 空选择为 -1
     */
    public int minY() {
        return this.minY;
    }

    /**
     * 按选择顺序复制所有槽位编号.
     *
     * @return 槽位编号数组副本
     */
    public int @NotNull [] toArray() {
        return this.slots.clone();
    }

    /**
     * 按选择顺序遍历所有槽位.
     *
     * @param action 接收槽位编号的操作
     */
    public void forEach(@NotNull IntConsumer action) {
        for (int slot : this.slots) {
            action.accept(slot);
        }
    }

    /**
     * 使用 Pattern 重新选择或排列当前槽位.
     *
     * <p>Pattern 只能使用当前已选槽位, 不能输出越界, 未选中或重复槽位.</p>
     *
     * @param pattern 槽位选择方式
     * @return 新的槽位选择
     */
    @NotNull
    public SlotSequence transform(@NotNull SlotPattern pattern) {
        PatternCollector collector = new PatternCollector(this);
        try {
            // 两个内置 Pattern 走快路径, 不必经过回调接口
            if (pattern == SlotPatterns.ROW_MAJOR) {
                collector.emitRowMajor();
            } else if (pattern == SlotPatterns.COLUMN_MAJOR) {
                collector.emitColumnMajor();
            } else {
                pattern.emit(this, collector);
            }
        } finally {
            // 无论成功与否都关闭输出通道, 阻止 Pattern 持有收集器延迟写入
            collector.deactivate();
        }
        return collector.finish();
    }

    /**
     * 直接返回内部槽位数组而不复制. 调用方只能读取, 不得修改.
     *
     * @return 内部槽位数组
     */
    int[] unsafeSlots() {
        return this.slots;
    }

    /**
     * 接收 Pattern 的输出, 同时检查输出是否仍属于候选槽位.
     */
    private static final class PatternCollector implements IntConsumer {
        private final SlotSequence candidates; // 允许输出的候选槽位
        private final byte[] states; // 每个 GUI 槽位的输出状态: 0 表示非候选, 1 表示未输出, 2 表示已输出
        private final int[] result; // 按输出顺序收集的槽位
        private int size; // 已输出的槽位数量
        private boolean active = true; // 输出通道是否仍然可用

        /**
         * 为一次 Pattern 输出创建收集器, 并把候选槽位标记为未输出.
         *
         * @param candidates 候选槽位
         */
        private PatternCollector(SlotSequence candidates) {
            this.candidates = candidates;
            this.states = new byte[candidates.guiSize.area()];
            this.result = new int[candidates.slots.length];
            for (int index = 0; index < candidates.slots.length; index++) {
                this.states[candidates.slots[index]] = 1;
            }
        }

        /**
         * 接收 Pattern 输出的一个槽位, 并校验它合法且未重复.
         *
         * @param slot 输出的槽位编号
         * @throws IllegalStateException 输出通道已关闭时抛出
         * @throws IndexOutOfBoundsException 槽位超出 GUI 范围时抛出
         * @throws IllegalArgumentException 槽位不是候选或重复输出时抛出
         */
        @Override
        public void accept(int slot) {
            // 依次校验: 通道活性, 范围, 候选资格, 重复输出
            if (!this.active) {
                throw new IllegalStateException("slot pattern output is no longer active");
            }
            if (slot < 0 || slot >= this.states.length) {
                throw new IndexOutOfBoundsException(
                        "slot " + slot + " is outside " + this.candidates.guiSize
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

        /**
         * 关闭输出通道, 之后的输出会抛出异常.
         */
        private void deactivate() {
            this.active = false;
        }

        /**
         * 按从上到下, 每行从左到右的顺序输出未输出过的候选槽位.
         */
        private void emitRowMajor() {
            for (int slot = 0; slot < this.states.length; slot++) {
                if (this.states[slot] == 1) {
                    this.accept(slot);
                }
            }
        }

        /**
         * 按从左到右, 每列从上到下的顺序输出未输出过的候选槽位.
         */
        private void emitColumnMajor() {
            GuiSize size = this.candidates.guiSize;
            for (int x = 0; x < size.width(); x++) {
                for (int y = 0; y < size.height(); y++) {
                    int slot = size.indexOfTrusted(x, y);
                    if (this.states[slot] == 1) {
                        this.accept(slot);
                    }
                }
            }
        }

        /**
         * 用收集到的槽位构建新的选择.
         *
         * @return 新的槽位选择
         */
        private SlotSequence finish() {
            // 输出与候选完全一致时直接复用候选实例, 避免等价对象
            if (this.size == this.candidates.slots.length
                    && Arrays.equals(this.result, this.candidates.slots)) {
                return this.candidates;
            }
            return new SlotSequence(this.candidates.guiSize, Arrays.copyOf(this.result, this.size));
        }
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof SlotSequence sequence
                && this.guiSize.equals(sequence.guiSize)
                && Arrays.equals(this.slots, sequence.slots);
    }

    @Override
    public int hashCode() {
        return 31 * this.guiSize.hashCode() + Arrays.hashCode(this.slots);
    }

    @Override
    public String toString() {
        return "SlotSequence" + Arrays.toString(this.slots);
    }
}
