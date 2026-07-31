package net.momirealms.sparrow.ui.gui;

import org.jetbrains.annotations.NotNull;

import java.util.function.IntConsumer;

public final class SlotPatterns {
    public static final SlotPattern ROW_MAJOR = SlotPatterns::emitRowMajor;             // 从上到下逐行选择, 每行从左到右
    public static final SlotPattern COLUMN_MAJOR = SlotPatterns::emitColumnMajor;       // 从左到右逐列选择, 每列从上到下
    public static final SlotPattern CHECKERBOARD_EVEN = SlotPatterns::emitEvenSquares;  // 只选择为偶数的棋盘格
    public static final SlotPattern CHECKERBOARD_ODD = SlotPatterns::emitOddSquares;    // 只选择为奇数的棋盘格

    private SlotPatterns() {
    }

    /**
     * 返回只选择棋盘格其中一种颜色位置的 Pattern.
     *
     * @param parity {@code 0} 表示偶数格, {@code 1} 表示奇数格
     * @return 棋盘格选择方式
     */
    @NotNull
    public static SlotPattern checkerboard(int parity) {
        return switch (parity) {
            case 0 -> CHECKERBOARD_EVEN;
            case 1 -> CHECKERBOARD_ODD;
            default -> throw new IllegalArgumentException("checkerboard parity must be 0 or 1");
        };
    }

    /**
     * 按从上到下, 每行从左到右的顺序输出选中槽位.
     *
     * @param candidates 候选槽位
     * @param output 接收选中槽位的输出
     */
    private static void emitRowMajor(SlotSequence candidates, IntConsumer output) {
        // 槽位编号本身就是行优先顺序, 直接按编号升序输出
        boolean[] selected = selectedSlots(candidates);
        for (int slot = 0; slot < selected.length; slot++) {
            if (selected[slot]) {
                output.accept(slot);
            }
        }
    }

    /**
     * 按从左到右, 每列从上到下的顺序输出选中槽位.
     *
     * @param candidates 候选槽位
     * @param output 接收选中槽位的输出
     */
    private static void emitColumnMajor(SlotSequence candidates, IntConsumer output) {
        boolean[] selected = selectedSlots(candidates);
        // 外层遍历列, 内层遍历行, 把坐标换算回槽位编号
        GuiSize size = candidates.guiSize();
        for (int x = 0; x < size.width(); x++) {
            for (int y = 0; y < size.height(); y++) {
                int slot = size.indexOfTrusted(x, y);
                if (selected[slot]) {
                    output.accept(slot);
                }
            }
        }
    }

    /**
     * 输出 {@code x + y} 为偶数的选中槽位.
     *
     * @param candidates 候选槽位
     * @param output 接收选中槽位的输出
     */
    private static void emitEvenSquares(SlotSequence candidates, IntConsumer output) {
        emitCheckerboard(candidates, output, 0);
    }

    /**
     * 输出 {@code x + y} 为奇数的选中槽位.
     *
     * @param candidates 候选槽位
     * @param output 接收选中槽位的输出
     */
    private static void emitOddSquares(SlotSequence candidates, IntConsumer output) {
        emitCheckerboard(candidates, output, 1);
    }

    /**
     * 按 {@code x + y} 的奇偶性过滤并输出选中槽位.
     *
     * @param candidates 候选槽位
     * @param output 接收选中槽位的输出
     * @param parity 要保留的奇偶性, {@code 0} 表示偶数格, {@code 1} 表示奇数格
     */
    private static void emitCheckerboard(SlotSequence candidates, IntConsumer output, int parity) {
        // 保持候选原有顺序, 只按坐标奇偶过滤
        for (int index = 0; index < candidates.length(); index++) {
            if (((candidates.xAt(index) + candidates.yAt(index)) & 1) == parity) {
                output.accept(candidates.slotAt(index));
            }
        }
    }

    /**
     * 把候选槽位转成按槽位编号索引的位图, 便于按任意顺序判定选中.
     *
     * @param candidates 候选槽位
     * @return 选中位图, 下标为槽位编号
     */
    private static boolean[] selectedSlots(SlotSequence candidates) {
        boolean[] selected = new boolean[candidates.guiSize().area()];
        int[] slots = candidates.unsafeSlots();
        for (int index = 0; index < slots.length; index++) {
            selected[slots[index]] = true;
        }
        return selected;
    }
}
