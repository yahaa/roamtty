package com.termux.view;

import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.termux.terminal.TerminalSession;

/**
 * The interface for communication between {@link TerminalView} and its client. It allows for getting
 * various  configuration options from the client and for sending back data to the client like logs,
 * key events, both hardware and IME (which makes it different from that available with
 * {@link View#setOnKeyListener(View.OnKeyListener)}, etc. It must be set for the
 * {@link TerminalView} through {@link TerminalView#setTerminalViewClient(TerminalViewClient)}.
 */
public interface TerminalViewClient {

    /**
     * Callback function on scale events according to {@link ScaleGestureDetector#getScaleFactor()}.
     */
    float onScale(float scale);



    /**
     * On a single tap on the terminal if terminal mouse reporting not enabled.
     */
    void onSingleTapUp(MotionEvent e);

    /** Explicit double-tap action on a writable terminal, fired only once no third tap follows. Read-only history never calls this. */
    default void onDoubleTap(MotionEvent e) {}

    /** Explicit triple-tap action on a writable terminal. Read-only history never calls this. */
    default void onTripleTap(MotionEvent e) {}

    /** A mostly horizontal swipe to the left on a writable terminal. Read-only history never calls this. */
    default void onSwipeLeft(MotionEvent e) {}

    /** A mostly horizontal swipe to the right on a writable terminal. Read-only history never calls this. */
    default void onSwipeRight(MotionEvent e) {}

    /** A left swipe whose finger rests before lifting, on a writable terminal; lifting then sends nothing more. */
    default void onSwipeHoldLeft() {}

    /** A right swipe whose finger rests before lifting, on a writable terminal; lifting then sends nothing more. */
    default void onSwipeHoldRight() {}

    boolean shouldBackButtonBeMappedToEscape();

    boolean shouldEnforceCharBasedInput();

    boolean shouldUseCtrlSpaceWorkaround();

    boolean isTerminalViewSelected();



    void copyModeChanged(boolean copyMode);



    boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session);

    boolean onKeyUp(int keyCode, KeyEvent e);

    /** Remote input was cleared without key events; consume any one-shot modifiers. */
    default void onInputCleared() {}

    boolean onLongPress(MotionEvent event);



    boolean readControlKey();

    boolean readAltKey();

    boolean readShiftKey();

    boolean readFnKey();



    boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session);


    void onEmulatorSet();


    void logError(String tag, String message);

    void logWarn(String tag, String message);

    void logInfo(String tag, String message);

    void logDebug(String tag, String message);

    void logVerbose(String tag, String message);

    void logStackTraceWithMessage(String tag, String message, Exception e);

    void logStackTrace(String tag, Exception e);

}
