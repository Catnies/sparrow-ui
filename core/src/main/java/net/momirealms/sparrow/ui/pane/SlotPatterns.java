package net.momirealms.sparrow.ui.pane;

import org.jetbrains.annotations.NotNull;

import java.util.function.IntConsumer;

public final class SlotPatterns {
    public static final SlotPattern ROW_MAJOR = SlotPatterns::emitRowMajor;             // 从上到下逐行, 每行从左到右
    public static final SlotPattern COLUMN_MAJOR = SlotPatterns::emitColumnMajor;       // 从左到右逐列, 每列从上到下
    public static final SlotPattern CHECKERBOARD_EVEN = SlotPatterns::emitEvenSquares;  // 棋盘格中 x + y 为偶数的一半
    public static final SlotPattern CHECKERBOARD_ODD = SlotPatterns::emitOddSquares;    // 棋盘格中 x + y 为奇数的一半

    private SlotPatterns() {
    }

    /**
     * 要棋盘格的其中一半时, 用它按奇偶挑一个.
     *
     * @param parity {@code 0} 是 x + y 为偶数的那半, {@code 1} 是奇数的那半
     * @return 对应的棋盘格 Pattern
     * @throws IllegalArgumentException parity 不是 0 或 1 时
     */
    @NotNull
    public static SlotPattern checkerboard(int parity) {
        return switch (parity) {
            case 0 -> CHECKERBOARD_EVEN;
            case 1 -> CHECKERBOARD_ODD;
            default -> throw new IllegalArgumentException("checkerboard parity must be 0 or 1");
        };
    }

    // 槽号本身就是行优先编号, 直接按编号升序输出就够了
    private static void emitRowMajor(SlotSequence candidates, IntConsumer output) {
        boolean[] selected = selectedSlots(candidates);
        for (int slot = 0; slot < selected.length; slot++) {
            if (selected[slot]) {
                output.accept(slot);
            }
        }
    }

    // 列优先得换个走法: 外层遍历列, 内层遍历行, 再把坐标换算回槽号
    private static void emitColumnMajor(SlotSequence candidates, IntConsumer output) {
        boolean[] selected = selectedSlots(candidates);
        PaneSize size = candidates.paneSize();
        for (int x = 0; x < size.width(); x++) {
            for (int y = 0; y < size.height(); y++) {
                int slot = size.indexOfTrusted(x, y);
                if (selected[slot]) {
                    output.accept(slot);
                }
            }
        }
    }

    private static void emitEvenSquares(SlotSequence candidates, IntConsumer output) {
        emitCheckerboard(candidates, output, 0);
    }

    private static void emitOddSquares(SlotSequence candidates, IntConsumer output) {
        emitCheckerboard(candidates, output, 1);
    }

    // 按 x + y 的奇偶过滤, 顺序沿用候选自己的
    private static void emitCheckerboard(SlotSequence candidates, IntConsumer output, int parity) {
        for (int index = 0; index < candidates.length(); index++) {
            if (((candidates.xAt(index) + candidates.yAt(index)) & 1) == parity) {
                output.accept(candidates.slotAt(index));
            }
        }
    }

    // 把候选摊成一张按槽号索引的位图, 之后不管用什么顺序遍历都能随手判它选没选中
    private static boolean[] selectedSlots(SlotSequence candidates) {
        boolean[] selected = new boolean[candidates.paneSize().area()];
        int[] slots = candidates.unsafeSlots();
        for (int index = 0; index < slots.length; index++) {
            selected[slots[index]] = true;
        }
        return selected;
    }
}
