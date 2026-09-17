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
    private final PaneSize size;                            // Pane 尺寸
    private final String[] identifiers;                     // 标志符内部编号 -> 模板里写的文本
    private final Map<String, Integer> identifierIndexes;   // 模板里写的文本 -> 内部编号
    private final int[] identifierBySlot;                   // 每个槽位的标志符编号, -1 表示这一格没有标志符
    private final int[] sourceColumns;                      // 每个槽位在模板里的原始列号, 报错时指回模板
    private final SlotSequence[] slotsByIdentifier;         // 每个标志符的槽位, 解析完就选好不再动

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
     * 只要一块给定尺寸的空白布局, 一个标志符都没有.
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
     * 按多行模板建布局.
     * <p>每个普通 Unicode 字符占一格, 反引号里的文本算一个标志符, 例如 {@code `confirm button`}.
     * 所有行的槽位数必须一样.
     *
     * @param rows 从上到下排列的模板行
     * @return 解析好的布局
     * @throws IllegalArgumentException 没有模板行, 首行是空的, 各行宽度对不上, 或者模板语法有错时
     */
    @NotNull
    public static Structure of(String @NotNull ... rows) {
        if (rows.length == 0)
            throw new IllegalArgumentException("structure must contain at least one row");

        // 第一行先解析暂存, 整块 Pane 有多宽由它说了算
        Compiler compiler = new Compiler();
        ParsedRow first = compiler.parseBuffered(rows[0], 0);
        if (first.width() == 0)
            throw new IllegalArgumentException("structure rows must contain at least one slot");

        // 宽度定下来之后, 后面每行都按同一宽度落位, 对不上就是模板自己有毛病
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
     * 把一整段文本按槽号顺序摊进给定尺寸的布局.
     * <p>文本里的格子数必须正好等于 {@link PaneSize#area()}.
     *
     * @param size Pane 尺寸
     * @param flatData 按槽号顺序连成一段的模板文本
     * @return 解析好的布局
     * @throws IllegalArgumentException 格子数和尺寸对不上, 或者模板语法有错时
     */
    @NotNull
    public static Structure of(@NotNull PaneSize size, @NotNull String flatData) {
        Compiler compiler = new Compiler();
        int[] identifierBySlot = new int[size.area()];
        int[] sourceColumns = new int[size.area()];
        // 多出来的格子当场失败, 别让它写到数组外面
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
     * 这一格的标志符, 这一格没有标志符时给 null.
     *
     * @param slot 槽位编号
     * @return 槽位标志符, 没有就是 null
     * @throws IndexOutOfBoundsException 槽号越界时
     */
    @Nullable
    public String identifierAt(int slot) {
        int identifierIndex = this.identifierBySlot[slot];
        return identifierIndex < 0 ? null : this.identifiers[identifierIndex];
    }

    /**
     * 这个标志符占的全部槽位, 按从上到下, 每行从左到右排.
     *
     * @param identifier 标志符
     * @return 这个标志符的槽位
     * @throws IllegalArgumentException 标志符是空串, 或者模板里根本没有它时
     */
    @NotNull
    public SlotSequence slots(@NotNull String identifier) {
        return this.slotsByIdentifier[this.identifierIndex(identifier)];
    }

    /**
     * 把几个标志符的槽位合到一起, 再按 Pattern 决定留哪些, 什么顺序.
     *
     * @param pattern 槽位选择方式
     * @param identifiers 要合并的标志符
     * @return 筛过也排过的槽位选择
     * @throws IllegalArgumentException 一个标志符都没给, 给了空串, 或者模板里根本没有它时
     */
    @NotNull
    public SlotSequence slots(@NotNull SlotPattern pattern, String @NotNull ... identifiers) {
        if (identifiers.length == 0) {
            throw new IllegalArgumentException("at least one identifier is required");
        }
        // 只有一个标志符时, 解析期选好的槽位直接能用, 不用重收
        if (identifiers.length == 1) {
            SlotSequence candidates = this.slots(identifiers[0]);
            return pattern == SlotPatterns.ROW_MAJOR ? candidates : candidates.transform(pattern);
        }

        // 先把要用到的标志符标成位图, 顺便数清楚一共要收多少格
        boolean[] selectedIdentifiers = new boolean[this.identifiers.length];
        int slotCount = 0;
        for (int identifier = 0; identifier < identifiers.length; identifier++) {
            int index = this.identifierIndex(identifiers[identifier]);
            if (!selectedIdentifiers[index]) {
                selectedIdentifiers[index] = true;
                slotCount += this.slotsByIdentifier[index].length();
            }
        }

        // 按槽号从小到大收, 收出来的顺序天然就是行优先
        int[] selectedSlots = new int[slotCount];
        int index = 0;
        for (int slot = 0; slot < this.identifierBySlot.length; slot++) {
            int identifierIndex = this.identifierBySlot[slot];
            if (identifierIndex >= 0 && selectedIdentifiers[identifierIndex]) {
                selectedSlots[index++] = slot;
            }
        }
        SlotSequence candidates = new SlotSequence(this.size, selectedSlots);
        // 顺序已经是行优先, ROW_MAJOR 不用再走一遍 pattern
        return pattern == SlotPatterns.ROW_MAJOR ? candidates : candidates.transform(pattern);
    }


    @NotNull
    public PaneSize size() {
        return this.size;
    }

    public boolean contains(@NotNull String identifier) {
        return this.identifierIndexes.containsKey(identifier);
    }

    // 模板里出现过多少种标志符, 重复的不另算
    int identifierCount() {
        return this.identifiers.length;
    }

    // 把模板里写的名字换成内部编号. 查不到就抛, 这个编号要当下标用, 不能含糊过去.
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

    // 槽位在模板里的原始列号, 报错时靠它指回模板的那一格.
    int sourceColumn(int slot) {
        return this.sourceColumns[slot];
    }

    // 模板解析器: 一边读字符一边认标志符, 顺手把每个标志符占的槽位记下来.
    private static final class Compiler {
        private final ArrayList<String> identifiers = new ArrayList<>();
        private final HashMap<String, Integer> identifierIndexes = new HashMap<>();
        private final ArrayList<IntArrayList> slotsByIdentifier = new ArrayList<>();

        // 第一行只解析不落位, 整块 Pane 有多宽要等它读完才知道.
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
         * 从左到右读一行, 每读出一格就把标志符编号, 模板列号和行内列号交给 consumer.
         *
         * @param row 模板行文本
         * @param rowIndex 行号, 报错时用
         * @param tokenConsumer 每一格的回调
         * @return 这一行有多少格
         * @throws IllegalArgumentException 模板语法不对时
         */
        private int parse(String row, int rowIndex, TriIntConsumer tokenConsumer) {
            int sourceIndex = 0;
            int sourceColumn = 1;
            int logicalColumn = 0;

            while (sourceIndex < row.length()) {
                int tokenSourceColumn = sourceColumn;
                int codePoint = checkedCodePointAt(row, sourceIndex, rowIndex);
                // 控制字符在模板里看不见, 也不该当内容用, 一律判语法错
                if (Character.isISOControl(codePoint)) {
                    throw syntaxError(rowIndex, sourceColumn, "control characters are not allowed");
                }

                if (codePoint != '`') {
                    String identifier = new String(Character.toChars(codePoint));
                    tokenConsumer.accept(this.identifier(identifier), tokenSourceColumn, logicalColumn++);
                    sourceIndex += Character.charCount(codePoint);
                    sourceColumn++;
                    continue;
                }

                // 反引号开场, 一直读到配对的反引号, 中间这段算一个标志符
                sourceIndex++;
                sourceColumn++;
                StringBuilder decoded = new StringBuilder();
                boolean closed = false;
                while (sourceIndex < row.length()) {
                    codePoint = checkedCodePointAt(row, sourceIndex, rowIndex);
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
                    // 引号里只认反引号和反斜杠这两种转义, 别的一律报错
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

        // 现给标志符编个号, 头一次见到就登记一个.
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

        // 把暂存的那一行正式落位.
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

        // 一格落位: 记下这格是谁的, 同时挂进那个标志符的槽位列表.
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

        // 收尾: 把解析结果整理成不可变的 Structure.
        private Structure finish(PaneSize size, int[] identifierBySlot, int[] sourceColumns) {
            String[] identifiers = this.identifiers.toArray(String[]::new);
            // 每个标志符的槽位收成 SlotSequence, 之后查它就不用再挑一遍
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
     * 第一行解析出来的东西, 先攒着等宽度定下来.
     *
     * @param identifiers 按格子顺序的标志符内部编号
     * @param sourceColumns 每格在模板里的原始列号
     */
    private record ParsedRow(int[] identifiers, int[] sourceColumns) {
        private int width() {
            return this.identifiers.length;
        }
    }

    // 一个 code point 算一格, 所以代理对必须成对出现, 只有半个字符的模板没法解释
    private static int checkedCodePointAt(String source, int index, int rowIndex) {
        char first = source.charAt(index);
        // 高位后面必须跟着低位, 不然就是半个字符
        if (Character.isHighSurrogate(first)) {
            if (index + 1 >= source.length() || !Character.isLowSurrogate(source.charAt(index + 1))) {
                throw syntaxError(rowIndex, source.codePointCount(0, index) + 1, "unpaired high surrogate");
            }
        // 低位单独出现同样是半个字符
        } else if (Character.isLowSurrogate(first)) {
            throw syntaxError(rowIndex, source.codePointCount(0, index) + 1, "unpaired low surrogate");
        }
        return Character.codePointAt(source, index);
    }

    // 报给用户的行列号从 1 数起, 和编辑器里看到的位置对上
    private static IllegalArgumentException syntaxError(int rowIndex, int sourceColumn, String message) {
        return new IllegalArgumentException(message + " at row " + (rowIndex + 1) + ", source column " + sourceColumn);
    }
}
