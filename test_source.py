from pathlib import Path


source = Path("src/com/midori/gboard/termux/MainHook.java").read_text(encoding="utf-8")
assert "hookLatinImeEngine" not in source
assert "[ENGLISH MODE]" not in source
assert "[CHINESE MODE]" in source
print("source checks passed")
