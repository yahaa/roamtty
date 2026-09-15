/* Copyright 2026 iTermux contributors. Licensed under Apache-2.0. */
package com.termux.terminal;

/** Callbacks default to no logging: parser diagnostics may contain remote terminal text. */
public class SilentTerminalClient implements TerminalSessionClient {
    public void onTextChanged(TerminalSession s) {}
    public void onTitleChanged(TerminalSession s) {}
    public void onSessionFinished(TerminalSession s) {}
    public void onCopyTextToClipboard(TerminalSession s, String text) {}
    public void onPasteTextFromClipboard(TerminalSession s) {}
    public void onBell(TerminalSession s) {}
    public void onColorsChanged(TerminalSession s) {}
    public void onTerminalCursorStateChange(boolean state) {}
    public void setTerminalShellPid(TerminalSession s, int pid) {}
    public Integer getTerminalCursorStyle() { return 0; }
    public void logError(String tag, String message) {}
    public void logWarn(String tag, String message) {}
    public void logInfo(String tag, String message) {}
    public void logDebug(String tag, String message) {}
    public void logVerbose(String tag, String message) {}
    public void logStackTraceWithMessage(String tag, String message, Exception e) {}
    public void logStackTrace(String tag, Exception e) {}
}
