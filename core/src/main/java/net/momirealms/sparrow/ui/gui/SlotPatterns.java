package net.momirealms.sparrow.ui.gui;

import org.jetbrains.annotations.NotNull;

import java.util.function.IntConsumer;

public final class SlotPatterns {
    /** 从上到下逐行选择, 每行从左到右. */
    public static final SlotPattern ROW_MAJOR = SlotPatterns::emitRowMajor;
    /** 从左到右逐列选择, 每列从上到下. */
    public static final SlotPattern COLUMN_MAJOR = SlotPatterns::emitColumnMajor;
    /** 只选择 {@code x + y} 为偶数的棋盘格. */
    public static final SlotPattern CHECKERBOARD_EVEN = SlotPatterns::emitEvenSquares;
    /** 只选择 {@code x + y} 为奇数的棋盘格. */
    public static final SlotPattern CHECKERBOARD_ODD = SlotPatterns::emitOddSquares;

    private SlotPatterns() {
    }

    /**
     * 返回只选择棋盘格其中一种颜色位置的 Pattern.
     *
     * @param parity {@code 0} 表示偶数格, {@code 1} 表示奇数格
     * @return 棋盘格选择方式
     */
    public static @NotNull SlotPattern checkerboard(int parity) {
        return switch (parity) {
            case 0 -> CHECKERBOARD_EVEN;
            case 1 -> CHECKERBOARD_ODD;
            default -> throw new IllegalArgumentException("checkerboard parity must be 0 or 1");
        };
    }

    private static void emitRowMajor(SlotSequence candidates, IntConsumer output) {
        boolean[] selected = selectedSlots(candidates);
        for (int slot = 0; slot < selected.length; slot++) {
            if (selected[slot]) {
                output.accept(slot);
            }
        }
    }

    private static void emitColumnMajor(SlotSequence candidates, IntConsumer output) {
        boolean[] selected = selectedSlots(candidates);
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

    private static void emitEvenSquares(SlotSequence candidates, IntConsumer output) {
        emitCheckerboard(candidates, output, 0);
    }

    private static void emitOddSquares(SlotSequence candidates, IntConsumer output) {
        emitCheckerboard(candidates, output, 1);
    }

    private static void emitCheckerboard(SlotSequence candidates, IntConsumer output, int parity) {
        for (int index = 0; index < candidates.length(); index++) {
            if (((candidates.xAt(index) + candidates.yAt(index)) & 1) == parity) {
                output.accept(candidates.slotAt(index));
            }
        }
    }

    private static boolean[] selectedSlots(SlotSequence candidates) {
        boolean[] selected = new boolean[candidates.guiSize().area()];
        int[] slots = candidates.trustedArray();
        for (int index = 0; index < slots.length; index++) {
            selected[slots[index]] = true;
        }
        return selected;
    }
}
