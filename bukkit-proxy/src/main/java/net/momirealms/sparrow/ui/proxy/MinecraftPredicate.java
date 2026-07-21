package net.momirealms.sparrow.ui.proxy;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析代理注解中的 Minecraft 版本和服务端补丁条件表达式.
 */
public final class MinecraftPredicate implements Predicate<String> {
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\s*(\\(|\\)|&&|\\|\\||!|[^\\s()&|!]+)\\s*");
    private final Context context;

    /**
     * 创建绑定到当前服务端版本和补丁集合的条件解释器.
     *
     * @param version 纯 Minecraft 版本字符串
     * @param patches 服务端补丁标记
     */
    public MinecraftPredicate(String version, List<String> patches) {
        this.context = new Context(MinecraftPredicate.parseVersionToInteger(version), patches);
    }

    @Override
    public boolean test(String expression) {
        if (expression == null || expression.isEmpty()) {
            return true;
        }
        return this.compile(expression).test(this.context);
    }

    private Condition compile(String expression) {
        Matcher matcher = MinecraftPredicate.TOKEN_PATTERN.matcher(expression);
        Deque<Condition> nodes = new ArrayDeque<>();
        Deque<String> ops = new ArrayDeque<>();
        while (matcher.find()) {
            String token = matcher.group(1);
            if (token.isEmpty()) {
                continue;
            }
            switch (token) {
                case "(", "!" -> ops.push(token);
                case ")" -> {
                    while (!ops.isEmpty() && !ops.peek().equals("(")) {
                        MinecraftPredicate.processOperator(nodes, ops.pop());
                    }
                    if (!ops.isEmpty()) {
                        ops.pop(); // 弹出 "("
                    }
                }
                case "&&", "||" -> {
                    while (!ops.isEmpty() && MinecraftPredicate.precedence(ops.peek()) >= MinecraftPredicate.precedence(token)) {
                        MinecraftPredicate.processOperator(nodes, ops.pop());
                    }
                    ops.push(token);
                }
                default -> nodes.push(MinecraftPredicate.compileLeaf(token));
            }
        }
        while (!ops.isEmpty()) {
            MinecraftPredicate.processOperator(nodes, ops.pop());
        }
        return nodes.isEmpty() ? ctx -> true : nodes.pop();
    }

    private static void processOperator(Deque<Condition> nodes, String op) {
        if ("!".equals(op)) {
            if (nodes.isEmpty()) {
                throw new IllegalArgumentException("Invalid syntax: '!' used without operand");
            }
            Condition node = nodes.pop();
            nodes.push(ctx -> !node.test(ctx));
        } else {
            if (nodes.size() < 2) {
                throw new IllegalArgumentException("Invalid syntax: missing operands for " + op);
            }
            Condition right = nodes.pop();
            Condition left = nodes.pop();
            if ("&&".equals(op)) {
                nodes.push(ctx -> left.test(ctx) && right.test(ctx));
            } else if ("||".equals(op)) {
                nodes.push(ctx -> left.test(ctx) || right.test(ctx));
            }
        }
    }

    private static int precedence(String op) {
        if ("!".equals(op)) {
            return 3;
        }
        if ("&&".equals(op)) {
            return 2;
        }
        if ("||".equals(op)) {
            return 1;
        }
        return 0;
    }

    private static Condition compileLeaf(String token) {
        String[] parts = token.split("=", 2);
        if (parts.length != 2) {
            return ctx -> false;
        }
        String type = parts[0].trim();
        String param = parts[1].trim();
        return switch (type) {
            case "min_version" -> new VersionCheck(param, true);
            case "max_version" -> new VersionCheck(param, false);
            case "version" -> new ExactVersionCheck(param);
            case "has_patch" -> new PatchCheck(param);
            default -> throw new IllegalArgumentException("Invalid predicate: " + token);
        };
    }

    /**
     * 将最多三段的 Minecraft 版本转换为可直接比较的整数.
     *
     * @param versionString Minecraft 版本字符串
     * @return 版本比较值
     */
    public static int parseVersionToInteger(String versionString) {
        int v1 = 0;
        int v2 = 0;
        int v3 = 0;
        int currentNumber = 0;
        int part = 0;
        for (int i = 0; i < versionString.length(); i++) {
            char c = versionString.charAt(i);
            if (c >= '0' && c <= '9') {
                currentNumber = currentNumber * 10 + (c - '0');
            } else if (c == '.') {
                if (part == 0) {
                    v1 = currentNumber;
                }
                if (part == 1) {
                    v2 = currentNumber;
                }
                part++;
                currentNumber = 0;
                if (part > 2) {
                    break;
                }
            }
        }
        if (part == 0) {
            v1 = currentNumber;
        } else if (part == 1) {
            v2 = currentNumber;
        } else if (part == 2) {
            v3 = currentNumber;
        }
        return v1 * 10000 + v2 * 100 + v3;
    }

    /**
     * 已编译条件的最小执行接口.
     */
    public interface Condition {
        boolean test(Context predicate);
    }

    /**
     * 条件求值所需的不可变服务端上下文.
     *
     * @param version 版本比较值
     * @param patches 服务端补丁标记
     */
    public record Context(int version, List<String> patches) {
        public Context {
            patches = List.copyOf(patches);
        }
    }

    private static class ExactVersionCheck implements Condition {
        private final int targetVersion;

        public ExactVersionCheck(String version) {
            this.targetVersion = MinecraftPredicate.parseVersionToInteger(version);
        }

        @Override
        public boolean test(Context predicate) {
            return predicate.version() == this.targetVersion;
        }
    }

    private static class VersionCheck implements Condition {
        private final int targetVersion;
        private final boolean minOrMax;

        public VersionCheck(String targetVersion, boolean minOrMax) {
            this.targetVersion = MinecraftPredicate.parseVersionToInteger(targetVersion);
            this.minOrMax = minOrMax;
        }

        @Override
        public boolean test(Context context) {
            if (this.minOrMax) {
                return context.version() >= this.targetVersion;
            } else {
                return context.version() <= this.targetVersion;
            }
        }
    }

    private record PatchCheck(String patch) implements Condition {

        @Override
        public boolean test(Context predicate) {
            return predicate.patches().contains(this.patch);
        }
    }
}
