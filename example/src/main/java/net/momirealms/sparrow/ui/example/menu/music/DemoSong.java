package net.momirealms.sparrow.ui.example.menu.music;

import org.jetbrains.annotations.NotNull;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * 将随示例打包的 Flower Dance 旧版 NBS 谱转成可编辑音轨, 在命令注册时读取一次.
 */
final class DemoSong {
    private DemoSong() {
    }

    @NotNull
    static NotePattern load() {
        try (DataInputStream input = new DataInputStream(DemoSong.class.getResourceAsStream("/music/flower-dance.nbs"))) {
            int lastStep = readShort(input);
            int layerCount = readShort(input);
            for (int i = 0; i < 4; i++) {
                skipString(input);
            }
            // 内置谱使用旧版 NBS, 每秒 6.25 格; 编辑器以每拍四格、93.75 BPM 播放.
            int tempo = readShort(input);
            if (tempo != 625) {
                throw new IOException("Unexpected demo tempo: " + tempo);
            }
            input.skipNBytes(23);
            skipString(input);
            int pages = (lastStep + NotePattern.STEPS) / NotePattern.STEPS;
            byte[][] notes = new byte[layerCount][pages * NotePattern.STEPS];
            boolean[] used = new boolean[layerCount];
            for (int layer = 0; layer < layerCount; layer++) {
                Arrays.fill(notes[layer], (byte) NotePattern.DEFAULT_NOTE);
            }
            int step = -1;
            int jump;
            while ((jump = readShort(input)) != 0) {
                step += jump;
                int layer = -1;
                while ((jump = readShort(input)) != 0) {
                    layer += jump;
                    int instrument = input.readUnsignedByte();
                    int pitch = input.readUnsignedByte() - 33;
                    if (instrument != 0 || pitch < 0 || pitch >= NotePattern.PITCHES) {
                        throw new IOException("Unexpected demo note at step " + step);
                    }
                    notes[layer][step] = (byte) (pitch << 1 | 1);
                    used[layer] = true;
                }
            }
            // 源文件末尾有一条空轨道; 仅显示实际参与演奏的五条竖琴声部.
            ArrayList<NotePattern.Track> tracks = new ArrayList<>();
            for (int layer = 0; layer < layerCount; layer++) {
                if (used[layer]) {
                    tracks.add(new NotePattern.Track(NoteInstrument.HARP, false, notes[layer]));
                }
            }
            return new NotePattern(tracks.toArray(NotePattern.Track[]::new), pages);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to load the bundled Flower Dance score", exception);
        }
    }

    private static int readShort(@NotNull DataInputStream input) throws IOException {
        return Short.toUnsignedInt(Short.reverseBytes(input.readShort()));
    }

    private static void skipString(@NotNull DataInputStream input) throws IOException {
        input.skipNBytes(Integer.reverseBytes(input.readInt()));
    }
}
