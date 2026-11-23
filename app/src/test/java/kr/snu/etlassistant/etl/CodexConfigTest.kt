package kr.snu.etlassistant.etl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexConfigTest {

    private fun table(toml: String, name: String): List<String> =
        toml.lines().dropWhile { it.trim() != "[$name]" }.drop(1).takeWhile { !it.startsWith("[") }
            .map { it.trim() }.filter { it.isNotEmpty() }

    @Test fun analyticsFeedbackAndMetricsOff() {
        val t = CodexConfig.toml(null)
        assertTrue(table(t, "analytics").contains("enabled = false"))
        assertTrue(table(t, "feedback").contains("enabled = false"))
        assertTrue(table(t, "otel").contains("metrics_exporter = \"none\""))
        assertTrue(table(t, "features").contains("apps = false"))
    }

    @Test fun mcpOnlyWithPort() {
        assertFalse(CodexConfig.toml(null).contains("[mcp_servers.etl]"))
        val t = CodexConfig.toml(4321)
        assertTrue(table(t, "mcp_servers.etl").contains("url = \"http://127.0.0.1:4321/mcp\""))
        assertTrue(table(t, "mcp_servers.etl").contains("bearer_token_env_var = \"${CodexConfig.SECRET_ENV}\""))
    }

    @Test fun eachTableOnce() {
        val headers = CodexConfig.toml(1).lines().filter { it.startsWith("[") }
        assertEquals(headers.size, headers.toSet().size)
    }
}
