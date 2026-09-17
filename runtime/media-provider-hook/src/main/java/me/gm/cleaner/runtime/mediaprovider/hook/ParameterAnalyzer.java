package me.gm.cleaner.runtime.mediaprovider.hook;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 参数角色 —— 静态分析阶段确定的方法参数职责。
 * <p>
 * 分离"参数在哪"（roles）和"用它做什么"（BehaviorHandler），
 * 使得同一个行为模板可以处理不同参数布局的方法签名。
 * <p>
 * 注：本文件由 FuseJavaGate 抽出（G2 文件粒度门禁），职责不变，
 * 调用方（同包 FuseJavaGate / 单测）无需改引用。
 */
class ParamRoles {
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
enum ExtraParamRole {
    NONE,            // 无额外参数 / 不需要额外处理
    ACCESS_TYPE_INT, // 第三个参数是 int accessType
    ACCESS_TYPE_BOOL,// 第三个参数是 boolean forCreate
    IGNORE           // 有多余参数但忽略（如 HyperOS delete 的第三个 int）
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
 * - (String, String, int) → rename 时 path@0, path2@1, uid@2；非 rename 时 path2 强制 -1
 * - (String, int, int) → path@0, uid@1, extra=ACCESS_TYPE_INT
 * - (String, int, boolean) → path@0, uid@1, extra=ACCESS_TYPE_BOOL
 * <p>
 * Level 3 — uidIndex = -1（运行时由 {@link FuseJavaGate#resolveUid} 值推断）
 */
class ParameterAnalyzer {

    /**
     * 分析方法的参数角色（便捷重载：按方法名自动推导是否需要第二路径）。
     *
     * @param method 反射方法对象
     * @return ParamRoles，或 null 如果无法分析（无 String 参数等）
     */
    static ParamRoles analyze(final Method method) {
        final boolean needPath2 =
                method.getName().toLowerCase(Locale.ROOT).contains("rename");
        return analyze(method, needPath2);
    }

    /**
     * 分析方法的参数角色。
     *
     * @param method    反射方法对象
     * @param needPath2 仅 rename 行为传 true；其余行为即使有两个 String 也不标 path2，
     *                  从源头杜绝语义误标（sanitize 仅做类型级兜底，挡不住语义误标）
     * @return ParamRoles，或 null 如果无法分析（无 String 参数等）
     */
    static ParamRoles analyze(final Method method, final boolean needPath2) {
        final String name = method.getName().toLowerCase(Locale.ROOT);
        final Class<?>[] types = method.getParameterTypes();

        // ── Level 1: 方法名模式识别 ──

        // isUid* 系列：参数反转 (int uid, String path)
        if (name.startsWith("isuid") && types.length >= 2) {
            return new ParamRoles(/*pathIndex=*/1, /*path2Index=*/-1,
                    /*uidIndex=*/0, ExtraParamRole.NONE);
        }

        // ── 路径参数发现 ──
        // 语义约束：仅 rename 需要 path2；非 rename 强制保持 -1，避免把第二个
        // String（如 displayName、volumeName）误标为第二路径。仅 rename 模板
        // 消费 path2，其余 handler 只用 pathIndex，误标虽危害有限但必须求准。
        int pathIndex = -1;
        int path2Index = -1;
        for (int i = 0; i < types.length; i++) {
            if (types[i] == String.class) {
                if (pathIndex < 0) {
                    pathIndex = i;
                } else if (needPath2 && path2Index < 0) {
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
