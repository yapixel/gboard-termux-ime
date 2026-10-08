from pathlib import Path


source = Path("src/com/midori/gboard/termux/MainHook.java").read_text(encoding="utf-8")
assert "hookLatinImeEngine" not in source
assert "[ENGLISH MODE]" not in source
assert "info.packageName = VIRTUAL_TERMINAL_PKG" not in source
assert "[CHINESE MODE]" in source
assert "info.inputType = mOriginalInputType" in source
assert "info.imeOptions = mOriginalImeOptions" in source
assert "restartCurrentInput(service)" in source
assert "!isTargetTerminalApp(pkg) && !mInTerminalSession" not in source
assert "isTargetTerminalApp(info.packageName) || mInTerminalSession" not in source
assert "SafeBacktickInputConnection" in source
assert "KEYCODE_GRAVE" in source
assert 'super.commitText("`", 1)' in source
assert "super.commitText(text, newCursorPosition)" in source
assert "sLastBacktickTime" not in source
assert "KEYCODE_DPAD_LEFT" not in source
assert "isAllBackticks" not in source
assert "MODIFIER_META_MASK) == 0" in source
assert "isTargetTerminalApp(info.packageName)" in source
assert "Log.isLoggable(TAG, Log.DEBUG)" in source
assert 'Log.i(TAG, "IC.' not in source
print("source checks passed")
