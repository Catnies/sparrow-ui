package net.momirealms.sparrow.ui.example.menu.customframes;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntPredicate;

/**
 * 一次贪吃蛇跑图的完整排演: 开播之前就把每一步该画什么算完, 动画只负责按 tick 翻页.
 *
 * <p>这样安排不是为了省事, 而是帧函数要求纯: 同一 tick 可能被求值零次或多次,
 * 帧函数不能一边被问一边推进蛇的位置, 所以走位必须先定下来.
 *
 * <p>路线分三段, 三段都靠同一个 BFS: 逐个奔向最近的食物, 吃完之后<strong>再搜一次最近的边框格</strong>,
 * 走到那一格之后才朝外离场. 三段都绕开自己的身子, 因此蛇不会从自己身上穿过去.
 */
final class SnakePlan {
    static final byte EMPTY = 0;
    static final byte HEAD = 1;
    static final byte BODY = 2;
    static final byte FOOD = 3;

    private static final int MAX_STEPS = 400; // 兜底: 万一规划出意外的长路径也不让示例卡住
    private static final int ATTEMPTS = 8;    // 排演没吃满时最多重排几次

    private final byte[][] steps;  // steps[第几步][槽位] = 上面四个常量之一
    private final int[] eatSteps;  // 吃到食物的那几步的下标, 音效按它排

    private SnakePlan(byte[][] steps, int[] eatSteps) {
        this.steps = steps;
        this.eatSteps = eatSteps;
    }

    /**
     * 排演一次跑图: 随机撒食物, 逐个吃掉, 再搜最近的边框格走过去并离场.
     *
     * @param width 舞台宽度
     * @param height 舞台高度
     * @param startLength 蛇的初始长度
     * @param foodCount 食物数量
     * @return 排演结果
     */
    @NotNull
    static SnakePlan roll(int width, int height, int startLength, int foodCount) {
        // 极少数局面里蛇会把最后一份食物压在自己身下又走不开; 排演很便宜, 不满意就重排一次
        SnakePlan plan = planOnce(width, height, startLength, foodCount);
        for (int attempt = 0; attempt < ATTEMPTS && plan.eatSteps.length < foodCount; attempt++) {
            plan = planOnce(width, height, startLength, foodCount);
        }
        return plan;
    }

    /**
     * 排演一次, 不保证一定吃满.
     *
     * @param width 舞台宽度
     * @param height 舞台高度
     * @param startLength 蛇的初始长度
     * @param foodCount 食物数量
     * @return 排演结果
     */
    @NotNull
    private static SnakePlan planOnce(int width, int height, int startLength, int foodCount) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int area = width * height;

        // 蛇横躺在随机一行的左端, 头朝右
        int row = random.nextInt(height);
        ArrayDeque<Integer> body = new ArrayDeque<>();
        for (int offset = startLength - 1; offset >= 0; offset--) {
            body.addLast(row * width + offset);
        }

        // 食物撒在蛇身以外的地方
        Set<Integer> foods = new LinkedHashSet<>();
        while (foods.size() < foodCount) {
            int slot = random.nextInt(area);
            if (!body.contains(slot)) {
                foods.add(slot);
            }
        }

        List<byte[]> steps = new ArrayList<>();
        List<Integer> eatSteps = new ArrayList<>(foodCount);
        steps.add(snapshot(area, body, foods));

        // 第一段: 一次盯一份够得着的食物, 绕开自己的身子走过去
        while (!foods.isEmpty() && steps.size() < MAX_STEPS) {
            List<Integer> path = pathToSomeFood(body, foods, width, height);
            if (path == null) {
                // 剩下的食物这会儿全被自己的身子压着: 先挪一步等身子让开, 再重新找
                if (!wander(body, foods, width, height)) {
                    break;
                }
                steps.add(snapshot(area, body, foods));
                continue;
            }
            for (int index = 0; index < path.size(); index++) {
                int next = path.get(index);
                boolean ate = foods.remove(next);
                body.addFirst(next);
                // 吃到食物才长一节, 否则尾巴跟着往前挪
                if (!ate) {
                    body.removeLast();
                }
                if (ate) {
                    eatSteps.add(steps.size());
                }
                steps.add(snapshot(area, body, foods));
            }
        }

        // 第二段: 吃完之后再搜一次最近的边框格, 走到那一格上
        List<Integer> toBorder = pathTo(body.peekFirst(), cell -> isBorder(cell, width, height), body, width, height);
        if (toBorder != null) {
            for (int index = 0; index < toBorder.size() && steps.size() < MAX_STEPS; index++) {
                body.addFirst(toBorder.get(index));
                body.removeLast();
                steps.add(snapshot(area, body, foods));
            }
        }

