package com.neomechanical.neomoderation.moderation;

/** What a chat listener should do with the event after moderation. */
public record ChatDecision(Type type, String message) {
    public enum Type {
        ALLOW,
        BLOCK,
        /** Send {@code message}, with what a rule matched starred out. */
        CENSOR,
        /**
         * Send {@code message}, a gentler form of what was written, such as shouting
         * in lower case. Nothing was wrong with it, so a listener that would lose
         * formatting another plugin added sends the original instead.
         */
        SOFTEN
    }

    public static ChatDecision allow() {
        return new ChatDecision(Type.ALLOW, null);
    }

    public static ChatDecision block() {
        return new ChatDecision(Type.BLOCK, null);
    }

    public static ChatDecision censor(String censoredMessage) {
        return new ChatDecision(Type.CENSOR, censoredMessage);
    }

    public static ChatDecision soften(String gentlerMessage) {
        return new ChatDecision(Type.SOFTEN, gentlerMessage);
    }
}
