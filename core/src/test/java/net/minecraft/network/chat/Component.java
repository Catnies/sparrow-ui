package net.minecraft.network.chat;

public interface Component {

    static Component empty() {
        return new Literal("");
    }

    static Component literal(String text) {
        return new Literal(text);
    }

    record Literal(String text) implements Component {
    }
}
