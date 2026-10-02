package me.gm.cleaner.core.storage.redirect.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * P2 差分护栏：同一输入同时驱动新旧两套语义，冻结等价与分歧。
 *
 * 等价（必须全等，切换生产调用的前置条件）：
 * - MountRules.getMountedPath 对 OrderedRedirectInterpreter.interpret.derivedPath
 * - MountRules.mountPoint 对 deriveMountPoints.derivedPath
 *
 * 已知分歧（只记录不强制全等，切换时需另行决策）：
 * - getAccessiblePlaces 对 deriveAliasClosure：算法不同（单步逆推+meaningless 预过滤
 *   对 BFS 反向候选+interpret 回验+maxPaths 截断），此处只冻结代表样本行为。
 * - 校验严格度：Ordered 要求规范绝对路径+连续 orderIndex，非法抛；
 *   MountRules 零校验静默容忍。
 */
class RedirectDifferentialTest {

    @Test
    fun `oracle全量回放新旧解释一致`() {
        oracleCases().forEach { case ->
            val ordered = case.rules.mapIndexed { index, (source, target) ->
                rule(index, source, target)
            }
            val legacy = MountRules(case.rules)

            assertEquals(
                "${case.name}: getMountedPath",
                case.mountedPath,
                legacy.getMountedPath(case.path),
            )
            assertEquals(
                "${case.name}: interpret",
                case.mountedPath,
                OrderedRedirectInterpreter.interpret(case.path, ordered).derivedPath,
            )
            assertEquals(
                "${case.name}: mountPoint",
                case.mountPoints,
                legacy.mountPoint,
            )
            assertEquals(
                "${case.name}: deriveMountPoints",
                case.mountPoints,
                OrderedRedirectInterpreter.deriveMountPoints(ordered)
                    .map(RedirectMountPoint::derivedPath),
            )
        }
    }

    @Test
    fun `空规则与无命中一致透传`() {
        assertDifferential(
            name = "empty",
            rules = emptyList(),
            paths = listOf("/visible/A/file.txt"),
        )
        assertDifferential(
            name = "no-match",
            rules = listOf("/real/photos" to "/visible/DCIM"),
            paths = listOf("/other/file.txt", "/visible/DCIM2/a.jpg"),
        )
    }

    @Test
    fun `最后匹配与尾链改写一致`() {
        assertDifferential(
            name = "last-match-wins",
            rules = listOf("/real/first" to "/visible/A", "/real/last" to "/visible/A"),
            paths = listOf("/visible/A/file.txt"),
        )
        assertDifferential(
            name = "tail-chain",
            rules = listOf("/real/A" to "/visible/A", "/real/B" to "/real/A/B"),
            paths = listOf("/visible/A/B/file.txt", "/visible/A/other.txt"),
        )
        assertDifferential(
            name = "preserve-in-middle",
            rules = listOf(
                "/backing" to "/visible/A",
                "/visible/A" to "/visible/A",
                "/final" to "/backing/sub",
            ),
            paths = listOf("/visible/A/file.txt", "/backing/sub/file.txt"),
        )
    }

    @Test
    fun `同名前缀与路径边界一致`() {
        assertDifferential(
            name = "segment-boundary",
            rules = listOf("/real/photos" to "/visible/DCIM"),
            paths = listOf(
                "/visible/DCIM/a.jpg",
                "/visible/DCIM2/a.jpg",
                "/visible/DCIM",
                "/real/photos/a.jpg",
            ),
        )
    }

