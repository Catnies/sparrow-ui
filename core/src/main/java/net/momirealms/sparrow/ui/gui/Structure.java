package net.momirealms.sparrow.ui.gui;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 描述 GUI 每个槽位使用的标志符.
 *
 * <p>例如 {@code "ABB"} 表示一行三个槽位, 第一个槽位标记为 {@code A},
 * 后两个标记为 {@code B}. Builder 可以根据标志符一次填充多个槽位.</p>
 *
 * <p>Structure 只保存尺寸, 标志符和槽位位置, 不保存 Item 或 content 列表.
 * 创建后内容不会改变, 可以在多个 GUI 之间共用.</p>
 */
public final class Structure {
    private final GuiSize size;
    private final String[] identifiers; // 内部编号到标志符文本
    private final Map<String, Integer> identifierIndexes; // 标志符文本到内部编号
    private final int[] identifierBySlot; // 每个槽位对应的标志符编号
    private final int[] sourceColumns; // 用于 Builder 失败信息的模板原始列号
    private final SlotSequence[] slotsByIdentifier; // 每个标志符预先选好的槽位

    private Structure(
            GuiSize size,
            String[] identifiers,
            Map<String, Integer> identifierIndexes,
            int[] identifierBySlot,
            int[] sourceColumns,
            SlotSequence[] slotsByIdentifier
    ) {
        this.size = size;
        this.identifiers = identifiers;
        this.identifierIndexes = identifierIndexes;
        this.identifierBySlot = identifierBySlot;
        this.sourceColumns = sourceColumns;
        this.slotsByIdentifier = slotsByIdentifier;
    }

    /**
     * 创建只有尺寸, 没有任何标志符的布局.
     *
     * @param size GUI 尺寸
     * @return 空布局
     */
    public static @NotNull Structure of(@NotNull GuiSize size) {
        int[] identifierBySlot = new int[size.area()];
        Arrays.fill(identifierBySlot, -1);
        return new Structure(
                size,
                new String[0],
                Map.of(),
                identifierBySlot,
                new int[size.area()],
                new SlotSequence[0]
        );
    }

    /**
     * 根据多行字符串创建布局.
     *
     * <p>每个普通 Unicode 字符占一个槽位. 反引号包围的文本作为一个完整标志符,
     * 例如 {@code `confirm button`}. 所有行必须包含相同数量的槽位.</p>
     *
     * @param rows 从上到下排列的模板行
     * @return 解析后的布局
     */
    public static @NotNull Structure of(String @NotNull ... rows) {
        if (rows.length == 0) {
            throw new IllegalArgumentException("structure must contain at least one row");
        }

        Compiler compiler = new Compiler();
        ParsedRow first = compiler.parseBuffered(rows[0], 0);
        if (first.width() == 0) {
            throw new IllegalArgumentException("structure rows must contain at least one slot");
        }

        GuiSize size = new GuiSize(first.width(), rows.length);
        int[] identifierBySlot = new int[size.area()];
        int[] sourceColumns = new int[size.area()];
        compiler.commit(first, 0, identifierBySlot, sourceColumns);

        for (int row = 1; row < rows.length; row++) {
            int offset = row * size.width();
            int actualWidth = compiler.parse(rows[row], row, (identifier, sourceColumn, column) -> {
                if (column < size.width()) {
                    compiler.commit(identifier, offset + column, sourceColumn, identifierBySlot, sourceColumns);
                }
            });
            if (actualWidth != size.width()) {
                throw new IllegalArgumentException("row " + (row + 1) + " has logical width " + actualWidth + ", expected " + size.width());
            }
        }
        return compiler.finish(size, identifierBySlot, sourceColumns);
    }

    /**
     * 使用一段连续文本创建指定尺寸的布局.
     *
     * <p>文本中解析出的槽位数量必须等于 {@link GuiSize#area()}.</p>
     *
     * @param size GUI 尺寸
     * @param flatData 按槽位顺序连续排列的模板文本
     * @return 解析后的布局
     */
    public static @NotNull Structure of(@NotNull GuiSize size, @NotNull String flatData) {
        Compiler compiler = new Compiler();
        int[] identifierBySlot = new int[size.area()];
        int[] sourceColumns = new int[size.area()];
        int actualWidth = compiler.parse(flatData, 0, (identifier, sourceColumn, column) -> {
            if (column >= size.area()) {
                throw new IllegalArgumentException("flat structure has more than " + size.area() + " slots");
            }
            compiler.commit(identifier, column, sourceColumn, identifierBySlot, sourceColumns);
        });
        if (actualWidth != size.area()) {
            throw new IllegalArgumentException("flat structure has " + actualWidth + " slots, expected " + size.area());
        }
        return compiler.finish(size, identifierBySlot, sourceColumns);
    }

    /**
     * 返回这个布局的 GUI 尺寸.
     *
     * @return GUI 尺寸
     */
    public @NotNull GuiSize size() {
        return this.size;
    }

    /**
     * 返回模板中是否出现过指定标志符.
     *
     * @param identifier 标志符
     * @return 标志符存在时为 true
     */
    public boolean contains(@NotNull String identifier) {
        return this.identifierIndexes.containsKey(identifier);
    }

