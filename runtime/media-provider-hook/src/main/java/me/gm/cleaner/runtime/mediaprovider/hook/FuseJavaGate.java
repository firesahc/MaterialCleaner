package me.gm.cleaner.runtime.mediaprovider.hook;

import android.os.FileObserver;
import android.system.OsConstants;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import me.gm.cleaner.core.storage.redirect.databus.DataBus;
import org.json.JSONObject;

/**
 * FUSE Java 入口门 — 自动驾驶版本。
 * <p>
 * 职责：自动发现 MediaProvider 中所有 FUSE 相关方法，智能分配行为模板并安装 Hook。
 * 不再硬编码方法签名 —— 通过 {@link #scanFuseMethods()} 反射扫描 +
 * {@link BehaviorRegistry} 方法名匹配 + {@link ParameterAnalyzer} 参数推断，
 * 自动适配 Android 版本差异和厂商 ROM 自定义签名。
 * <p>
 * 行为模板（BehaviorHandler）六种：
 * - fileOp(eventType)：insert / delete 等文件操作（事件分发 + 只读检查 + 路径重定向）
 * - renameOp：重命名（双路径重定向 + 双事件 + 双只读检查）
 * - simpleRedirect：仅路径重定向（openWithFuse 等）
 * - multiArgConsistency：路径重定向 + 多 String 参数同步（onFileLookup/OpenForFuse 等）
 * - accessCheck：目录访问检查（自动识别 int / boolean 第三个参数变体）
 * - uidTracking：仅跟踪 uid 访问（isUidAllowedAccess...）
 * <p>
 * 不负责：xhook 安装、containsMount、fuse_bpf_install、native mountPoint。
 */
public class FuseJavaGate {
    private static final int DIR = 0x40000000;
    static final int DIRECTORY_ACCESS_FOR_READ = 1;
    static final int DIRECTORY_ACCESS_FOR_WRITE = 2;
    static final int DIRECTORY_ACCESS_FOR_CREATE = 3;
    static final int DIRECTORY_ACCESS_FOR_DELETE = 4;

    private final MediaProviderHook mHook;
    private final MediaProviderHooksService mService;
    private final ClassLoader mClassLoader;
    private final Class<?> mMediaProviderClass;

    public FuseJavaGate(MediaProviderHook hook, MediaProviderHooksService service,
                        ClassLoader classLoader, Class<?> mediaProviderClass) {
        mHook = hook;
        mService = service;
        mClassLoader = classLoader;
        mMediaProviderClass = mediaProviderClass;
        initFuseHooks();
    }

    // ════════════════════════════════════════════════════════════════
    //  FUSE Hook 自动驾驶安装
    // ════════════════════════════════════════════════════════════════