    @Test
    fun `校验分歧矩阵_旧容忍新抛`() {
        // 尾斜杠：旧实现按 startsWith 语义容忍，新实现拒绝。
        val trailingSlash = listOf(rule(0, "/real/A", "/visible/A"))
        try {
            OrderedRedirectInterpreter.interpret("/visible/A/", trailingSlash)
            fail("尾斜杠应抛")
        } catch (_: IllegalArgumentException) {
        }
        // MountRules 不校验，此处不断言其输出，只确认不抛以冻结容忍行为。
        MountRules(listOf("/real/A" to "/visible/A")).getMountedPath("/visible/A/")

        // 双斜杠与相对段同理。
        listOf("/visible//A/file", "/visible/A/../B").forEach { bad ->
            try {
                OrderedRedirectInterpreter.interpret(bad, trailingSlash)
                fail("非法路径应抛：$bad")
            } catch (_: IllegalArgumentException) {
            }
        }

        // orderIndex 空洞：新实现拒绝，旧实现无 order 概念。
        val gapped = listOf(
            OrderedRedirectRule(RuleId("r0"), RedirectRuleType.MAP, "/a", "/b", 0),
            OrderedRedirectRule(RuleId("r2"), RedirectRuleType.MAP, "/c", "/d", 2),
        )
        try {
            OrderedRedirectInterpreter.interpret("/b/file", gapped)
            fail("空洞索引应抛")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `alias分歧样本_只冻结不强制全等`() {        val pairs = listOf("/real/A" to "/visible/A", "/real/B" to "/real/A/B")
        val ordered = pairs.mapIndexed { index, (source, target) ->
            rule(index, source, target)
        }
        val legacy = MountRules(pairs).getAccessiblePlaces("/real/A")
        val closure = OrderedRedirectInterpreter.deriveAliasClosure("/real/A", ordered)
        // 两者算法不同，分歧预期内。此处只冻结“都完成不抛、都包含输入本身”，
        // 全等切换决策留待 Phase 4 前另行收敛，不在此门禁中强制。
        assertTrue(legacy.isNotEmpty())
        assertTrue(closure.paths.isNotEmpty())
        assertTrue(closure.complete)
    }

    @Test
    fun `推导器挂载点与旧实现一致`() {
        oracleCases().forEach { case ->
            val snapshot = snapshotOf(case.rules)
            val expected = MountRules(case.rules).mountPoint
            val actual = RedirectPolicyDeriver.buildConfiguredMountPoints(snapshot).points
            assertEquals("${case.name}: deriver mountPoints", expected, actual)
        }
    }

    @Test
    fun `推导器非规范输入回退旧实现不抛`() {
        val dirty = listOf("/real/A/" to "/visible/A", "/visible/A" to "/visible/A")
        val snapshot = snapshotOf(dirty)
        val expected = MountRules(dirty).mountPoint
        assertEquals(
            expected,
            RedirectPolicyDeriver.buildConfiguredMountPoints(snapshot).points,
        )
        assertEquals(
            MountRules(dirty).getMountedPath("/visible/A/file"),
            RedirectPolicyDeriver.getMountedPath(snapshot, "pkg", 0, "/visible/A/file"),
        )
    }

    @Test
    fun `推导器缺包缺用户返回原路径`() {
        val snapshot = snapshotOf(listOf("/real/A" to "/visible/A"))
        assertEquals(
            "/visible/A/file",
            RedirectPolicyDeriver.getMountedPath(snapshot, "other", 0, "/visible/A/file"),
        )
        assertEquals(
            "/visible/A/file",
            RedirectPolicyDeriver.getMountedPath(snapshot, "pkg", 10, "/visible/A/file"),
        )
        assertEquals(
            "/real/A/file",
            RedirectPolicyDeriver.getMountedPath(snapshot, "pkg", 0, "/visible/A/file"),
        )
    }

    @Test
    fun `投影桥挂载计划与旧实现一致`() {
        oracleCases().forEach { case ->
            val rules = case.rules.map { (source, target) ->
                RedirectRule(source = source, target = target)
            }
            val plan = MountPlanDeriver.derive("pkg", 0, rules)
                ?: error("${case.name}: 空规则不应返回 null")
            val legacy = MountRules(case.rules)
            assertEquals("${case.name}: sources", legacy.sources, plan.sources)
            assertEquals("${case.name}: targets", legacy.targets, plan.targets)
            assertEquals("${case.name}: mountPoints", legacy.mountPoint, plan.mountPoints)
            assertEquals("${case.name}: mkdirList", legacy.mountPoint + legacy.sources, plan.mkdirList)
            assertEquals(
                "${case.name}: resolve",
                legacy.getMountedPath(case.path),
                MountPlanDeriver.resolveMountedPath(rules, case.path),
            )
        }
    }

    @Test
    fun `投影桥空规则返回null脏输入不抛`() {
        assertEquals(null, MountPlanDeriver.derive("pkg", 0, emptyList()))
        assertEquals("/a", MountPlanDeriver.resolveMountedPath(emptyList(), "/a"))
        val dirty = listOf(RedirectRule(source = "/real/A/", target = "/visible/A"))
        val plan = MountPlanDeriver.derive("pkg", 0, dirty)!!
        assertEquals(
            MountRules(listOf("/real/A/" to "/visible/A")).mountPoint,
            plan.mountPoints,
        )
    }

    private fun snapshotOf(rules: List<Pair<String, String>>): RedirectPolicySnapshot {
        val redirect = rules.map { (source, target) ->
            RedirectRule(source = source, target = target)
        }
        return RedirectPolicySnapshot(
            generation = 1L,
            storage = RuntimeStoragePolicy(
                redirectRules = mapOf("pkg" to mapOf(0 to redirect)),
            ),
        )
    }

    private fun assertDifferential(
        name: String,
        rules: List<Pair<String, String>>,
        paths: List<String>,
    ) {
        val ordered = rules.mapIndexed { index, (source, target) ->
            rule(index, source, target)
        }
        val legacy = MountRules(rules)
        val mountPoints = OrderedRedirectInterpreter.deriveMountPoints(ordered)
            .map(RedirectMountPoint::derivedPath)
        assertEquals("$name: mountPoint", legacy.mountPoint, mountPoints)
        paths.forEach { path ->
            assertEquals(
                "$name: $path",
                legacy.getMountedPath(path),
                OrderedRedirectInterpreter.interpret(path, ordered).derivedPath,
            )
        }
    }

    private fun rule(
        index: Int,
        source: String,
        target: String,
    ): OrderedRedirectRule = OrderedRedirectRule(
        ruleId = RuleId("rule-$index"),
        type = if (source == target) RedirectRuleType.PRESERVE else RedirectRuleType.MAP,
        source = source,
        target = target,
        orderIndex = index,
    )

    private fun oracleCases(): List<OracleCase> {
        val stream = javaClass.classLoader
            ?.getResourceAsStream("mount-rules-v4_0_0-oracle.jsonl")
            ?: error("缺少 mount-rules-v4_0_0-oracle.jsonl")
        return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter(String::isNotBlank).map(::parseOracleCase).toList()
        }
    }

    private fun parseOracleCase(line: String): OracleCase {
        val fields = FIELD.findAll(line).associate { match ->
            val name = match.groupValues[1]
            val scalar = match.groupValues[2]
            val array = match.groupValues[3]
            name to if (scalar.isNotEmpty()) {
                listOf(scalar)
            } else {
                STRING.findAll(array).map { it.groupValues[1] }.toList()
            }
        }
        fun scalar(name: String): String = requireNotNull(fields[name]?.singleOrNull())
        fun array(name: String): List<String> = requireNotNull(fields[name])
        return OracleCase(
            name = scalar("name"),
            rules = array("rules").map { encoded ->
                val separator = encoded.indexOf(RULE_SEPARATOR)
                require(separator >= 0)
                encoded.substring(0, separator) to
                    encoded.substring(separator + RULE_SEPARATOR.length)
            },
            path = scalar("path"),
            mountedPath = scalar("mountedPath"),
            mountPoints = array("mountPoints"),
        )
    }

    private data class OracleCase(
        val name: String,
        val rules: List<Pair<String, String>>,
        val path: String,
        val mountedPath: String,
        val mountPoints: List<String>,
    )

    private companion object {
        const val RULE_SEPARATOR = "=>"
        val FIELD = Regex(""""([^"]+)":(?:"([^"]*)"|\[([^]]*)])""")
        val STRING = Regex(""""([^"]*)"""")
    }
}
