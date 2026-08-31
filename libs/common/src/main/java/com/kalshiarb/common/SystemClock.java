package com.kalshiarb.common;

/**
 * The one place in the codebase permitted to read the real clock.
 *
 * <p>Package-private so it can only be reached through {@link Clock#system()}, which keeps
 * "who is allowed to know the real time" answerable by grepping for that one call.
 */
enum SystemClock implements Clock {
    INSTANCE;

    @Override
    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    @Override
    public long nanoTime() {
        return System.nanoTime();
    }
}