    /**
     * 自动驾驶入口：扫描 → 注册匹配 → 参数分析 → 自动化安装。
     * <p>
     * 流程：
     * 1. {@link #scanFuseMethods()} — 反射扫描 MediaProvider 中所有含 "Fuse" 的方法
     * 2. {@link BehaviorRegistry#lookup(String)} — 按方法名查找行为模板（精确匹配 → 启发式回退）
     * 3. {@link ParameterAnalyzer#analyze(Method)} — 推断 pathIndex / uidIndex 等参数角色
     * 4. {@link #installHook(Method, BehaviorHandler, ParamRoles)} — 统一异常安全包装后安装
     * <p>
     * 未知方法仅记录日志，不会导致崩溃。
     */
    private void initFuseHooks() {
        Log.i("MC_REDIRECT", "[FuseJavaGate] initFuseHooks() — auto-pilot scanning...");

        final BehaviorRegistry registry = new BehaviorRegistry();
        final List<DiscoveredMethod> discovered = scanFuseMethods();
        final List<String> hookedMethods = new ArrayList<>();
        final List<String> unknownMethods = new ArrayList<>();
        final List<String> failedMethods = new ArrayList<>();

        for (final DiscoveredMethod dm : discovered) {
            final BehaviorHandler handler = registry.lookup(dm.method.getName());
            if (handler == null) {
                unknownMethods.add(dm.method.getName() + " " + Arrays.toString(dm.method.getParameterTypes()));
                continue;
            }
            final ParamRoles roles = ParameterAnalyzer.analyze(dm.method);
            if (roles == null || roles.pathIndex < 0) {
                unknownMethods.add(dm.method.getName() + " " + Arrays.toString(dm.method.getParameterTypes())
                        + " (unanalyzable params)");
                continue;
            }
            final String signature = dm.method.getName() + " " + Arrays.toString(dm.method.getParameterTypes());
            try {
                installHook(dm.method, handler, roles);
                hookedMethods.add(signature);
            } catch (final Throwable t) {
                failedMethods.add(signature + " (" + t.getClass().getName() + ": " + t.getMessage() + ")");
                Log.e("MC_REDIRECT", "[FuseJavaGate] install failed " + signature, t);
            }
        }

        // 汇总日志
        for (final String s : hookedMethods) {
            Log.i("MC_REDIRECT", "[FuseJavaGate] hooked " + s);
        }
        for (final String s : unknownMethods) {
            Log.w("MC_REDIRECT", "[FuseJavaGate] UNKNOWN FUSE method: " + s);
        }
        for (final String s : failedMethods) {
            Log.e("MC_REDIRECT", "[FuseJavaGate] FAILED FUSE method: " + s);
        }
        Log.i("MC_REDIRECT", "[FuseJavaGate] initFuseHooks complete: "
                + hookedMethods.size() + " hooked, " + unknownMethods.size()
                + " unknown, " + failedMethods.size() + " failed");
        NativeHookStatus.INSTANCE.markFuseJavaGateScanned(
                discovered.size(), hookedMethods, unknownMethods, failedMethods);
    }

    /**
     * 扫描 MediaProvider 类中所有 FUSE 相关方法。
     * <p>
     * 筛选条件：
     * - 方法名包含 "fuse"（大小写不敏感，覆盖 Fuse/FUSE/fuse 等变体）
     * - 至少有一个 String 类型参数（路径参数）
     * <p>
     * 不要求第一个参数是 String —— isUidAllowedAccessToDataOrObbPathForFuse(int, String)
     * 等反转参数方法也可被发现。{@link ParameterAnalyzer} 会正确处理参数角色反转。
     */
    private List<DiscoveredMethod> scanFuseMethods() {
        final List<DiscoveredMethod> result = new ArrayList<>();
        for (final Method method : mMediaProviderClass.getDeclaredMethods()) {
            final String name = method.getName();
            if (!name.toLowerCase(Locale.ROOT).contains("fuse")) {
                continue;
            }
            // 必须有至少一个 String 参数（路径）
            boolean hasStringParam = false;
            for (final Class<?> p : method.getParameterTypes()) {
                if (p == String.class) {
                    hasStringParam = true;
                    break;
                }
            }
            if (!hasStringParam) {
                continue;
            }
            result.add(new DiscoveredMethod(method));
        }
        return result;
    }