        // 第三段: 从脚下那一侧走出去, 头出界之后身子逐节跟出去
        int head = body.peekFirst();
        int headX = head % width;
        int headY = head / width;
        int[] exit = isBorder(head, width, height)
                ? borderExit(headX, headY, width, height)
                : nearestEdgeDirection(headX, headY, width, height);
        while (!body.isEmpty() && steps.size() < MAX_STEPS) {
            headX += exit[0];
            headY += exit[1];
            if (headX >= 0 && headX < width && headY >= 0 && headY < height) {
                body.addFirst(headY * width + headX);
            }
            body.removeLast();
            steps.add(snapshot(area, body, foods));
        }

        int[] eaten = new int[eatSteps.size()];
        for (int index = 0; index < eaten.length; index++) {
            eaten[index] = eatSteps.get(index);
        }
        return new SnakePlan(steps.toArray(new byte[0][]), eaten);
    }

    /**
     * 这次排演一共几步.
     *
     * @return 步数
     */
    int stepCount() {
        return this.steps.length;
    }

    /**
     * 吃到食物的那几步的下标, 按先后排列.
     *
     * @return 步号数组
     */
    int @NotNull [] eatSteps() {
        return this.eatSteps.clone();
    }

    /**
     * 查某一步某个槽位该画什么.
     *
     * @param step 第几步, 越界时按最后一步算
     * @param slot 舞台槽位
     * @return {@link #EMPTY} / {@link #HEAD} / {@link #BODY} / {@link #FOOD} 之一
     */
    byte cellAt(int step, int slot) {
        return this.steps[Math.min(step, this.steps.length - 1)][slot];
    }

    /**
     * 给当前局面拍一张图. 蛇头最后画, 万一走位与自己重叠也仍然看得见头.
     *
     * @param area 舞台格子总数
     * @param body 蛇身, 头在最前
     * @param foods 还没吃掉的食物
     * @return 这一步每个槽位该画什么
     */
    @NotNull
    private static byte[] snapshot(int area, @NotNull ArrayDeque<Integer> body, @NotNull Set<Integer> foods) {
        byte[] frame = new byte[area];
        Arrays.fill(frame, EMPTY);
        for (Integer food : foods) {
            frame[food] = FOOD;
        }
        for (Integer cell : body) {
            frame[cell] = BODY;
        }
        Integer head = body.peekFirst();
        if (head != null) {
            frame[head] = HEAD;
        }
        return frame;
    }

    /**
     * 挑一份够得着的食物并给出走过去的路.
     *
     * <p>近的先试. 蛇身有可能正好压在某份食物上, 那一份这会儿走不到, 先去吃别的,
     * 等身子挪开再回来 —— 直接放弃的话就会漏吃.
     *
     * @param body 当前蛇身
     * @param foods 还没吃掉的食物
     * @param width 舞台宽度
     * @param height 舞台高度
     * @return 不含起点的路径, 一份都够不着时为 {@code null}
     */
    @Nullable
    private static List<Integer> pathToSomeFood(@NotNull ArrayDeque<Integer> body, @NotNull Set<Integer> foods, int width, int height) {
        int head = body.peekFirst();
        List<Integer> candidates = new ArrayList<>(foods);
        candidates.sort(Comparator.comparingInt(food -> manhattan(head, food, width)));
        for (int index = 0; index < candidates.size(); index++) {
            int target = candidates.get(index);
            List<Integer> path = pathTo(head, cell -> cell == target, body, width, height);
            if (path != null) {
                return path;
            }
        }
        return null;
    }

    /**
     * 一份食物都够不着时先挪一步, 把压在食物上的身子让开.
     *
     * <p>挪的方向不能固定, 否则在角落里会来回蹭着走不动; 挑一个"走完离目标更近"的邻格,
     * 既能让尾巴腾出位置, 又保证是在往目标靠.
     *
     * @param body 当前蛇身
     * @param foods 还没吃掉的食物
     * @param width 舞台宽度
     * @param height 舞台高度
     * @return 挪动成功时为 true
     */
    private static boolean wander(@NotNull ArrayDeque<Integer> body, @NotNull Set<Integer> foods, int width, int height) {
        int head = body.peekFirst();
        int tail = body.peekLast();
        int target = -1;
        int targetDistance = Integer.MAX_VALUE;
        for (Integer food : foods) {
            int distance = manhattan(head, food, width);
            if (distance < targetDistance) {
                targetDistance = distance;
                target = food;
            }
        }

        int x = head % width;
        int y = head / width;
        int best = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int direction = 0; direction < 4; direction++) {
            int nextX = x + (direction == 0 ? 1 : direction == 1 ? -1 : 0);
            int nextY = y + (direction == 2 ? 1 : direction == 3 ? -1 : 0);
            if (nextX < 0 || nextX >= width || nextY < 0 || nextY >= height) {
                continue;
            }
            int next = nextY * width + nextX;
            // 尾巴这一步就会挪走, 因此那一格算空的
            if (body.contains(next) && next != tail) {
                continue;
            }
            int distance = manhattan(next, target, width);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = next;
            }
        }
        if (best < 0) {
            return false;
        }
        body.addFirst(best);
        body.removeLast();
        return true;
    }

    /**
     * 两格之间的曼哈顿距离.
     *
     * @param from 起点槽位
     * @param to 终点槽位
     * @param width 舞台宽度
     * @return 距离
     */
    private static int manhattan(int from, int to, int width) {
        return Math.abs(to % width - from % width) + Math.abs(to / width - from / width);
    }

    /**
     * 从蛇头走到最近的一个满足条件的格子, 绕开当前蛇身.
     *
     * <p>广度优先因此第一个撞上的目标就是最近的那个: 找食物时条件是"就是这一格",
     * 找出口时条件是"是边框格".
     *
     * @param from 蛇头所在槽位
     * @param goal 目标条件
     * @param body 当前蛇身
     * @param width 舞台宽度
     * @param height 舞台高度
     * @return 不含起点的路径, 起点本身就满足时为空列表, 走不通时为 {@code null}
     */
    @Nullable
    private static List<Integer> pathTo(int from, @NotNull IntPredicate goal, @NotNull ArrayDeque<Integer> body, int width, int height) {
        int area = width * height;
        int[] cameFrom = new int[area];
        Arrays.fill(cameFrom, -1);
        boolean[] blocked = new boolean[area];
        for (Integer cell : body) {
            blocked[cell] = true;
        }
        blocked[from] = false;

        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.addLast(from);
        cameFrom[from] = from;
        while (!queue.isEmpty()) {
            int current = queue.pollFirst();
            if (goal.test(current)) {
                return rebuild(cameFrom, from, current);
            }
            int x = current % width;
            int y = current / width;
            for (int direction = 0; direction < 4; direction++) {
                int nextX = x + (direction == 0 ? 1 : direction == 1 ? -1 : 0);
                int nextY = y + (direction == 2 ? 1 : direction == 3 ? -1 : 0);
                if (nextX < 0 || nextX >= width || nextY < 0 || nextY >= height) {
                    continue;
                }
                int next = nextY * width + nextX;
                if (blocked[next] || cameFrom[next] >= 0) {
                    continue;
                }
                cameFrom[next] = current;
                queue.addLast(next);
            }
        }
        return null;
    }

    /**
     * 沿来路回溯出路径.
     *
     * @param cameFrom 每个槽位的来路
     * @param from 起点
     * @param to 终点
     * @return 不含起点的路径
     */
    @NotNull
    private static List<Integer> rebuild(int @NotNull [] cameFrom, int from, int to) {
        List<Integer> reversed = new ArrayList<>();
        for (int cell = to; cell != from; cell = cameFrom[cell]) {
            reversed.add(cell);
        }
        List<Integer> path = new ArrayList<>(reversed.size());
        for (int index = reversed.size() - 1; index >= 0; index--) {
            path.add(reversed.get(index));
        }
        return path;
    }

    /**
     * 判断一个槽位是否贴着舞台边框.
     *
     * @param slot 槽位
     * @param width 舞台宽度
     * @param height 舞台高度
     * @return 贴边时为 true
     */
    private static boolean isBorder(int slot, int width, int height) {
        int x = slot % width;
        int y = slot / width;
        return x == 0 || x == width - 1 || y == 0 || y == height - 1;
    }

    /**
     * 站在边框格上时朝外走的方向.
     *
     * @param x 蛇头 x 坐标
     * @param y 蛇头 y 坐标
     * @param width 舞台宽度
     * @param height 舞台高度
     * @return 单位方向 {dx, dy}
     */
    private static int @NotNull [] borderExit(int x, int y, int width, int height) {
        if (x == 0) {
            return new int[]{-1, 0};
        }
        if (x == width - 1) {
            return new int[]{1, 0};
        }
        if (y == 0) {
            return new int[]{0, -1};
        }
        return new int[]{0, 1};
    }

    /**
     * 没能走到边框格时的退路: 朝距离最近的那条边直着走出去.
     *
     * @param x 蛇头 x 坐标
     * @param y 蛇头 y 坐标
     * @param width 舞台宽度
     * @param height 舞台高度
     * @return 单位方向 {dx, dy}
     */
    private static int @NotNull [] nearestEdgeDirection(int x, int y, int width, int height) {
        int left = x;
        int right = width - 1 - x;
        int up = y;
        int down = height - 1 - y;
        int best = Math.min(Math.min(left, right), Math.min(up, down));
        if (best == left) {
            return new int[]{-1, 0};
        }
        if (best == right) {
            return new int[]{1, 0};
        }
        if (best == up) {
            return new int[]{0, -1};
        }
        return new int[]{0, 1};
    }
}
