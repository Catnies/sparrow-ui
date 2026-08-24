package net.momirealms.sparrow.ui.internal.map;

import org.bukkit.map.MapPalette;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.IntStream;

public final class MapColorProfileGenerator {
    private static final String EXPECTED_CACHE_MD5 = "E88EDD068D12D39934B40E8B6B124C83";

    private MapColorProfileGenerator() {
    }

    public static void main(String[] arguments) throws IOException {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("Expected the map color profile output directory");
        }

        // 每个 RGB 分块可以独立穷举, 生成时并行计算所有候选集合.
        int[] colors = MapColorProfileGenerator.paperColors();
        byte[][] blockCandidates = new byte[MapColorProfile.BLOCK_COUNT][];
        IntStream.range(0, MapColorProfile.BLOCK_COUNT).parallel().forEach(block ->
                blockCandidates[block] = MapColorProfileGenerator.generateBlockCandidates(block, colors)
        );

        // 运行时 offset 使用无符号 short, 扁平候选表不能超过 65535 项.
        int candidateCount = 0;
        for (int block = 0; block < blockCandidates.length; block++) {
            candidateCount += blockCandidates[block].length;
        }
        if (candidateCount > Character.MAX_VALUE) {
            throw new IllegalStateException("Map color profile has too many candidates: " + candidateCount);
        }

        // 将每块候选压成 offsets + candidates, 运行时只需两次数组寻址.
        char[] offsets = new char[MapColorProfile.BLOCK_COUNT + 1];
        byte[] candidates = new byte[candidateCount];
        int offset = 0;
        for (int block = 0; block < blockCandidates.length; block++) {
            offsets[block] = (char) offset;
            byte[] current = blockCandidates[block];
            System.arraycopy(current, 0, candidates, offset, current.length);
            offset += current.length;
        }
        offsets[offsets.length - 1] = (char) offset;

        // 穷举全部 24 位 RGB 后固定输出摘要, 映射变化必须显式更新基准.
        MapColorProfile profile = new MapColorProfile(colors, offsets, candidates);
        String cacheMd5 = MapColorProfileGenerator.cacheMd5(profile);
        if (!EXPECTED_CACHE_MD5.equals(cacheMd5)) {
            throw new IllegalStateException("Generated map color cache MD5 " + cacheMd5 + ", expected " + EXPECTED_CACHE_MD5);
        }

        // 二进制布局与 MapColorProfile.read 的读取顺序保持一致.
        Path outputDirectory = Path.of(arguments[0]);
        Files.createDirectories(outputDirectory);
        Path output = outputDirectory.resolve("map-color-profile.bin");
        try (DataOutputStream data = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(output)))) {
            data.writeInt(MapColorProfile.MAGIC);
            data.writeByte(MapColorProfile.FORMAT_VERSION);
            data.writeByte(MapColorProfile.BLOCK_SHIFT);
            data.writeShort(MapColorProfile.COLOR_COUNT);
            data.writeInt(MapColorProfile.BLOCK_COUNT);
            data.writeInt(candidates.length);
            for (int index = 0; index < offsets.length; index++) {
                data.writeShort(offsets[index]);
            }
            data.write(candidates);
        }
        System.out.println("Generated " + output + " with " + candidates.length + " candidates");
    }

    @SuppressWarnings("removal")
    private static int[] paperColors() {
        int[] colors = new int[MapColorProfile.COLOR_COUNT];
        for (int id = 0; id < colors.length; id++) {
            colors[id] = id < 248 ? MapPalette.getColor((byte) id).getRGB() : 0;
        }
        return colors;
    }

    private static byte[] generateBlockCandidates(int block, int[] colors) {
        // 候选只保留在这个 8x8x8 分块内至少胜出一次的地图色.
        boolean[] used = new boolean[MapColorProfile.COLOR_COUNT];
        int redStart = (block >>> 10) << MapColorProfile.BLOCK_SHIFT;
        int greenStart = (block >>> 5 & 31) << MapColorProfile.BLOCK_SHIFT;
        int blueStart = (block & 31) << MapColorProfile.BLOCK_SHIFT;
        int blockSize = 1 << MapColorProfile.BLOCK_SHIFT;
        for (int red = redStart; red < redStart + blockSize; red++) {
            for (int green = greenStart; green < greenStart + blockSize; green++) {
                for (int blue = blueStart; blue < blueStart + blockSize; blue++) {
                    used[MapColorProfileGenerator.match(red, green, blue, colors)] = true;
                }
            }
        }

        int count = 0;
        for (int id = MapColorProfile.FIRST_COLOR; id < used.length; id++) {
            if (used[id]) {
                count++;
            }
        }
        byte[] candidates = new byte[count];
        int index = 0;
        for (int id = MapColorProfile.FIRST_COLOR; id < used.length; id++) {
            if (used[id]) {
                candidates[index++] = (byte) id;
            }
        }
        return candidates;
    }

    private static int match(int red, int green, int blue, int[] colors) {
        // 生成阶段全表扫描, 为运行时的分块候选表提供基准结果.
        int bestId = 0;
        int bestDistance = -1;
        for (int id = MapColorProfile.FIRST_COLOR; id < colors.length; id++) {
            int color = colors[id];
            if (color >>> 24 == 0) {
                continue;
            }
            int distance = MapColorProfile.distance(red, green, blue, color);
            if (bestDistance == -1 || distance < bestDistance) {
                bestDistance = distance;
                bestId = id;
            }
        }
        return bestId;
    }

    private static String cacheMd5(MapColorProfile profile) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] row = new byte[256];
            for (int red = 0; red < 256; red++) {
                for (int green = 0; green < 256; green++) {
                    for (int blue = 0; blue < 256; blue++) {
                        row[blue] = profile.match(red, green, blue);
                    }
                    digest.update(row);
                }
            }
            return HexFormat.of().withUpperCase().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("MD5 is unavailable", exception);
        }
    }
}