    /**
     * 安装单个 FUSE Hook，使用统一的异常安全包装。
     * <p>
     * 所有 Hook 共享同一个异常策略：捕获所有 Throwable、记录日志、不阻断文件系统。
     * 这是与旧代码的关键区别 —— 旧代码中 renameForFuse 和 openWithFuse 的 handler
     * 没有 try-catch，异常会传播到 Xposed 框架并可能导致 MediaProvider 崩溃。
     */
    private void installHook(final Method method, final BehaviorHandler handler, final ParamRoles roles) {
        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(final MethodHookParam param) {
                try {
                    handler.handle(param, roles, method.getName());
                } catch (final Throwable t) {
                    Log.e("MC_REDIRECT", "[FuseJavaGate] handler failed for "
                            + method.getName() + " " + Arrays.toString(method.getParameterTypes()), t);
                    // 永不阻断文件系统操作 —— Hook 是可选增强，不是系统依赖
                }
            }
        });
    }

    // ════════════════════════════════════════════════════════════════
    //  行为模板工厂方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 品种 A — fileOp(eventType)：文件操作（insert / delete）。
     * <p>
     * 行为链：重定向路径 → 分发文件事件（CREATE / DELETE）→ 只读检查（EPERM）
     */
    private BehaviorHandler fileOp(final int eventType) {
        return (param, roles, methodName) -> {
            final String path = (String) param.args[roles.pathIndex];
            final int uid = resolveUid(param, roles, methodName);
            if (uid < 0) {
                Log.w("MC_REDIRECT", "[FuseJavaGate] " + methodName + " cannot find uid");
                return;
            }
            final String packageName = getCallingPackageName(param.thisObject, uid);
            dispatchFileSystemEvent(packageName, path, eventType, methodName, uid);
            final String mountedPath = redirectFusePath(param, roles.pathIndex, uid, methodName);
            if (mService.isReadOnly(mountedPath, uid)) {
                param.setResult(OsConstants.EPERM);
            }
        };
    }

    /**
     * 品种 B — renameOp：重命名操作（双路径）。
     * <p>
     * 行为链：isDir 检测 → 分发 MOVED_FROM / MOVED_TO 事件 → 双路径重定向 → 双只读检查
     */
    private BehaviorHandler renameOp() {
        return (param, roles, methodName) -> {
            final String oldPath = (String) param.args[roles.pathIndex];
            final String newPath = (String) param.args[roles.path2Index];
            final int uid = resolveUid(param, roles, methodName);
            if (uid < 0) {
                Log.w("MC_REDIRECT", "[FuseJavaGate] " + methodName + " cannot find uid");
                return;
            }
            final String packageName = getCallingPackageName(param.thisObject, uid);
            final int isDir = new File(oldPath).isFile() ? 0 : DIR;
            dispatchFileSystemEvent(packageName, oldPath, FileObserver.MOVED_FROM | isDir,
                    methodName, uid);
            dispatchFileSystemEvent(packageName, newPath, FileObserver.MOVED_TO | isDir,
                    methodName, uid);
            final String mountedOldPath = redirectFusePath(param, roles.pathIndex, uid, methodName + ".oldPath");
            final String mountedNewPath = redirectFusePath(param, roles.path2Index, uid, methodName + ".newPath");
            if (mService.isReadOnly(mountedOldPath, uid) || mService.isReadOnly(mountedNewPath, uid)) {
                param.setResult(OsConstants.EPERM);
            }
        };
    }

    /**
     * 品种 C — simpleRedirect：仅路径重定向（openWithFuse 等）。
     * <p>
     * 无事件分发、无只读检查 —— 只做路径替换。
     */
    private BehaviorHandler simpleRedirect() {
        return (param, roles, methodName) -> {
            final int uid = resolveUid(param, roles, methodName);
            if (uid < 0) {
                Log.w("MC_REDIRECT", "[FuseJavaGate] " + methodName + " cannot find uid, skipping redirect");
                return;
            }
            redirectFusePath(param, roles.pathIndex, uid, methodName);
        };
    }

    /**
     * 品种 D — multiArgConsistency：多 String 参数同步（onFileLookupForFuse / onFileOpenForFuse）。
     * <p>
     * 行为链：路径重定向 → 将所有等于原始路径的 String 参数同步更新为挂载后路径。
     * uid 位置不确定，通过 {@link #resolveUid} 运行时推断。
     */
    private BehaviorHandler multiArgConsistency() {
        return (param, roles, methodName) -> {
            final String originalPath = (String) param.args[roles.pathIndex];
            final int uid = resolveUid(param, roles, methodName);
            if (uid < 0) {
                Log.w("MC_REDIRECT", "[FuseJavaGate] " + methodName
                        + " cannot find uid — signature available in hook log");
                return;
            }
            final String mountedPath = redirectFusePath(param, roles.pathIndex, uid, methodName);
            redirectMatchingStringPathArgs(param, originalPath, mountedPath);
        };
    }

    /**
     * 品种 E — accessCheck：目录访问检查。
     * <p>
     * 自动识别第三个参数的类型（int accessType 或 boolean forCreate），
     * 运行时按类型分发逻辑：
     * <ul>
     *   <li>int 版：accessType == CREATE/DELETE 时触发事件，accessType != READ 时只读检查</li>
     *   <li>boolean 版：forCreate 决定事件类型，始终进行只读检查</li>
     * </ul>
     */
    private BehaviorHandler accessCheck() {
        return (param, roles, methodName) -> {
            final String path = (String) param.args[roles.pathIndex];
            final int uid = resolveUid(param, roles, methodName);
            if (uid < 0) {
                Log.w("MC_REDIRECT", "[FuseJavaGate] " + methodName + " cannot find uid");
                return;
            }
            // 第三个参数可能是 int(accessType) 或 boolean(forCreate)
            final Object extraArg = param.args.length > roles.uidIndex + 1
                    ? param.args[roles.uidIndex + 1] : null;

            if (extraArg instanceof Integer) {
                // int 版：isDirAccessAllowedForFuse(String, int, int)
                final int accessType = (int) extraArg;
                if (accessType == DIRECTORY_ACCESS_FOR_CREATE
                        || accessType == DIRECTORY_ACCESS_FOR_DELETE) {
                    final String packageName = getCallingPackageName(param.thisObject, uid);
                    dispatchFileSystemEvent(packageName, path,
                            (accessType == DIRECTORY_ACCESS_FOR_CREATE
                                    ? FileObserver.CREATE : FileObserver.DELETE) | DIR,
                            methodName, uid);
                }
                final String mountedPath = redirectFusePath(param, roles.pathIndex, uid, methodName);
                if (accessType != DIRECTORY_ACCESS_FOR_READ
                        && mService.isReadOnly(mountedPath, uid)) {
                    param.setResult(OsConstants.EPERM);
                }
            } else if (extraArg instanceof Boolean) {
                // boolean 版：isDirectoryCreationOrDeletionAllowedForFuse(String, int, boolean)
                final boolean forCreate = (boolean) extraArg;
                final String packageName = getCallingPackageName(param.thisObject, uid);
                dispatchFileSystemEvent(packageName, path,
                        (forCreate ? FileObserver.CREATE : FileObserver.DELETE) | DIR,
                        methodName, uid);
                final String mountedPath = redirectFusePath(param, roles.pathIndex, uid, methodName);
                if (mService.isReadOnly(mountedPath, uid)) {
                    param.setResult(OsConstants.EPERM);
                }
            } else {
                // 未知第三个参数类型 —— 回退为简单重定向，记录警告
                Log.w("MC_REDIRECT", "[FuseJavaGate] " + methodName
                        + " unexpected extra arg type: "
                        + (extraArg != null ? extraArg.getClass().getName() : "null"));
                redirectFusePath(param, roles.pathIndex, uid, methodName);
            }
        };
    }

    /**
     * 品种 F — uidTracking：仅跟踪 uid 路径访问。
     * <p>
     * 无重定向、无事件分发 —— 仅记录到 QuerySessionCache。
     * 方法签名通常参数反转：(int uid, String path)。
     */
    private BehaviorHandler uidTracking() {
        return (param, roles, methodName) -> {
            final int uid = (int) param.args[roles.uidIndex];
            final String path = (String) param.args[roles.pathIndex];
            final String packageName = getCallingPackageName(param.thisObject, uid);
            QuerySessionCache.maybeAccessQueriedPath(packageName, uid, path);
        };
    }

    // ════════════════════════════════════════════════════════════════
    //  uid 解析
    // ════════════════════════════════════════════════════════════════

    /**
     * 统一 uid 解析：优先使用静态分析的 uidIndex，回退到运行时值推断。
     * <p>
     * 当 {@link ParamRoles#uidIndex} >= 0 时直接取值；
     * 否则调用 {@link #findCallingUid(String, Object[])} 在运行时扫描参数列表，
     * 利用方法名提示 + 值 >= 10000 启发式定位 uid。
     */
    private int resolveUid(final XC_MethodHook.MethodHookParam param,
                           final ParamRoles roles, final String methodName) {
        if (roles.uidIndex >= 0) {
            final Object arg = param.args[roles.uidIndex];
            if (arg instanceof Integer) {
                return (int) arg;
            }
            // uidIndex 指向的不是 int —— 运行时回退
        }
        return findCallingUid(methodName, param.args);
    }

    // ════════════════════════════════════════════════════════════════
    //  内部类型
    // ════════════════════════════════════════════════════════════════

    /**
     * 行为处理函数接口 —— 所有 FUSE 方法 Hook 回调的统一抽象。
     * <p>
     * 六个实现品种：fileOp、renameOp、simpleRedirect、multiArgConsistency、accessCheck、uidTracking。
     * 每个品种通过 {@link FuseJavaGate} 中的工厂方法创建。
     */
    @FunctionalInterface
    private interface BehaviorHandler {
        /**
         * @param param      Xposed 方法 Hook 参数（args、thisObject、result 等）
         * @param roles      静态分析得到的参数角色（path/uid/index 等）
         * @param methodName 方法名（用于日志和运行时 uid 推断提示）
         */
        void handle(XC_MethodHook.MethodHookParam param, ParamRoles roles, String methodName)
                throws Throwable;
    }

    /**
     * 参数角色 —— 静态分析阶段确定的方法参数职责。
     * <p>
     * 分离"参数在哪"（roles）和"用它做什么"（BehaviorHandler），
     * 使得同一个行为模板可以处理不同参数布局的方法签名。
     */
    private static class ParamRoles {
        /** 主要路径参数索引（通常是第一个 String） */
        final int pathIndex;
        /** 第二个路径参数索引（rename 场景），-1 表示不存在 */
        final int path2Index;
        /** uid 参数索引，-1 表示需运行时推断 */
        final int uidIndex;
        /** 额外参数角色语义 */
        final ExtraParamRole extraRole;

        ParamRoles(int pathIndex, int path2Index, int uidIndex, ExtraParamRole extraRole) {
            this.pathIndex = pathIndex;
            this.path2Index = path2Index;
            this.uidIndex = uidIndex;
            this.extraRole = extraRole;
        }

        @Override
        public String toString() {
            return "ParamRoles{path=" + pathIndex + ", path2=" + path2Index
                    + ", uid=" + uidIndex + ", extra=" + extraRole + "}";
        }
    }

    /** 额外参数角色的语义分类 */
    private enum ExtraParamRole {
        NONE,            // 无额外参数 / 不需要额外处理
        ACCESS_TYPE_INT, // 第三个参数是 int accessType
        ACCESS_TYPE_BOOL,// 第三个参数是 boolean forCreate
        IGNORE           // 有多余参数但忽略（如 HyperOS delete 的第三个 int）
    }

    /**
     * BehaviorRegistry — 方法名到行为模板的映射注册表。
     * <p>
     * 匹配策略（两级回退）：
     * 1. 精确匹配：已确认的 FUSE 方法名 → 确定性的行为模板
     * 2. 启发式匹配：未知方法名 → 按命名前缀模式猜测行为
     * <p>
     * 精确匹配优先：insertFileIfNecessaryForFuse → fileOp(CREATE)
     * 启发式回退：某厂商新增 unknownFileOpForFuse → 匹配 "Fuse" + 前缀启发式
     */
    private class BehaviorRegistry {
        private final Map<String, BehaviorHandler> exactRegistry = new LinkedHashMap<>();
        private final List<HeuristicRule> heuristicRules = new ArrayList<>();

        BehaviorRegistry() {
            // ── 注册表：精确方法名 → 行为模板 ──
            exactRegistry.put("insertfileifnecessaryforfuse", fileOp(FileObserver.CREATE));
            exactRegistry.put("deletefileforfuse", fileOp(FileObserver.DELETE));
            exactRegistry.put("renameforfuse", renameOp());
            exactRegistry.put("openwithfuse", simpleRedirect());
            exactRegistry.put("onfilelookupforfuse", multiArgConsistency());
            exactRegistry.put("onfileopenforfuse", multiArgConsistency());
            exactRegistry.put("isdiraccessallowedforfuse", accessCheck());
            exactRegistry.put("isdirectorycreationordeletionallowedforfuse", accessCheck());
            exactRegistry.put("isuidallowedaccesstodataorobbpathforfuse", uidTracking());

            // ── 启发式规则（按优先级排序）──
            // 规则：同时匹配 "insert" / "create" / "add" 和 "Fuse" → fileOp(CREATE)
            heuristicRules.add(new HeuristicRule("insert", fileOp(FileObserver.CREATE)));
            heuristicRules.add(new HeuristicRule("delete", fileOp(FileObserver.DELETE)));
            heuristicRules.add(new HeuristicRule("rename", renameOp()));
            heuristicRules.add(new HeuristicRule("open", simpleRedirect()));
            // "onFile" 前缀 → multiArgConsistency
            heuristicRules.add(new HeuristicRule.PrefixRule("onfile", multiArgConsistency()));
            // "isDir" 或 "isDirectory" 前缀 → accessCheck
            heuristicRules.add(new HeuristicRule.PrefixRule("isdir", accessCheck()));
            heuristicRules.add(new HeuristicRule.PrefixRule("isdirectory", accessCheck()));
            // "isUid" 前缀 → uidTracking
            heuristicRules.add(new HeuristicRule.PrefixRule("isuid", uidTracking()));
        }

        /**
         * 查找方法名对应的行为模板。
         *
         * @param methodName 方法名（不含包名）
         * @return 行为模板，或 null 如果无法匹配
         */
        BehaviorHandler lookup(final String methodName) {
            final String normalizedName = methodName.toLowerCase(Locale.ROOT);
            // 1. 精确匹配
            final BehaviorHandler exact = exactRegistry.get(normalizedName);
            if (exact != null) {
                return exact;
            }
            // 2. 启发式回退
            for (final HeuristicRule rule : heuristicRules) {
                if (rule.matches(normalizedName)) {
                    Log.i("MC_REDIRECT", "[FuseJavaGate] heuristic match: " + methodName
                            + " (rule: \"" + rule.substring + "\")");
                    return rule.handler;
                }
            }
            return null;
        }
    }

    /**
     * 启发式匹配规则 —— 方法名子串或前缀匹配。
     */
    private static class HeuristicRule {
        final String substring;
        final BehaviorHandler handler;

        HeuristicRule(String substring, BehaviorHandler handler) {
            this.substring = substring;
            this.handler = handler;
        }

        boolean matches(String name) {
            return name.contains(substring);
        }

        /** 前缀匹配专用子类 */
        static class PrefixRule extends HeuristicRule {
            PrefixRule(String prefix, BehaviorHandler handler) {
                super(prefix, handler);
            }

            @Override
            boolean matches(String name) {
                return name.startsWith(substring);
            }
        }
    }

    /**
     * 方法扫描发现的原始数据 —— 持有反射 Method 对象。
     */
    private static class DiscoveredMethod {
        final Method method;

        DiscoveredMethod(Method method) {
            this.method = method;
        }
    }

    /**
     * ParameterAnalyzer — 静态参数角色推断。
     * <p>
     * 根据方法名的命名模式和参数类型序列，推断 pathIndex、uidIndex 等参数角色。
     * 三层推断策略：
     * <p>
     * Level 1 — 方法名模式识别：
     * - "isUid" 前缀 → 参数反转 (int uid, String path)
     * - "isDir"/"isDirectory" 前缀 → uid 固定在 index 1
     * <p>
     * Level 2 — 参数类型序列分析：
     * - (String, int) → path@0, uid@1
     * - (String, String, int) → path@0, path2@1, uid@2
     * - (String, int, int) → path@0, uid@1, extra=ACCESS_TYPE_INT
     * - (String, int, boolean) → path@0, uid@1, extra=ACCESS_TYPE_BOOL
     * <p>
     * Level 3 — uidIndex = -1（运行时由 {@link #resolveUid} 值推断）
     */
    private static class ParameterAnalyzer {

        /**
         * 分析方法的参数角色。
         *
         * @param method 反射方法对象
         * @return ParamRoles，或 null 如果无法分析（无 String 参数等）
         */
        static ParamRoles analyze(final Method method) {
            final String name = method.getName().toLowerCase(Locale.ROOT);
            final Class<?>[] types = method.getParameterTypes();

            // ── Level 1: 方法名模式识别 ──

            // isUid* 系列：参数反转 (int uid, String path)
            if (name.startsWith("isuid") && types.length >= 2) {
                return new ParamRoles(/*pathIndex=*/1, /*path2Index=*/-1,
                        /*uidIndex=*/0, ExtraParamRole.NONE);
            }

            // ── 路径参数发现 ──
            int pathIndex = -1;
            int path2Index = -1;
            for (int i = 0; i < types.length; i++) {
                if (types[i] == String.class) {
                    if (pathIndex < 0) {
                        pathIndex = i;
                    } else if (path2Index < 0) {
                        path2Index = i;
                    }
                }
            }
            if (pathIndex < 0) {
                return null; // 没有 String 路径参数
            }

            // ── Level 1 cont'd: isDir/isDirectory — uid 固定在 index 1 ──
            if (name.startsWith("isdir") || name.startsWith("isdirectory")) {
                int uidIdx = (types.length > 1 && types[1] == int.class) ? 1 : -1;
                ExtraParamRole extra = ExtraParamRole.NONE;
                if (types.length > 2) {
                    if (types[2] == boolean.class) {
                        extra = ExtraParamRole.ACCESS_TYPE_BOOL;
                    } else if (types[2] == int.class) {
                        extra = ExtraParamRole.ACCESS_TYPE_INT;
                    }
                }
                return new ParamRoles(pathIndex, path2Index, uidIdx, extra);
            }

            // ── Level 2: 参数类型序列分析 ──
            int uidIndex = -1;
            ExtraParamRole extra = ExtraParamRole.NONE;

            // 收集所有 int 参数索引
            final List<Integer> intIndices = new ArrayList<>();
            for (int i = 0; i < types.length; i++) {
                if (types[i] == int.class) {
                    intIndices.add(i);
                }
            }

            if (intIndices.isEmpty()) {
                // 无 int 参数 → 运行时推断 uid
                return new ParamRoles(pathIndex, path2Index, -1, ExtraParamRole.NONE);
            }

            // 单 int 参数 → 一定是 uid
            if (intIndices.size() == 1) {
                uidIndex = intIndices.get(0);
                return new ParamRoles(pathIndex, path2Index, uidIndex, ExtraParamRole.NONE);
            }

            // 多 int 参数 —— 根据方法名判断
            uidIndex = intIndices.get(0); // 默认第一个 int 是 uid

            if (name.contains("delete")) {
                // deleteFileForFuse(String, int, int) → 第三个 int 忽略
                extra = ExtraParamRole.IGNORE;
            }
            // 其他情况（如 openWithFuse 多重建载）：第一个 int 是 uid，多余的静默忽略

            return new ParamRoles(pathIndex, path2Index, uidIndex, extra);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  文件事件分发
    // ════════════════════════════════════════════════════════════════

    void dispatchFileSystemEvent(final String packageName, final String path, final int flags,
                                 final String methodName, final int uid) {
        Log.d("MC_REDIRECT", "[FuseJavaGate] dispatchFileSystemEvent pkg=" + packageName + " path=" + path + " flags=" + flags);
        // 通过 DataBus 事件队列分发（异步，不影响主流程）。
        // 仅使用 DataBus 路径——不再通过 Binder 同步调用 onFileSystemEvent。
        // FileSystemEventConsumer 通过定时轮询 DataBus 消费事件。
        // 这消除了文件事件热路径对 Server Binder 的强依赖：
        // - Server 不可用时事件不丢失（积压在 DataBus 中）
        // - Server 恢复后通过消费者游标续消费
        try {
            final var event = new JSONObject();
            event.put("schemaVersion", 1);
            event.put("timeMillis", System.currentTimeMillis());
            event.put("packageName", packageName);
            event.put("path", path);
            event.put("flags", flags);
            event.put("sourceLayer", "FUSE_JAVA_GATE");
            event.put("methodName", methodName);
            event.put("uid", uid);
            event.put("policyGeneration", HookPolicyCache.INSTANCE.getRedirectPolicyGeneration());
            event.put("nativeMountPointsGeneration",
                    HookPolicyCache.INSTANCE.getNativeMountPointsGeneration());
            HookDataBusBridge.INSTANCE.writeEvent(DataBus.EVENT_FILESYSTEM, event.toString());
            // 数据面契约 6.4: 写事件后发 signal，消除消费者端 2s 轮询延迟
            HookDataBusBridge.INSTANCE.signal(DataBus.SIGNAL_FILESYSTEM_EVENTS_CHANGED);
        } catch (Exception e) {
            Log.e("MC_REDIRECT", "[FuseJavaGate] DataBus write failed", e);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  工具方法
    // ════════════════════════════════════════════════════════════════

    private String getCallingPackageName(final Object mp, final int uid) {
        final var localCallingIdentity = XposedHelpers.callMethod(
                mp, "getCachedCallingIdentityForFuse", uid);
        return (String) XposedHelpers.callMethod(localCallingIdentity, "getPackageName");
    }

    private String redirectFusePath(final XC_MethodHook.MethodHookParam param, final int pathIndex,
                                    final int uid, final String operation) {
        final var path = (String) param.args[pathIndex];
        if (path == null) {
            return null;
        }
        final String packageName;
        try {
            packageName = getCallingPackageName(param.thisObject, uid);
        } catch (Throwable t) {
            Log.w("MC_REDIRECT", "[FuseJavaGate] " + operation +
                    " cannot resolve package for uid=" + uid + ", path=" + path, t);
            return path;
        }
        final var mountedPath = HookPolicyCache.INSTANCE.getMountedPath(packageName, path);
        if (mountedPath == null || path.equals(mountedPath)) {
            return path;
        }
        param.args[pathIndex] = mountedPath;
        Log.i("MC_REDIRECT", "[FuseJavaGate] " + operation + " redirected: pkg=" +
                packageName + " " + path + " -> " + mountedPath);
        return mountedPath;
    }

    private void redirectMatchingStringPathArgs(final XC_MethodHook.MethodHookParam param,
                                                final String originalPath,
                                                final String mountedPath) {
        if (originalPath == null || mountedPath == null || originalPath.equals(mountedPath)) {
            return;
        }
        for (int i = 0; i < param.args.length; i++) {
            if (originalPath.equals(param.args[i])) {
                param.args[i] = mountedPath;
            }
        }
    }

    private int findCallingUid(final String methodName, final Object[] args) {
        if ("onFileOpenForFuse".equals(methodName) && args.length > 2 &&
                args[2] instanceof Integer) {
            return (int) args[2];
        }
        if ("onFileLookupForFuse".equals(methodName) && args.length > 1 &&
                args[1] instanceof Integer) {
            return (int) args[1];
        }
        return findCallingUid(args);
    }

    private int findCallingUid(final Object[] args) {
        var firstInt = -1;
        for (int i = 1; i < args.length; i++) {
            if (!(args[i] instanceof Integer)) {
                continue;
            }
            final var value = (int) args[i];
            if (firstInt < 0) {
                firstInt = value;
            }
            if (value >= 10000) {
                return value;
            }
        }
        return firstInt;
    }
}
