package net.momirealms.sparrow.ui.state.internal;

import net.momirealms.sparrow.ui.state.Signal;

public final class SignalTestAccess {
    private SignalTestAccess() {
    }

    public static int entryCount(Signal<?> signal) {
        return AbstractSignal.require(signal).entryCount();
    }
}
