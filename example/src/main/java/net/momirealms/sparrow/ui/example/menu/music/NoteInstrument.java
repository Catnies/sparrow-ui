package net.momirealms.sparrow.ui.example.menu.music;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.jetbrains.annotations.NotNull;

enum NoteInstrument {
    HARP("竖琴", Sound.BLOCK_NOTE_BLOCK_HARP, Material.OAK_PLANKS),
    BASS("贝斯", Sound.BLOCK_NOTE_BLOCK_BASS, Material.SPRUCE_LOG),
    BASEDRUM("底鼓", Sound.BLOCK_NOTE_BLOCK_BASEDRUM, Material.STONE),
    SNARE("小鼓", Sound.BLOCK_NOTE_BLOCK_SNARE, Material.SAND),
    HAT("踩镲", Sound.BLOCK_NOTE_BLOCK_HAT, Material.GLASS),
    GUITAR("吉他", Sound.BLOCK_NOTE_BLOCK_GUITAR, Material.WHITE_WOOL),
    FLUTE("长笛", Sound.BLOCK_NOTE_BLOCK_FLUTE, Material.CLAY),
    BELL("钟琴", Sound.BLOCK_NOTE_BLOCK_BELL, Material.GOLD_BLOCK),
    CHIME("风铃", Sound.BLOCK_NOTE_BLOCK_CHIME, Material.PACKED_ICE),
    XYLOPHONE("木琴", Sound.BLOCK_NOTE_BLOCK_XYLOPHONE, Material.BONE_BLOCK),
    IRON_XYLOPHONE("铁木琴", Sound.BLOCK_NOTE_BLOCK_IRON_XYLOPHONE, Material.IRON_BLOCK),
    COW_BELL("牛铃", Sound.BLOCK_NOTE_BLOCK_COW_BELL, Material.SOUL_SAND),
    DIDGERIDOO("迪吉里杜管", Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, Material.PUMPKIN),
    BIT("芯片", Sound.BLOCK_NOTE_BLOCK_BIT, Material.EMERALD_BLOCK),
    BANJO("班卓琴", Sound.BLOCK_NOTE_BLOCK_BANJO, Material.HAY_BLOCK),
    PLING("电钢琴", Sound.BLOCK_NOTE_BLOCK_PLING, Material.GLOWSTONE);

    private static final NoteInstrument[] ALL = values();
    private static final String[] PITCH_NAMES = {"F♯", "G", "G♯", "A", "A♯", "B", "C", "C♯", "D", "D♯", "E", "F"};

    private final String label;
    private final Sound sound;
    private final Material icon;

    NoteInstrument(@NotNull String label, @NotNull Sound sound, @NotNull Material icon) {
        this.label = label;
        this.sound = sound;
        this.icon = icon;
    }

    @NotNull
    String label() {
        return this.label;
    }

    @NotNull
    Sound sound() {
        return this.sound;
    }

    @NotNull
    Material icon() {
        return this.icon;
    }

    @NotNull
    NoteInstrument advance(int direction) {
        return ALL[Math.floorMod(this.ordinal() + direction, ALL.length)];
    }

    static float pitch(int key) {
        return (float) Math.pow(2.0, (key - 12) / 12.0);
    }

    @NotNull
    static String pitchName(int key) {
        return PITCH_NAMES[key % 12] + " · 音高 " + key + "/24";
    }
}
