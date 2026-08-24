package net.momirealms.sparrow.ui.internal.map;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

final class MapColorProfile {
    static final int MAGIC = 0x53504D43; // ASCII SPMC
    static final int FORMAT_VERSION = 1; // 二进制 profile 格式
    static final int COLOR_COUNT = 256;
    static final int FIRST_COLOR = 4;     // 地图色 0..3 留给透明像素
    static final int BLOCK_SHIFT = 3;     // 每个 RGB 分块覆盖 8x8x8 种颜色
    static final int BLOCKS_PER_AXIS = 1 << (8 - BLOCK_SHIFT);
    static final int BLOCK_COUNT = BLOCKS_PER_AXIS * BLOCKS_PER_AXIS * BLOCKS_PER_AXIS;

    private static final String PROFILE_RESOURCE = "/map-color-profile.bin";

    private final int[] colors;       // 当前服务端的 256 个地图色
    private final char[] offsets;     // 第 n 块候选范围为 [offsets[n], offsets[n + 1])
    private final byte[] candidates;  // 各分块可能成为最近色的地图色 ID

    MapColorProfile(int[] colors, char[] offsets, byte[] candidates) {
        this.colors = colors.clone();
        this.offsets = offsets.clone();
        this.candidates = candidates.clone();
        this.validate();
    }

    static MapColorProfile load(int[] colors) {
        if (colors.length != COLOR_COUNT) {
            throw new IllegalArgumentException("map color profile requires " + COLOR_COUNT + " colors, got " + colors.length);
        }

        // 候选关系在构建期生成, 运行时只替换实际调色板颜色.
        try (InputStream resource = MapColorProfile.class.getResourceAsStream(PROFILE_RESOURCE)) {
            if (resource == null) {
                throw new IllegalStateException("Missing map color profile " + PROFILE_RESOURCE);
            }
            return read(colors, new DataInputStream(new BufferedInputStream(resource)));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read map color profile " + PROFILE_RESOURCE, exception);
        }
    }

    byte[] imageToBytes(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        byte[] result = new byte[pixels.length];
        for (int index = 0; index < pixels.length; index++) {
            int argb = pixels[index];
            // 半透明以下保留默认 ID 0, 其余像素匹配最近地图色.
            if ((argb >>> 24) >= 128) {
                result[index] = this.match(argb >>> 16 & 0xFF, argb >>> 8 & 0xFF, argb & 0xFF);
            }
        }
        return result;
    }

    byte match(int red, int green, int blue) {
        // RGB 各取高 5 位组成 15 位分块索引.
        int block = (red >>> BLOCK_SHIFT) << 10 | (green >>> BLOCK_SHIFT) << 5 | blue >>> BLOCK_SHIFT;
        int start = this.offsets[block];
        int end = this.offsets[block + 1];
        int bestId = this.candidates[start] & 0xFF;
        if (start + 1 == end) {
            return (byte) bestId;
        }

        int bestDistance = distance(red, green, blue, this.colors[bestId]);
        for (int index = start + 1; index < end; index++) {
            int candidateId = this.candidates[index] & 0xFF;
            int candidateDistance = distance(red, green, blue, this.colors[candidateId]);
            if (candidateDistance < bestDistance) {
                bestDistance = candidateDistance;
                bestId = candidateId;
                if (candidateDistance == 0) {
                    break;
                }
            }
        }
        return (byte) bestId;
    }

    static int distance(int red, int green, int blue, int argb) {
        // 使用红色中点加权的 RGB 距离.
        int candidateRed = argb >>> 16 & 0xFF;
        int candidateGreen = argb >>> 8 & 0xFF;
        int candidateBlue = argb & 0xFF;
        int redSum = red + candidateRed;
        int redDelta = red - candidateRed;
        int greenDelta = green - candidateGreen;
        int blueDelta = blue - candidateBlue;
        return (1024 + redSum) * redDelta * redDelta
                + 2048 * greenDelta * greenDelta
                + (1534 - redSum) * blueDelta * blueDelta;
    }

    private static MapColorProfile read(int[] colors, DataInputStream input) throws IOException {
        // 固定头部使资源版本, 分块方式和调色板尺寸无法被误读.
        if (input.readInt() != MAGIC) {
            throw new IllegalStateException("Invalid map color profile magic");
        }
        int formatVersion = input.readUnsignedByte();
        if (formatVersion != FORMAT_VERSION) {
            throw new IllegalStateException("Unsupported map color profile format " + formatVersion);
        }
        int blockShift = input.readUnsignedByte();
        if (blockShift != BLOCK_SHIFT) {
            throw new IllegalStateException("Unsupported map color profile block shift " + blockShift);
        }
        int colorCount = input.readUnsignedShort();
        if (colorCount != COLOR_COUNT) {
            throw new IllegalStateException("Map color profile contains " + colorCount + " colors, expected " + COLOR_COUNT);
        }
        int blockCount = input.readInt();
        if (blockCount != BLOCK_COUNT) {
            throw new IllegalStateException("Map color profile contains " + blockCount + " blocks, expected " + BLOCK_COUNT);
        }
        int candidateCount = input.readInt();
        if (candidateCount <= 0 || candidateCount > Character.MAX_VALUE) {
            throw new IllegalStateException("Invalid map color candidate count " + candidateCount);
        }

        // offset 使用无符号 16 位值, 最后一项同时给出候选总数.
        char[] offsets = new char[BLOCK_COUNT + 1];
        for (int index = 0; index < offsets.length; index++) {
            offsets[index] = (char) input.readUnsignedShort();
        }
        byte[] candidates = new byte[candidateCount];
        input.readFully(candidates);
        if (input.read() != -1) {
            throw new IllegalStateException("Map color profile contains trailing data");
        }
        return new MapColorProfile(colors, offsets, candidates);
    }

    private void validate() {
        if (this.colors.length != COLOR_COUNT || this.offsets.length != BLOCK_COUNT + 1) {
            throw new IllegalArgumentException("Invalid map color profile dimensions");
        }
        if (this.offsets[0] != 0 || this.offsets[this.offsets.length - 1] != this.candidates.length) {
            throw new IllegalArgumentException("Invalid map color profile offsets");
        }
        for (int block = 0; block < BLOCK_COUNT; block++) {
            int start = this.offsets[block];
            int end = this.offsets[block + 1];
            if (start >= end) {
                throw new IllegalArgumentException("Map color profile block " + block + " has no candidates");
            }
            int previousId = -1;
            // 每块至少一个候选, ID 严格递增且必须指向可见颜色.
            for (int index = start; index < end; index++) {
                int candidateId = this.candidates[index] & 0xFF;
                if (candidateId < FIRST_COLOR || candidateId <= previousId || this.colors[candidateId] >>> 24 == 0) {
                    throw new IllegalArgumentException("Invalid map color candidate " + candidateId + " in block " + block);
                }
                previousId = candidateId;
            }
        }
    }
}
