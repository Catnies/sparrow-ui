package net.momirealms.sparrow.ui.network;

import java.util.Arrays;

record PacketEntry<H>(long sequence, H handler) {
    static <H> PacketEntry<H>[] append(PacketEntry<H>[] entries, PacketEntry<H> entry) {
        // 新注册项追加到末尾, 旧快照的数组继续供在途派发使用.
        PacketEntry<H>[] updated = Arrays.copyOf(entries, entries.length + 1);
        updated[entries.length] = entry;
        return updated;
    }

    static <H> PacketEntry<H>[] remove(PacketEntry<H>[] entries, long sequence) {
        // sequence 标识一次注册, 同一个 handler 注册多次也能分别注销.
        for (int index = 0; index < entries.length; index++) {
            if (entries[index].sequence == sequence) {
                PacketEntry<H>[] updated = Arrays.copyOf(entries, entries.length - 1);
                System.arraycopy(entries, index + 1, updated, index, entries.length - index - 1);
                return updated;
            }
        }
        return entries;
    }
}
