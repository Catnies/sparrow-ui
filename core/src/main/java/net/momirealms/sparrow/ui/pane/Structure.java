package net.momirealms.sparrow.ui.pane;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.momirealms.sparrow.ui.util.TriIntConsumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public final class Structure {
    private final PaneSize size;          // Pane 尺寸
    private final String[] identifiers;  // 内部编号到标志符文本
    private final Map<String, Integer> identifierIndexes; // 标志符文本到内部编号
    private final int[] identifierBySlot;   // 每个槽位对应的标志符编号, -1 表示没有标志符
    private final int[] sourceColumns;      // 用于 Builder 失败信息的模板原始列号
    private final SlotSequence[] slotsByIdentifier; // 每个标志符预先选好的槽位

    private Structure(
            PaneSize size,
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
     * @param size Pane 尺寸
     * @return 空布局
     */
    @NotNull
    public static Structure of(@NotNull PaneSize size) {
        // -1 表示槽位没有任何标志符
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
     * <p>每个普通 Unicode 字符占一个槽位. 反引号包围的文本作为一个完整标志符,
     * 例如 {@code `confirm button`}. 所有行必须包含相同数量的槽位.
     *
     * @param rows 从上到下排列的模板行
     * @return 解析后的布局
     * @throws IllegalArgumentException 没有模板行, 首行为空, 行宽不一致或模板语法错误时抛出
     */
    @NotNull
    public static Structure of(String @NotNull ... rows) {
        if (rows.length == 0)
            throw new IllegalArgumentException("structure must contain at least one row");

        // 先解析第一行并暂存, 用它确定 Pane 宽度
        Compiler compiler = new Compiler();
        ParsedRow first = compiler.parseBuffered(rows[0], 0);
        if (first.width() == 0)
            throw new IllegalArgumentException("structure rows must contain at least one slot");

        // 第一行确定尺寸后, 其余行必须包含相同数量的 Pane 槽位
        PaneSize size = new PaneSize(first.width(), rows.length);
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
     * <p>文本中解析出的槽位数量必须等于 {@link PaneSize#area()}.
     *
     * @param size Pane 尺寸
     * @param flatData 按槽位顺序连续排列的模板文本
     * @return 解析后的布局
     * @throws IllegalArgumentException 槽位数量与尺寸不符或模板语法错误时抛出
     */
    @NotNull
    public static Structure of(@NotNull PaneSize size, @NotNull String flatData) {
        Compiler compiler = new Compiler();
        int[] identifierBySlot = new int[size.area()];
        int[] sourceColumns = new int[size.area()];
        // 超出面积的槽位直接失败, 避免写入越界
        int actualWidth = compiler.parse(flatData, 0, (identifier, sourceColumn, column) -> {
            if (column >= size.area())
                throw new IllegalArgumentException("flat structure has more than " + size.area() + " slots");
            compiler.commit(identifier, column, sourceColumn, identifierBySlot, sourceColumns);
        });
        if (actualWidth != size.area()) {
            throw new IllegalArgumentException("flat structure has " + actualWidth + " slots, expected " + size.area());
        }
        return compiler.finish(size, identifierBySlot, sourceColumns);
    }

    /**
     * 指定槽位的标志符, 没有标志符时返回 null.
     *
     * @param slot 槽位编号
     * @return 槽位标志符, 或 null
     */
    @Nullable
    public String identifierAt(int slot) {
        int identifierIndex = this.identifierBySlot[slot];
        return identifierIndex < 0 ? null : this.identifiers[identifierIndex];
    }

    /**
     * 选择所有使用指定标志符的槽位.
     * <p>结果按从上到下, 每行从左到右的顺序排列.
     *
     * @param identifier 标志符
     * @return 对应的槽位选择
     * @throws IllegalArgumentException 标志符为空或未在模板中出现时抛出
     */
    @NotNull
    public SlotSequence slots(@NotNull String identifier) {
        return this.slotsByIdentifier[this.identifierIndex(identifier)];
    }

    /**
     * 选择所有使用指定标志符的槽位, 再使用 Pattern 决定取舍和顺序.
     *
     * @param pattern 槽位选择方式
     * @param identifiers 要合并的标志符
     * @return 筛选并排列后的槽位选择
     * @throws IllegalArgumentException 没有标志符, 标志符为空或未在模板中出现时抛出
     */
    @NotNull
    public SlotSequence slots(@NotNull SlotPattern pattern, String @NotNull ... identifiers) {
        if (identifiers.length == 0) {
            throw new IllegalArgumentException("at least one identifier is required");
        }
        // 单标志符直接使用预先选好的槽位, 无需重新收集
        if (identifiers.length == 1) {
            SlotSequence candidates = this.slots(identifiers[0]);
            return pattern == SlotPatterns.ROW_MAJOR ? candidates : candidates.transform(pattern);
        }

        // 先把要合并的标志符标成位图, 同时统计槽位总数
        boolean[] selectedIdentifiers = new boolean[this.identifiers.length];
        int slotCount = 0;
        for (int identifier = 0; identifier < identifiers.length; identifier++) {
            int index = this.identifierIndex(identifiers[identifier]);
            if (!selectedIdentifiers[index]) {
                selectedIdentifiers[index] = true;
                slotCount += this.slotsByIdentifier[index].length();
            }
        }

        // 再按槽位编号顺序收集所有选中标志符的槽位
        int[] selectedSlots = new int[slotCount];
        int index = 0;
        for (int slot = 0; slot < this.identifierBySlot.length; slot++) {
            int identifierIndex = this.identifierBySlot[slot];
            if (identifierIndex >= 0 && selectedIdentifiers[identifierIndex]) {
                selectedSlots[index++] = slot;
            }
        }
        SlotSequence candidates = new SlotSequence(this.size, selectedSlots);
        // 收集顺序本身就是行优先, ROW_MAJOR 无需再走 Pattern
        return pattern == SlotPatterns.ROW_MAJOR ? candidates : candidates.transform(pattern);
    }


    @NotNull
    public PaneSize size() {
        return this.size;
    }

    public boolean contains(@NotNull String identifier) {
        return this.identifierIndexes.containsKey(identifier);
    }

    // 布局中不同标志符的数量.
    int identifierCount() {
        return this.identifiers.length;
    }

    // 标志符的内部编号; 为空或未在模板中出现时抛 IllegalArgumentException.
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

    // 按内部编号返回标志符文本.
    String identifier(int index) {
        return this.identifiers[index];
    }

    // 按内部编号返回预先选好的槽位.
    SlotSequence slots(int identifierIndex) {
        return this.slotsByIdentifier[identifierIndex];
    }

    // 槽位在模板中的原始列号, 用于错误定位.
    int sourceColumn(int slot) {
        return this.sourceColumns[slot];
    }

    // 只在创建 Structure 时使用, 负责读取模板并记录每个标志符的槽位.
    private static final class Compiler {
        private final ArrayList<String> identifiers = new ArrayList<>(); // 内部编号到标志符文本
        private final HashMap<String, Integer> identifierIndexes = new HashMap<>(); // 标志符文本到内部编号
        private final ArrayList<IntArrayList> slotsByIdentifier = new ArrayList<>(); // 每个标志符按出现顺序记录的槽位

        // 先暂存第一行, 因读完它确定 Pane 宽度.
        private ParsedRow parseBuffered(String row, int rowIndex) {
            IntArrayList identifiers = new IntArrayList();
            IntArrayList sourceColumns = new IntArrayList();
            this.parse(row, rowIndex, (identifier, sourceColumn, ignoredSlot) -> {
                identifiers.add(identifier);
                sourceColumns.add(sourceColumn);
            });
            return new ParsedRow(identifiers.toIntArray(), sourceColumns.toIntArray());
        }

        /**
         * 从左到右读取一行, 并把每个标志符交给 consumer.
         *
         * @param row 模板行文本
         * @param rowIndex 行号, 用于错误定位
         * @param tokenConsumer 接收标志符的回调, (identifier 标志符内部编号, sourceColumn 模板中的原始列号, logicalColumn Pane 槽位列号)
         * @return 该行的 Pane 槽位数量
         * @throws IllegalArgumentException 模板语法错误时抛出
         */
        private int parse(String row, int rowIndex, TriIntConsumer tokenConsumer) {
            int sourceIndex = 0;
            int sourceColumn = 1;
            int logicalColumn = 0;

            while (sourceIndex < row.length()) {
                int tokenSourceColumn = sourceColumn;
                int codePoint = checkedCodePointAt(row, sourceIndex, rowIndex);
                // 拒绝控制字符, 防止模板中混入不可见字符.
                if (Character.isISOControl(codePoint)) {
                    throw syntaxError(rowIndex, sourceColumn, "control characters are not allowed");
                }

                // 普通字符直接作为一个槽位标志符
                if (codePoint != '`') {
                    String identifier = new String(Character.toChars(codePoint));
                    tokenConsumer.accept(this.identifier(identifier), tokenSourceColumn, logicalColumn++);
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
                    // 拒绝控制字符, 防止模板中混入不可见字符.
                    if (Character.isISOControl(codePoint)) {
                        throw syntaxError(rowIndex, sourceColumn, "control characters are not allowed");
                    }
                    if (codePoint == '`') {
                        if (decoded.isEmpty()) {
                            throw syntaxError(rowIndex, tokenSourceColumn, "quoted identifier must not be empty");
                        }
                        sourceIndex++;
                        sourceColumn++;
                        closed = true;
                        break;
                    }
                    // 反引号内只允许转义反引号与反斜杠本身
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
                tokenConsumer.accept(this.identifier(decoded.toString()), tokenSourceColumn, logicalColumn++);
            }
            return logicalColumn;
        }

        // 标志符的内部编号, 首次出现时登记新编号.
        private int identifier(String identifier) {
            Integer existing = this.identifierIndexes.get(identifier);
            if (existing != null) {
                return existing;
            }

            int index = this.identifiers.size();
            this.identifiers.add(identifier);
            this.identifierIndexes.put(identifier, index);
            this.slotsByIdentifier.add(new IntArrayList());
            return index;
        }

        // 把暂存的一行写入槽位映射.
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

        // 把单个标志符写入槽位映射, 并记录到该标志符的槽位列表.
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

        // 把解析结果整理成不可变的 Structure.
        private Structure finish(PaneSize size, int[] identifierBySlot, int[] sourceColumns) {
            String[] identifiers = this.identifiers.toArray(String[]::new);
            // 每个标志符的槽位列表整理成预先选好的 SlotSequence
            SlotSequence[] slots = new SlotSequence[identifiers.length];
            for (int index = 0; index < slots.length; index++) {
                slots[index] = SlotSequence.of(size, this.slotsByIdentifier.get(index).toIntArray());
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

    /**
     * 暂存的第一行解析结果.
     *
     * @param identifiers 按槽位顺序的标志符内部编号
     * @param sourceColumns 每个槽位在模板中的原始列号
     */
    private record ParsedRow(int[] identifiers, int[] sourceColumns) {
        private int width() {
            return this.identifiers.length;
        }
    }

    // 读取一个 Unicode 字符, 拒绝不成对的 surrogate.
    private static int checkedCodePointAt(String source, int index, int rowIndex) {
        char first = source.charAt(index);
        // 高 surrogate 后必须紧跟低 surrogate, 否则模板只包含半个字符
        if (Character.isHighSurrogate(first)) {
            if (index + 1 >= source.length() || !Character.isLowSurrogate(source.charAt(index + 1))) {
                throw syntaxError(rowIndex, source.codePointCount(0, index) + 1, "unpaired high surrogate");
            }
        // 低 surrogate 不能单独出现
        } else if (Character.isLowSurrogate(first)) {
            throw syntaxError(rowIndex, source.codePointCount(0, index) + 1, "unpaired low surrogate");
        }
        return Character.codePointAt(source, index);
    }

    // 构造带行号(从 0)和列号(从 1)的模板语法错误.
    private static IllegalArgumentException syntaxError(int rowIndex, int sourceColumn, String message) {
        return new IllegalArgumentException(message + " at row " + (rowIndex + 1) + ", source column " + sourceColumn);
    }
}
