package com.apricotiris.IrisShot.shizuku;

import android.os.ParcelFileDescriptor;

interface IShizukuCommandService {
    ParcelFileDescriptor execute(in String[] arguments, long timeoutMs, int maxStdoutBytes) = 0;
    int performSwipe(
        int startX,
        int startY,
        int endX,
        int endY,
        long durationMs,
        long holdMs
    ) = 1;
    long reserveLinearSwipeSession() = 2;
    int performLinearSwipe(
        long sessionId,
        int startX,
        int startY,
        int endX,
        int endY,
        long durationMs
    ) = 3;
    boolean cancelSwipe(long sessionId) = 4;
    boolean awaitSwipeStarted(long sessionId, long timeoutMs) = 5;
    void releaseSwipeSession(long sessionId) = 6;
    int performReservedSwipe(
        long sessionId,
        int startX,
        int startY,
        int endX,
        int endY,
        long durationMs,
        long holdMs
    ) = 7;
    void destroy() = 16777114;
}