    /**
     * 返回指定槽位的标志符, 没有标志符时返回 null.
     *
     * @param slot 槽位编号
     * @return 槽位标志符, 或 null
     */
    public @Nullable String identifierAt(int slot) {
        int identifierIndex = this.identifierBySlot[slot];
        return identifierIndex < 0 ? null : this.identifiers[identifierIndex];
    }

    /**
     * 选择所有使用指定标志符的槽位.
     *
     * <p>结果按从上到下, 每行从左到右的顺序排列.</p>
     *
     * @param identifier 标志符
     * @return 对应的槽位选择
     */
    public @NotNull SlotSequence slots(@NotNull String identifier) {
        return this.slotsByIdentifier[this.identifierIndex(identifier)];
    }

    /**
     * 选择所有使用指定标志符的槽位, 再使用 Pattern 决定取舍和顺序.
     *
     * @param pattern 槽位选择方式
     * @param identifiers 要合并的标志符
     * @return 筛选并排列后的槽位选择
     */
    public @NotNull SlotSequence slots(@NotNull SlotPattern pattern, String @NotNull ... identifiers) {
        if (identifiers.length == 0) {
            throw new IllegalArgumentException("at least one identifier is required");
        }
        if (identifiers.length == 1) {
            SlotSequence candidates = this.slots(identifiers[0]);
            return pattern == SlotPatterns.ROW_MAJOR ? candidates : candidates.transform(pattern);
        }

        boolean[] selectedIdentifiers = new boolean[this.identifiers.length];
        int slotCount = 0;
        for (int identifier = 0; identifier < identifiers.length; identifier++) {
            int index = this.identifierIndex(identifiers[identifier]);
            if (!selectedIdentifiers[index]) {
                selectedIdentifiers[index] = true;
                slotCount += this.slotsByIdentifier[index].length();
            }
        }

        int[] selectedSlots = new int[slotCount];
        int index = 0;
        for (int slot = 0; slot < this.identifierBySlot.length; slot++) {
            int identifierIndex = this.identifierBySlot[slot];
            if (identifierIndex >= 0 && selectedIdentifiers[identifierIndex]) {
                selectedSlots[index++] = slot;
            }
        }
        SlotSequence candidates = new SlotSequence(this.size, selectedSlots);
        return pattern == SlotPatterns.ROW_MAJOR ? candidates : candidates.transform(pattern);
    }

    int identifierCount() {
        return this.identifiers.length;
    }

    int identifierIndex(String identifier) {
        if (identifier.isEmpty()) {
            throw new IllegalArgumentException("identifier must not be empty");
        }
        Integer index = this.identifierIndexes.get(identifier);
        if (index == null) {
            throw new IllegalArgumentException("identifier '" + identifier + "' does not occur in structure");
        }
        return index;
    }

    String identifier(int index) {
        return this.identifiers[index];
    }

    SlotSequence slots(int identifierIndex) {
        return this.slotsByIdentifier[identifierIndex];
    }

    int sourceColumn(int slot) {
        return this.sourceColumns[slot];
    }

    /**
     * 只在创建 Structure 时使用, 负责读取模板并记录每个标志符的槽位.
     */
    private static final class Compiler {
        private final ArrayList<String> identifiers = new ArrayList<>();
        private final HashMap<String, Integer> identifierIndexes = new HashMap<>();
        private final ArrayList<IntAccumulator> slotsByIdentifier = new ArrayList<>();

        /**
         * 先暂存第一行, 因为只有读完它才能确定 GUI 宽度.
         */
        private ParsedRow parseBuffered(String row, int rowIndex) {
            IntAccumulator identifiers = new IntAccumulator();
            IntAccumulator sourceColumns = new IntAccumulator();
            this.parse(row, rowIndex, (identifier, sourceColumn, ignoredSlot) -> {
                identifiers.add(identifier);
                sourceColumns.add(sourceColumn);
            });
            return new ParsedRow(identifiers.toArray(), sourceColumns.toArray());
        }

