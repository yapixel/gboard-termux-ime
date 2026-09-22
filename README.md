# Gboard Termux IME

用于 Termux 的 LibXposed/LSPosed Gboard 兼容模块。

进入 Termux 时模块可切换到英文键盘，离开后恢复此前的输入语言；在 Termux 内切换到中文时，它会把 `EditorInfo` 恢复为标准文本输入，使 Gboard 拼音候选和中文上屏正常工作。

## 1.3.4 修复

1.3.0 会在英文模式把 Termux 伪装成 `com.android.virtualization.terminal`，并拦截所有形如 `LatinIme(EditorInfo) -> boolean` 的 Gboard 内部方法。这依赖 Gboard 私有实现，在部分 ROM/Gboard 组合中会延迟最后一个字符，例如输入 `cd ..` 后只提交 `cd .`，剩余的 `.` 在下一次提交时出现。

1.3.4 删除了这两个英文干预点，并修复了中文输入引擎未真正切换的问题：

- 保留真实的 Termux 包名，不再触发 Gboard 的虚拟终端私有路径。
- 英文完整恢复 Termux 原始 `EditorInfo` 和原始 `InputConnection`，所有命令均走终端的逐字符输入路径；`cd ..`、`cd ../../../` 只是回归样例，并非命令特判。
- 切换中文时临时使用标准文本 `EditorInfo` 并重启当前输入会话，使 Gboard 离开 `PasswordIme`。
- 中文输入可使用 composing text、拼音候选和中文上屏。
- 进入 Termux 自动切英文、离开恢复原语言的状态机保持不变。

Termux 的 `~/.termux/termux.properties` 应设置：

```properties
enforce-char-based-input = false
```

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
