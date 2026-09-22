package com.midori.gboard.termux;

import android.content.Context;
import android.inputmethodservice.InputMethodService;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainHook extends XposedModule {
    private static final String TAG = "GboardTermuxIME";
    private static final String GBOARD_PKG = "com.google.android.inputmethod.latin";

    // 官方 Linux 虚拟终端包名（命中 Gboard 的 apps_to_respect_type_text_flag_no_suggestions 白名单）
    private static final String VIRTUAL_TERMINAL_PKG = "com.android.virtualization.terminal";

    private final Set<Method> mHookedMethods = new HashSet<>();
    private volatile InputMethodSubtype mLastSubtype = null;
    private volatile InputMethodSubtype mEnglishSubtype = null;
    private volatile InputMethodSubtype mLastNonTerminalSubtype = null;
    private volatile boolean mInTerminalSession = false;
    private volatile boolean mTerminalExited = false;
    private volatile boolean mIsProgrammaticSwitch = false;
    private volatile String mOriginalTerminalPkg = "com.termux";
    private volatile int mOriginalInputType = InputType.TYPE_NULL;
    private volatile int mOriginalImeOptions = 0;
    private volatile String mLastPackage = null;
    private volatile String mImeId = null;

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if (!GBOARD_PKG.equals(pkg)) {
            return;
        }

        Log.i(TAG, "Gboard package loaded: " + pkg + ", initializing per-app language hooks for Termux...");

        ClassLoader cl = param.getDefaultClassLoader();
        try {
            Class<?> latinImeClass = cl.loadClass("com.android.inputmethod.latin.LatinIME");
            hookInputMethodServiceHierarchy(latinImeClass);
            Log.i(TAG, "Successfully installed LatinIME hierarchy hooks!");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook LatinIME class in Gboard", t);
        }

    }

    private void hookInputMethodServiceHierarchy(Class<?> startClass) {
        Class<?> current = startClass;
        while (current != null && current != Object.class) {
            hookMethodIfDeclared(current, "onStartInput", EditorInfo.class, boolean.class);
            hookMethodIfDeclared(current, "onStartInputView", EditorInfo.class, boolean.class);
            hookMethodIfDeclared(current, "onCurrentInputMethodSubtypeChanged", InputMethodSubtype.class);
            hookMethodIfDeclared(current, "switchInputMethod", String.class, InputMethodSubtype.class);
            hookMethodIfDeclared(current, "getCurrentInputEditorInfo");
            hookMethodIfDeclared(current, "onFinishInput");
            hookMethodIfDeclared(current, "onWindowHidden");
            current = current.getSuperclass();
        }
    }

    private void hookMethodIfDeclared(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        try {
            Method m = clazz.getDeclaredMethod(methodName, parameterTypes);
            m.setAccessible(true);
            if (mHookedMethods.add(m)) {
                hook(m).intercept(new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        String name = chain.getExecutable().getName();
                        if ("switchInputMethod".equals(name)) {
                            Object arg0 = chain.getArg(0);
                            Object arg1 = chain.getArg(1);
                            if (arg0 instanceof String) {
                                mImeId = (String) arg0;
                            }
                            if (arg1 instanceof InputMethodSubtype) {
                                InputMethodSubtype st = (InputMethodSubtype) arg1;
                                if (isEnglishSubtype(st)) {
                                    mEnglishSubtype = st;
                                }
                                // 关键防御：只有在非终端会话且非模块主动自动切换时，才记录外部应用的语言状态
                                if (!mInTerminalSession && !mIsProgrammaticSwitch) {
                                    mLastNonTerminalSubtype = st;
                                    Log.d(TAG, "Captured non-terminal subtype from user switchInputMethod: " + getSubtypeDesc(st));
                                }
                            }
                            return chain.proceed();
                        } else if ("onCurrentInputMethodSubtypeChanged".equals(name)) {
                            Object arg = chain.getArg(0);
                            if (arg instanceof InputMethodSubtype) {
                                mLastSubtype = (InputMethodSubtype) arg;
                                Log.i(TAG, "Subtype changed: " + getSubtypeDesc(mLastSubtype));
                                if (isEnglishSubtype(mLastSubtype)) {
                                    mEnglishSubtype = mLastSubtype;
                                }
                                // 关键防御：只有在非终端会话且非模块主动自动切换时，才记录外部应用的语言状态
                                if (!mInTerminalSession && !mIsProgrammaticSwitch) {
                                    mLastNonTerminalSubtype = mLastSubtype;
                                    Log.d(TAG, "Updated non-terminal subtype: " + getSubtypeDesc(mLastNonTerminalSubtype));
                                }
                            }
                            Object thisObj = chain.getThisObject();
                            InputMethodService service = (thisObj instanceof InputMethodService)
                                    ? (InputMethodService) thisObj : null;
                            if (service != null) {
                                EditorInfo currentInfo = service.getCurrentInputEditorInfo();
                                if (currentInfo != null && (isTargetTerminalApp(currentInfo.packageName) || mInTerminalSession)) {
                                    mInTerminalSession = true;
                                    spoofEditorInfoIfTerminal(currentInfo, thisObj, true);
                                }
                            }
                            Object result = chain.proceed();
                            if (service != null && mInTerminalSession) {
                                restartCurrentInput(service);
                            }
                            return result;
                        } else if ("onStartInput".equals(name)) {
                            Object arg0 = chain.getArg(0);
                            boolean isRestarting = false;
                            if (chain.getExecutable().getParameterCount() > 1 && chain.getArg(1) instanceof Boolean) {
                                isRestarting = (Boolean) chain.getArg(1);
                            }
                            if (arg0 instanceof EditorInfo) {
                                EditorInfo info = (EditorInfo) arg0;
                                Object thisObj = chain.getThisObject();
                                InputMethodService service = (thisObj instanceof InputMethodService) ? (InputMethodService) thisObj : null;
                                handleStartInput(info, isRestarting, service);
                            }
                            return chain.proceed();
                        } else if ("onStartInputView".equals(name)) {
                            Object arg0 = chain.getArg(0);
                            if (arg0 instanceof EditorInfo) {
                                EditorInfo info = (EditorInfo) arg0;
                                Object thisObj = chain.getThisObject();
                                InputMethodService service = (thisObj instanceof InputMethodService) ? (InputMethodService) thisObj : null;
                                if (isTargetTerminalApp(info.packageName) || mInTerminalSession) {
                                    spoofEditorInfoIfTerminal(info, service, false);
                                } else if (service != null && mLastNonTerminalSubtype != null) {
                                    // 确保常规应用展示键盘视图时，输入法处于正确的外部语言
                                    InputMethodSubtype cur = getCurrentSubtype(service);
                                    if (!subtypesEqual(cur, mLastNonTerminalSubtype)) {
                                        Log.i(TAG, "onStartInputView: ensuring non-terminal subtype: " + getSubtypeDesc(mLastNonTerminalSubtype));
                                        switchToSubtype(service, mLastNonTerminalSubtype);
                                        mLastSubtype = mLastNonTerminalSubtype;
                                    }
                                }
                            }
                            return chain.proceed();
                        } else if ("getCurrentInputEditorInfo".equals(name)) {
                            Object res = chain.proceed();
                            if (res instanceof EditorInfo) {
                                EditorInfo info = (EditorInfo) res;
                                if (isTargetTerminalApp(info.packageName) || mInTerminalSession) {
                                    spoofEditorInfoIfTerminal(info, chain.getThisObject(), false);
                                }
                            }
                            return res;
                        } else if ("onFinishInput".equals(name)) {
                            Object thisObj = chain.getThisObject();
                            if (thisObj instanceof InputMethodService) {
                                handleTerminalBackground((InputMethodService) thisObj, "onFinishInput");
                            }
                            return chain.proceed();
                        } else if ("onWindowHidden".equals(name)) {
                            Object thisObj = chain.getThisObject();
                            if (thisObj instanceof InputMethodService) {
                                handleTerminalBackground((InputMethodService) thisObj, "onWindowHidden");
                            }
                            return chain.proceed();
                        }
                        return chain.proceed();
                    }
                });
                Log.i(TAG, "Hooked " + clazz.getName() + "." + methodName);
            }
        } catch (NoSuchMethodException ignored) {
            // Method not declared in this class level, continuing up hierarchy
        } catch (Throwable t) {
            Log.e(TAG, "Error hooking " + clazz.getName() + "." + methodName, t);
        }
    }

    private void handleStartInput(EditorInfo info, boolean restarting, InputMethodService service) {
        if (info == null) return;
        String currentPkg = info.packageName;

        if (isTargetTerminalApp(currentPkg)) {
            // 目标为终端类应用（Termux）
            boolean enteringTerminal = !mInTerminalSession
                    || mTerminalExited
                    || (mLastPackage != null && !isTargetTerminalApp(mLastPackage))
                    || (!restarting && (mLastPackage == null || !isTargetTerminalApp(mLastPackage)));
            mTerminalExited = false;

            if (enteringTerminal && service != null) {
                Log.i(TAG, "Entering terminal app: " + currentPkg + " (previous: " + mLastPackage + ", restarting=" + restarting + ")");

                mOriginalTerminalPkg = currentPkg;
                mOriginalInputType = info.inputType;
                mOriginalImeOptions = info.imeOptions;

                InputMethodSubtype curSubtype = getCurrentSubtype(service);
                // 1. 若从外部应用切入且当前非英文，固化记录外部应用的最后语言状态
                if (curSubtype != null && !isEnglishSubtype(curSubtype)) {
                    mLastNonTerminalSubtype = curSubtype;
                    Log.i(TAG, "Preserved non-terminal subtype before entering Termux: " + getSubtypeDesc(mLastNonTerminalSubtype));
                }

                // 2. 先将终端会话标记置为 true，确保后续切换为英文时不污染 mLastNonTerminalSubtype
                mInTerminalSession = true;

                // 3. 进入 Termux 默认置为英文键盘！
                if (!isEnglishSubtype(curSubtype)) {
                    InputMethodSubtype enSub = findEnglishSubtype(service);
                    if (enSub != null) {
                        Log.i(TAG, "Defaulting Termux to English subtype: " + getSubtypeDesc(enSub));
                        switchToSubtype(service, enSub);
                        mLastSubtype = enSub;
                    }
                }
            } else {
                mInTerminalSession = true;
            }

            if (!VIRTUAL_TERMINAL_PKG.equals(currentPkg)) {
                mOriginalTerminalPkg = currentPkg;
            }
            spoofEditorInfoIfTerminal(info, service, true);
        } else {
            // 目标为非终端常规应用（Telegram、Chrome 等）
            mInTerminalSession = false;
            mTerminalExited = false;

            if (service != null && mLastNonTerminalSubtype != null) {
                InputMethodSubtype curSubtype = getCurrentSubtype(service);
                if (!subtypesEqual(curSubtype, mLastNonTerminalSubtype)) {
                    Log.i(TAG, "Restoring preserved non-terminal subtype for " + currentPkg + ": " + getSubtypeDesc(mLastNonTerminalSubtype));
                    switchToSubtype(service, mLastNonTerminalSubtype);
                    mLastSubtype = mLastNonTerminalSubtype;
                }
            } else if (service != null) {
                // 常规应用中当前语言记录为非终端快照
                InputMethodSubtype curSubtype = getCurrentSubtype(service);
                if (curSubtype != null && !mIsProgrammaticSwitch) {
                    mLastNonTerminalSubtype = curSubtype;
                }
            }
        }

        if (currentPkg != null && !VIRTUAL_TERMINAL_PKG.equals(currentPkg)) {
            mLastPackage = currentPkg;
        }
    }

    private void handleTerminalBackground(InputMethodService service, String trigger) {
        // 只要 Termux 不在前台（滑到后台、退到桌面、切换应用、键盘隐藏），立即恢复外部语言！
        if (mInTerminalSession) {
            Log.i(TAG, "Termux left foreground (trigger: " + trigger + "). Immediate restore to non-terminal subtype: "
                    + getSubtypeDesc(mLastNonTerminalSubtype));
            mInTerminalSession = false;
            mTerminalExited = true;

            if (service != null && mLastNonTerminalSubtype != null) {
                InputMethodSubtype cur = getCurrentSubtype(service);
                if (!subtypesEqual(cur, mLastNonTerminalSubtype)) {
                    switchToSubtype(service, mLastNonTerminalSubtype);
                    mLastSubtype = mLastNonTerminalSubtype;
                }
            }
        }
    }

    private void switchToSubtype(InputMethodService service, InputMethodSubtype subtype) {
        if (service == null || subtype == null) return;
        String imeId = getImeId(service);
        mIsProgrammaticSwitch = true;
        try {
            Log.i(TAG, "Switching subtype to: " + getSubtypeDesc(subtype) + " (imeId=" + imeId + ")");
            service.switchInputMethod(imeId, subtype);
        } catch (Throwable t) {
            Log.e(TAG, "Direct switchInputMethod failed, attempting reflection", t);
            try {
                Method m = InputMethodService.class.getMethod("switchInputMethod", String.class, InputMethodSubtype.class);
                m.setAccessible(true);
                m.invoke(service, imeId, subtype);
            } catch (Throwable t2) {
                Log.e(TAG, "Reflection switchInputMethod failed", t2);
            }
        } finally {
            mIsProgrammaticSwitch = false;
        }
    }

    private void restartCurrentInput(InputMethodService service) {
        try {
            InputConnection connection = service.getCurrentInputConnection();
            EditorInfo info = service.getCurrentInputEditorInfo();
            if (connection == null || info == null) return;

            Object inputMethod = service.onCreateInputMethodInterface();
            Method restartInput = inputMethod.getClass().getMethod(
                    "restartInput", InputConnection.class, EditorInfo.class);
            restartInput.invoke(inputMethod, connection, info);
            Log.i(TAG, "Restarted current Termux input for " + getSubtypeDesc(mLastSubtype));
        } catch (Throwable t) {
            Log.e(TAG, "Failed to restart current Termux input", t);
        }
    }

    private InputMethodSubtype getCurrentSubtype(InputMethodService service) {
        if (mLastSubtype != null) {
            return mLastSubtype;
        }
        if (service != null) {
            try {
                InputMethodManager imm = (InputMethodManager) service.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    InputMethodSubtype st = imm.getCurrentInputMethodSubtype();
                    if (st != null) {
                        mLastSubtype = st;
                        return st;
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private InputMethodSubtype findEnglishSubtype(InputMethodService service) {
        if (mEnglishSubtype != null) {
            return mEnglishSubtype;
        }
        if (service == null) return null;
        try {
            InputMethodManager imm = (InputMethodManager) service.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                List<InputMethodInfo> imis = imm.getEnabledInputMethodList();
                for (InputMethodInfo imi : imis) {
                    if (service.getPackageName().equals(imi.getPackageName())) {
                        List<InputMethodSubtype> list = imm.getEnabledInputMethodSubtypeList(imi, true);
                        // 1. 优先寻找明确声明英文语言的代码（en_US, en_GB 等）
                        for (InputMethodSubtype st : list) {
                            if (isEnglishSubtype(st)) {
                                mEnglishSubtype = st;
                                Log.i(TAG, "Discovered English subtype: " + getSubtypeDesc(st));
                                return st;
                            }
                        }
                        // 2. 兜底寻找支持 ASCII 的非中日文键盘 Subtype
                        for (InputMethodSubtype st : list) {
                            if ("keyboard".equalsIgnoreCase(st.getMode()) && st.isAsciiCapable() && !isChineseSubtype(st) && !isJapaneseSubtype(st)) {
                                mEnglishSubtype = st;
                                Log.i(TAG, "Discovered fallback ASCII-capable subtype: " + getSubtypeDesc(st));
                                return st;
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to find English subtype", t);
        }
        return null;
    }

    private String getImeId(InputMethodService service) {
        if (mImeId != null) {
            return mImeId;
        }
        // 1. 反射获取 InputMethodService.mCurId
        try {
            Field f = InputMethodService.class.getDeclaredField("mCurId");
            f.setAccessible(true);
            String id = (String) f.get(service);
            if (id != null && !id.isEmpty()) {
                mImeId = id;
                Log.i(TAG, "Found imeId from mCurId: " + id);
                return id;
            }
        } catch (Throwable ignored) {}

        // 2. 从系统 Settings.Secure 查询默认输入法
        try {
            String defaultIme = Settings.Secure.getString(service.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            if (defaultIme != null && defaultIme.contains(service.getPackageName())) {
                mImeId = defaultIme;
                Log.i(TAG, "Found imeId from Settings.Secure: " + defaultIme);
                return defaultIme;
            }
        } catch (Throwable ignored) {}

        // 3. 从 InputMethodManager 获取已启用输入法列表中匹配 Gboard 的 ID
        try {
            InputMethodManager imm = (InputMethodManager) service.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                for (InputMethodInfo imi : imm.getEnabledInputMethodList()) {
                    if (service.getPackageName().equals(imi.getPackageName())) {
                        mImeId = imi.getId();
                        Log.i(TAG, "Found imeId from InputMethodManager: " + mImeId);
                        return mImeId;
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 4. 标准兜底
        mImeId = "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME";
        return mImeId;
    }

    private static boolean subtypesEqual(InputMethodSubtype a, InputMethodSubtype b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.hashCode() == b.hashCode() || a.equals(b);
    }

    private static boolean isTargetTerminalApp(String pkg) {
        if (pkg == null) return false;
        return pkg.equals("com.termux")
            || pkg.startsWith("com.termux.")
            || pkg.equals("io.neoterm")
            || pkg.equals("jackpal.androidterm")
            || pkg.equals(VIRTUAL_TERMINAL_PKG);
    }

    private static boolean isEnglishSubtype(InputMethodSubtype subtype) {
        if (subtype == null) return false;
        String mode = subtype.getMode();
        if (mode != null && !"keyboard".equalsIgnoreCase(mode)) {
            return false;
        }
        String locale = subtype.getLocale();
        if (locale != null && locale.toLowerCase().startsWith("en")) {
            return true;
        }
        String langTag = subtype.getLanguageTag();
        if (langTag != null && langTag.toLowerCase().startsWith("en")) {
            return true;
        }
        return false;
    }

    private static boolean isChineseSubtype(InputMethodSubtype subtype) {
        if (subtype == null) return false;
        String locale = subtype.getLocale();
        if (locale != null && locale.toLowerCase().startsWith("zh")) {
            return true;
        }
        String langTag = subtype.getLanguageTag();
        if (langTag != null && langTag.toLowerCase().startsWith("zh")) {
            return true;
        }
        return false;
    }

    private static boolean isJapaneseSubtype(InputMethodSubtype subtype) {
        if (subtype == null) return false;
        String locale = subtype.getLocale();
        if (locale != null && locale.toLowerCase().startsWith("ja")) {
            return true;
        }
        String langTag = subtype.getLanguageTag();
        if (langTag != null && langTag.toLowerCase().startsWith("ja")) {
            return true;
        }
        return false;
    }

    private static String getSubtypeDesc(InputMethodSubtype subtype) {
        if (subtype == null) return "null";
        return "[locale=" + subtype.getLocale() + ", tag=" + subtype.getLanguageTag() + ", mode=" + subtype.getMode() + ", hash=" + subtype.hashCode() + "]";
    }

    private void spoofEditorInfoIfTerminal(EditorInfo info, Object serviceObj, boolean logVerbose) {
        if (info == null) return;
        String pkg = info.packageName;
        if (!isTargetTerminalApp(pkg) && !mInTerminalSession) {
            return;
        }

        if (pkg != null && isTargetTerminalApp(pkg) && !VIRTUAL_TERMINAL_PKG.equals(pkg)) {
            mOriginalTerminalPkg = pkg;
        }

        // 获取当前活跃的 Subtype
        InputMethodSubtype subtype = mLastSubtype;
        if (subtype == null && serviceObj instanceof Context) {
            try {
                InputMethodManager imm = (InputMethodManager) ((Context) serviceObj).getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    subtype = imm.getCurrentInputMethodSubtype();
                    if (subtype != null) {
                        mLastSubtype = subtype;
                    }
                }
            } catch (Throwable ignored) {}
        }

        // Keep Termux's raw editor in English so punctuation is committed immediately.
        // Chinese needs an ordinary text editor plus restartInput to activate composing.
        info.packageName = (mOriginalTerminalPkg != null) ? mOriginalTerminalPkg : "com.termux";
        boolean isChinese = isChineseSubtype(subtype);
        if (isChinese) {
            info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL;
            info.imeOptions = mOriginalImeOptions | EditorInfo.IME_FLAG_NO_FULLSCREEN;
        } else {
            info.inputType = mOriginalInputType;
            info.imeOptions = mOriginalImeOptions;
        }

        if (isChinese && logVerbose) {
            Log.i(TAG, "Gboard: [CHINESE MODE] Restored EditorInfo for " + info.packageName
                    + " (" + getSubtypeDesc(subtype) + "): inputType=0x" + Integer.toHexString(info.inputType));
        }
    }
}
