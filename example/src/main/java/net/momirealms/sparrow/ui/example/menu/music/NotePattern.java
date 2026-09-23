package net.momirealms.sparrow.ui.example.menu.music;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * 按十六分音符编排的多轨乐谱. 修改音符时只复制所在轨道, 旧乐谱仍可供渲染读取.
 */
final class NotePattern {
    static final int STEPS = 8;
    static final int PITCHES = 25;
    static final int DEFAULT_NOTE = 12 << 1;

    private final Track[] tracks;
    private final int pages;

    NotePattern(@NotNull Track[] tracks, int pages) {
        this.tracks = tracks;
        this.pages = pages;
    }

    @NotNull
    static NotePattern empty() {
        return new NotePattern(new Track[0], 1).addTrack(NoteInstrument.HARP);
    }

    int pages() {
        return this.pages;
    }

    int steps() {
        return this.pages * STEPS;
    }

    int tracks() {
        return this.tracks.length;
    }

    @NotNull
    Track track(int index) {
        return this.tracks[index];
    }

    @NotNull
    NotePattern toggle(int track, int step) {
        return this.editNote(track, step, this.tracks[track].note(step) ^ 1);
    }

    @NotNull
    NotePattern shiftPitch(int track, int step, int direction) {
        int pitch = Math.floorMod((this.tracks[track].note(step) >>> 1) + direction, PITCHES);
        return this.editNote(track, step, pitch << 1 | 1);
    }

    @NotNull
    private NotePattern editNote(int track, int step, int value) {
        Track current = this.tracks[track];
        byte[] notes = current.notes.clone();
        notes[step] = (byte) value;
        return this.replaceTrack(track, new Track(current.instrument, current.muted, notes));
    }

    @NotNull
    NotePattern instrument(int track, @NotNull NoteInstrument instrument) {
        Track current = this.tracks[track];
        return this.replaceTrack(track, new Track(instrument, current.muted, current.notes));
    }

    @NotNull
    NotePattern mute(int track) {
        Track current = this.tracks[track];
        return this.replaceTrack(track, new Track(current.instrument, !current.muted, current.notes));
    }

    @NotNull
    private NotePattern replaceTrack(int track, @NotNull Track replacement) {
        Track[] next = this.tracks.clone();
        next[track] = replacement;
        return new NotePattern(next, this.pages);
    }

    @NotNull
    NotePattern addTrack(@NotNull NoteInstrument instrument) {
        Track[] next = Arrays.copyOf(this.tracks, this.tracks.length + 1);
        byte[] notes = new byte[this.steps()];
        Arrays.fill(notes, (byte) DEFAULT_NOTE);
        next[this.tracks.length] = new Track(instrument, false, notes);
        return new NotePattern(next, this.pages);
    }

    @NotNull
    NotePattern removeTrack(int track) {
        Track[] next = new Track[this.tracks.length - 1];
        System.arraycopy(this.tracks, 0, next, 0, track);
        System.arraycopy(this.tracks, track + 1, next, track, next.length - track);
        return new NotePattern(next, this.pages);
    }

    // 新页追加到曲尾; 复制时保留所有轨道在来源页上的音符.
    @NotNull
    NotePattern appendPage(int copyFrom) {
        Track[] next = new Track[this.tracks.length];
        int length = this.steps();
        for (int i = 0; i < next.length; i++) {
            Track current = this.tracks[i];
            byte[] notes = Arrays.copyOf(current.notes, length + STEPS);
            if (copyFrom >= 0) {
                System.arraycopy(current.notes, copyFrom * STEPS, notes, length, STEPS);
            } else {
                Arrays.fill(notes, length, notes.length, (byte) DEFAULT_NOTE);
            }
            next[i] = new Track(current.instrument, current.muted, notes);
        }
        return new NotePattern(next, this.pages + 1);
    }

    @NotNull
    NotePattern clearPage(int page) {
        Track[] next = new Track[this.tracks.length];
        for (int i = 0; i < next.length; i++) {
            Track current = this.tracks[i];
            byte[] notes = current.notes.clone();
            Arrays.fill(notes, page * STEPS, (page + 1) * STEPS, (byte) DEFAULT_NOTE);
            next[i] = new Track(current.instrument, current.muted, notes);
        }
        return new NotePattern(next, this.pages);
    }

    @NotNull
    NotePattern removePage(int page) {
        if (this.pages == 1) return this.clearPage(0);
        Track[] next = new Track[this.tracks.length];
        int start = page * STEPS;
        for (int i = 0; i < next.length; i++) {
            Track current = this.tracks[i];
            byte[] notes = new byte[this.steps() - STEPS];
            System.arraycopy(current.notes, 0, notes, 0, start);
            System.arraycopy(current.notes, start + STEPS, notes, start, notes.length - start);
            next[i] = new Track(current.instrument, current.muted, notes);
        }
        return new NotePattern(next, this.pages - 1);
    }

    static final class Track {
        private final NoteInstrument instrument;
        private final boolean muted;
        private final byte[] notes;

        Track(@NotNull NoteInstrument instrument, boolean muted, byte @NotNull [] notes) {
            this.instrument = instrument;
            this.muted = muted;
            this.notes = notes;
        }

        @NotNull
        NoteInstrument instrument() {
            return this.instrument;
        }

        boolean muted() {
            return this.muted;
        }

        // 最低位表示开关, 高位保存音高; 关闭后仍记住原来的音高.
        int note(int step) {
            return this.notes[step];
        }
    }
}
