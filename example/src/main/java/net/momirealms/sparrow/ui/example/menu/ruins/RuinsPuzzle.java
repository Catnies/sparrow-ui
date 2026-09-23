package net.momirealms.sparrow.ui.example.menu.ruins;

// 低位按行存放符文状态, 1 表示点亮. 每次操作生成完整状态供 UI 一次发布.
record RuinsPuzzle(int level, int lights, int moves, int hints) {
    static final int LEVELS = 2;

    static RuinsPuzzle start(int level) {
        // 题面由全亮状态按固定落点翻转得到, 两关的最短解分别为 3 步和 13 步.
        return new RuinsPuzzle(level, level == 0 ? 0x0ee : 0x0022880, 0, 0);
    }

    int width() {
        return this.level == 0 ? 3 : 5;
    }

    int cells() {
        return this.width() * this.width();
    }

    String difficulty() {
        return this.level == 0 ? "入门 · 3×3" : "挑战 · 5×5";
    }

    boolean solved() {
        return this.lights == (1 << this.cells()) - 1;
    }

    // 棋盘在 5×5 显示区内居中, 区域外的格子留空.
    int cellAt(int x, int y) {
        int width = this.width();
        int offset = (5 - width) / 2;
        x -= offset;
        y -= offset;
        if (x < 0 || y < 0 || x >= width || y >= width) return -1;
        return y * width + x;
    }

    RuinsPuzzle press(int cell) {
        if (this.solved()) return this;
        return new RuinsPuzzle(this.level, this.lights ^ mask(this.width(), cell), this.moves + 1, this.hints);
    }

    RuinsPuzzle reset() {
        return start(this.level);
    }

    RuinsPuzzle hinted() {
        return new RuinsPuzzle(this.level, this.lights, this.moves, this.hints + 1);
    }

    int solution() {
        return solve(this.width(), this.lights);
    }

    static int mask(int width, int cell) {
        int result = 1 << cell;
        int row = cell / width;
        int column = cell % width;
        if (row > 0) {
            result |= 1 << (cell - width);
        }
        if (row < width - 1) {
            result |= 1 << (cell + width);
        }
        if (column > 0) {
            result |= 1 << (cell - 1);
        }
        if (column < width - 1) {
            result |= 1 << (cell + 1);
        }
        return result;
    }

    // 枚举第一行的点击组合, 后续各行由上一行尚未亮起的格子决定; 5×5 也只枚举 32 种.
    static int solve(int width, int lights) {
        int cells = width * width;
        int all = (1 << cells) - 1;
        int best = -1;
        for (int firstRow = 0; firstRow < (1 << width); firstRow++) {
            int board = lights;
            int presses = firstRow;
            for (int cell = 0; cell < cells; cell++) {
                if (cell >= width && (board & (1 << (cell - width))) == 0) {
                    presses |= 1 << cell;
                }
                if ((presses & (1 << cell)) != 0) {
                    board ^= mask(width, cell);
                }
            }
            if (board == all && (best < 0 || Integer.bitCount(presses) < Integer.bitCount(best))) {
                best = presses;
            }
        }
        return best;
    }
}
