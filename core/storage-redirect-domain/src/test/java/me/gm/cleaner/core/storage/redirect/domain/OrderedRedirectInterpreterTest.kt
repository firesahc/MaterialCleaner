package me.gm.cleaner.core.storage.redirect.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderedRedirectInterpreterTest {

    @Test
    fun `解释结果与正式 v4_0_0 oracle逐项一致`() {
        val cases = javaClass.classLoader
            ?.getResourceAsStream("mount-rules-v4_0_0-oracle.jsonl")
            ?.bufferedReader(Charsets.UTF_8)
            ?.useLines { lines ->
                lines.filter(String::isNotBlank).map(::parseOracleCase).toList()
            }
            ?: error("缺少 mount-rules-v4_0_0-oracle.jsonl")

        cases.forEach { case ->
            val rules = case.rules.mapIndexed { index, (source, target) ->
                rule(index, source, target)
            }
            assertEquals(
                "${case.name}: getMountedPath",
                case.mountedPath,
                OrderedRedirectInterpreter.interpret(case.path, rules).derivedPath,
            )
            assertEquals(
                "${case.name}: mountPoint",
                case.mountPoints,
                OrderedRedirectInterpreter.deriveMountPoints(rules)
                    .map(RedirectMountPoint::derivedPath),
            )
        }
    }

    @Test
    fun `最后匹配基于原始输入且只向尾部链式执行`() {
        val rules = listOf(
            rule(0, "/real/nested", "/visible/A/B"),
            rule(1, "/real/root", "/visible/A"),
            rule(2, "/final", "/real/root/B"),
        )

        val result = OrderedRedirectInterpreter.interpret("/visible/A/B/file", rules)

        assertEquals("/final/file", result.derivedPath)
        assertEquals(listOf(RuleId("rule-1"), RuleId("rule-2")), result.matchedRuleIds)
        assertEquals(
            listOf("/visible/A/B/file", "/real/root/B/file", "/final/file"),
            result.reachableAliases,
        )
    }

    @Test
    fun `PRESERVE保留恒等Pair并参与最后匹配`() {
        val rules = listOf(
            rule(0, "/backing", "/visible/A"),
            rule(1, "/visible/A", "/visible/A"),
        )

        val result = OrderedRedirectInterpreter.interpret("/visible/A/file", rules)

        assertEquals("/visible/A/file", result.derivedPath)
        assertEquals(listOf(RuleId("rule-1")), result.matchedRuleIds)
        assertEquals(listOf("/visible/A/file"), result.reachableAliases)
    }

    @Test
    fun `同名前缀不构成路径命中`() {
        val result = OrderedRedirectInterpreter.interpret(
            "/visible/AB/file",
            listOf(rule(0, "/backing", "/visible/A")),
        )

        assertEquals("/visible/AB/file", result.derivedPath)
        assertTrue(result.matchedRuleIds.isEmpty())
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
