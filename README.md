# Gboard Termux IME

用于 Termux 的 LibXposed/LSPosed Gboard 兼容模块。

进入 Termux 时模块可切换到英文键盘，离开后恢复此前的输入语言；在 Termux 内切换到中文时，它会把 `EditorInfo` 恢复为标准文本输入，使 Gboard 拼音候选和中文上屏正常工作。

## 1.3.1 修复

1.3.0 会在英文模式把 Termux 伪装成 `com.android.virtualization.terminal`，并拦截所有形如 `LatinIme(EditorInfo) -> boolean` 的 Gboard 内部方法。这依赖 Gboard 私有实现，在部分 ROM/Gboard 组合中会延迟最后一个字符，例如输入 `cd ..` 后只提交 `cd .`，剩余的 `.` 在下一次提交时出现。

1.3.1 删除了这两个英文干预点：

- 英文输入完全保留 Gboard 和 Termux 原生的 `EditorInfo` 行为。
- 中文输入仍使用标准文本输入类型，保留候选词和中文上屏支持。
- 进入 Termux 自动切英文、离开恢复原语言的状态机保持不变。

## 安装

从 [Releases](../../releases) 下载 APK。模块默认作用域只有 Gboard：

```text
com.google.android.inputmethod.latin
```

在 LSPosed 中启用模块并勾选 Gboard，然后强制停止一次 Gboard 或重启设备。

## 构建

需要 JDK 8+、Android SDK Platform 34 和 Android Build Tools：

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
./build.sh
```

未设置 `KEYSTORE` 时只生成未签名 APK。签名私钥不在仓库中。

源码回归检查：

```sh
python3 test_source.py
```

## 来源

仓库由提供的 1.3.0 完整工程整理而成。包名为 `com.midori.gboard.termux`，使用 LibXposed API 102。原工程未附许可证，因此本仓库不额外声明授权。