        /**
         * 从左到右读取一行, 并把每个标志符交给 consumer.
         */
        private int parse(String row, int rowIndex, TokenConsumer consumer) {
            int sourceIndex = 0;
            int sourceColumn = 1;
            int logicalColumn = 0;

            while (sourceIndex < row.length()) {
                int tokenSourceColumn = sourceColumn;
                int codePoint = checkedCodePointAt(row, sourceIndex, rowIndex);
                rejectControl(rowIndex, sourceColumn, codePoint);

                // 普通字符直接作为一个槽位标志符
                if (codePoint != '`') {
                    String identifier = new String(Character.toChars(codePoint));
                    consumer.accept(this.identifier(identifier), tokenSourceColumn, logicalColumn++);
                    sourceIndex += Character.charCount(codePoint);
                    sourceColumn++;
                    continue;
                }

                // 反引号内的多个字符组成一个标志符
                sourceIndex++;
                sourceColumn++;
                StringBuilder decoded = new StringBuilder();
                boolean closed = false;
                while (sourceIndex < row.length()) {
                    codePoint = checkedCodePointAt(row, sourceIndex, rowIndex);
                    rejectControl(rowIndex, sourceColumn, codePoint);
                    if (codePoint == '`') {
                        if (decoded.isEmpty()) {
                            throw syntaxError(rowIndex, tokenSourceColumn, "quoted identifier must not be empty");
                        }
                        sourceIndex++;
                        sourceColumn++;
                        closed = true;
                        break;
                    }
                    if (codePoint == '\\') {
                        int escapeColumn = sourceColumn;
                        sourceIndex++;
                        sourceColumn++;
                        if (sourceIndex >= row.length()) {
                            throw syntaxError(rowIndex, escapeColumn, "unterminated escape sequence");
                        }
                        codePoint = checkedCodePointAt(row, sourceIndex, rowIndex);
                        if (codePoint != '`' && codePoint != '\\') {
                            throw syntaxError(rowIndex, escapeColumn, "unsupported escape sequence");
                        }
                    }

                    decoded.appendCodePoint(codePoint);
                    sourceIndex += Character.charCount(codePoint);
                    sourceColumn++;
                }

                if (!closed) {
                    throw syntaxError(rowIndex, tokenSourceColumn, "unterminated quoted identifier");
                }
                consumer.accept(this.identifier(decoded.toString()), tokenSourceColumn, logicalColumn++);
            }
            return logicalColumn;
        }

        private int identifier(String identifier) {
            Integer existing = this.identifierIndexes.get(identifier);
            if (existing != null) {
                return existing;
            }

            int index = this.identifiers.size();
            this.identifiers.add(identifier);
            this.identifierIndexes.put(identifier, index);
            this.slotsByIdentifier.add(new IntAccumulator());
            return index;
        }

        private void commit(
                ParsedRow row,
                int offset,
                int[] identifierBySlot,
                int[] sourceColumns
        ) {
            int[] rowIdentifiers = row.identifiers();
            int[] rowSourceColumns = row.sourceColumns();
            for (int column = 0; column < rowIdentifiers.length; column++) {
                this.commit(
                        rowIdentifiers[column],
                        offset + column,
                        rowSourceColumns[column],
                        identifierBySlot,
                        sourceColumns
                );
            }
        }

        private void commit(
                int identifier,
                int slot,
                int sourceColumn,
                int[] identifierBySlot,
                int[] sourceColumns
        ) {
            identifierBySlot[slot] = identifier;
            sourceColumns[slot] = sourceColumn;
            this.slotsByIdentifier.get(identifier).add(slot);
        }

        private Structure finish(GuiSize size, int[] identifierBySlot, int[] sourceColumns) {
            String[] identifiers = this.identifiers.toArray(String[]::new);
            SlotSequence[] slots = new SlotSequence[identifiers.length];
            for (int index = 0; index < slots.length; index++) {
                slots[index] = SlotSequence.of(size, this.slotsByIdentifier.get(index).toArray());
            }
            return new Structure(
                    size,
                    identifiers,
                    Map.copyOf(this.identifierIndexes),
                    identifierBySlot,
                    sourceColumns,
                    slots
            );
        }
    }

    @FunctionalInterface
    private interface TokenConsumer {
        void accept(int identifier, int sourceColumn, int logicalColumn);
    }

    private record ParsedRow(int[] identifiers, int[] sourceColumns) {
        private int width() {
            return this.identifiers.length;
        }
    }

    /**
     * 读取一个 Unicode 字符, 并拒绝不完整的 surrogate pair.
     */
    private static int checkedCodePointAt(String source, int index, int rowIndex) {
        char first = source.charAt(index);
        if (Character.isHighSurrogate(first)) {
            if (index + 1 >= source.length() || !Character.isLowSurrogate(source.charAt(index + 1))) {
                throw syntaxError(rowIndex, source.codePointCount(0, index) + 1, "unpaired high surrogate");
            }
        } else if (Character.isLowSurrogate(first)) {
            throw syntaxError(rowIndex, source.codePointCount(0, index) + 1, "unpaired low surrogate");
        }
        return Character.codePointAt(source, index);
    }

    private static void rejectControl(int rowIndex, int sourceColumn, int codePoint) {
        if (Character.isISOControl(codePoint)) {
            throw syntaxError(rowIndex, sourceColumn, "control characters are not allowed");
        }
    }

    private static IllegalArgumentException syntaxError(int rowIndex, int sourceColumn, String message) {
        return new IllegalArgumentException(
                message + " at row " + (rowIndex + 1) + ", source column " + sourceColumn
        );
    }

    /**
     * 用可扩容的 int 数组暂存槽位.
     */
    private static final class IntAccumulator {
        private int[] values = new int[8];
        private int size;

        private void add(int value) {
            if (this.size == this.values.length) {
                this.values = Arrays.copyOf(this.values, this.values.length * 2);
            }
            this.values[this.size++] = value;
        }

        private int[] toArray() {
            return Arrays.copyOf(this.values, this.size);
        }
    }
}
