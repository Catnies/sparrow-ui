package net.momirealms.sparrow.ui.internal.map;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

final class MapColorProfile {
    static final int MAGIC = 0x53504D43;
    static final int FORMAT_VERSION = 1;
    static final int COLOR_COUNT = 256;
    static final int FIRST_COLOR = 4;
    static final int BLOCK_SHIFT = 3;
    static final int BLOCKS_PER_AXIS = 1 << (8 - BLOCK_SHIFT);
    static final int BLOCK_COUNT = BLOCKS_PER_AXIS * BLOCKS_PER_AXIS * BLOCKS_PER_AXIS;

    private static final String PROFILE_RESOURCE = "/map-color-profile.bin";

    private final int[] colors;
    private final char[] offsets;
    private final byte[] candidates;

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
            if ((argb >>> 24) >= 128) {
                result[index] = this.match(argb >>> 16 & 0xFF, argb >>> 8 & 0xFF, argb & 0xFF);
            }
        }
        return result;
    }

    byte match(int red, int green, int blue) {
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
