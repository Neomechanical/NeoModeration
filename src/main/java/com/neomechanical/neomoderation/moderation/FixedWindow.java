package com.neomechanical.neomoderation.moderation;

/**
 * Counts events in back-to-back windows of a fixed length, for simple rate caps:
 * a window starts at its first event and ends {@code length} milliseconds later.
 * Not thread-safe; callers hold their own lock.
 */
public final class FixedWindow {
    private final long length;
    private long start;
    private int count;

    public FixedWindow(long length) {
        this.length = length;
    }

    /** Counts one event at {@code now}; returns how many the window holds with it. */
    public int add(long now) {
        roll(now);
        return ++count;
    }

    /** How many events the window holds at {@code now}. */
    public int count(long now) {
        roll(now);
        return count;
    }

    /** Whether the window has nothing left to count at {@code now}. */
    public boolean ended(long now) {
        return count == 0 || now - start >= length;
    }

    private void roll(long now) {
        if (ended(now)) {
            start = now;
            count = 0;
        }
    }
}
