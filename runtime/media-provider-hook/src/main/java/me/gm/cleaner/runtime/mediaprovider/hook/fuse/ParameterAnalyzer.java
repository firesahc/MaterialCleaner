package me.gm.cleaner.runtime.mediaprovider.hook.fuse;

import java.lang.reflect.Method;
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
    /** uid 参数索引，-1 表示完整签名未命中核验表 */
    final int uidIndex;
    /** open mode 参数索引，-1 表示未知/无需判定 */
    final int modeIndex;
    /** 额外参数角色语义 */
    final ExtraParamRole extraRole;

    ParamRoles(int pathIndex, int path2Index, int uidIndex, int modeIndex, ExtraParamRole extraRole) {
        this.pathIndex = pathIndex;
        this.path2Index = path2Index;
        this.uidIndex = uidIndex;
        this.modeIndex = modeIndex;
        this.extraRole = extraRole;
    }

    @Override
    public String toString() {
        return "ParamRoles{path=" + pathIndex + ", path2=" + path2Index
                + ", uid=" + uidIndex + ", mode=" + modeIndex + ", extra=" + extraRole + "}";
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
 * 根据已核验的方法名与完整参数签名确定参数角色。
 * 未命中已知 UID 签名时保留 uidIndex=-1，由安装门控跳过 UID 依赖的 Hook；
 * 不按“唯一 int”或整数取值扫描猜测 UID。
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

        // 以下映射只接收经过核对的方法名和完整参数形状；额外 int 参数不能改变 UID 位置。
        if (name.equals("isuidallowedaccesstodataorobbpathforfuse") &&
                matches(types, int.class, String.class)) {
            return roles(1, -1, 0, -1, ExtraParamRole.NONE);
        }
        if (name.equals("isdiraccessallowedforfuse") &&
                matches(types, String.class, int.class, int.class)) {
            return roles(0, -1, 1, -1, ExtraParamRole.ACCESS_TYPE_INT);
        }
        if (name.equals("isdirectorycreationordeletionallowedforfuse") &&
                matches(types, String.class, int.class, boolean.class)) {
            return roles(0, -1, 1, -1, ExtraParamRole.ACCESS_TYPE_BOOL);
        }
        if (name.equals("openwithfuse") &&
                matches(types, String.class, int.class, int.class, int.class,
                        boolean.class, boolean.class, int.class)) {
            return roles(0, -1, 1, 3, ExtraParamRole.NONE);
        }
        if (name.equals("renameforfuse") &&
                matches(types, String.class, String.class, int.class)) {
            return roles(0, 1, 2, -1, ExtraParamRole.NONE);
        }
        if (name.equals("insertfileifnecessaryforfuse") &&
                matches(types, String.class, int.class)) {
            return roles(0, -1, 1, -1, ExtraParamRole.NONE);
        }
        if (name.equals("deletefileforfuse")) {
            if (matches(types, String.class, int.class)) {
                return roles(0, -1, 1, -1, ExtraParamRole.NONE);
            }
            if (matches(types, String.class, int.class, int.class)) {
                return roles(0, -1, 1, -1, ExtraParamRole.IGNORE);
            }
        }
        if (name.equals("onfilelookupforfuse")) {
            if (matches(types, String.class, int.class) ||
                    matches(types, String.class, int.class, String.class)) {
                return roles(0, -1, 1, -1, ExtraParamRole.NONE);
            }
            if (matches(types, String.class, String.class, int.class)) {
                return roles(0, -1, 2, -1, ExtraParamRole.NONE);
            }
        }
        if (name.equals("onfileopenforfuse")) {
            if (matches(types, String.class, int.class)) {
                return roles(0, -1, 1, -1, ExtraParamRole.NONE);
            }
            if (matches(types, String.class, String.class, int.class)) {
                return roles(0, -1, 2, -1, ExtraParamRole.NONE);
            }
        }

        // 不在核验表中的变体保留 UID unknown；由 FuseUidGate 阻止安装 UID 依赖 Hook。
        return roles(pathIndex, path2Index, -1, -1, ExtraParamRole.NONE);
    }

    private static ParamRoles roles(
            int pathIndex,
            int path2Index,
            int uidIndex,
            int modeIndex,
            ExtraParamRole extraRole) {
        return new ParamRoles(pathIndex, path2Index, uidIndex, modeIndex, extraRole);
    }

    private static boolean matches(Class<?>[] actual, Class<?>... expected) {
        if (actual.length != expected.length) return false;
        for (int i = 0; i < actual.length; i++) {
            if (actual[i] != expected[i]) return false;
        }
        return true;
    }
}
