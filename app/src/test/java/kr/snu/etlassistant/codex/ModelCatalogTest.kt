package kr.snu.etlassistant.codex

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {

    private fun eff(vararg e: String) = JSONArray().apply {
        e.forEach { put(JSONObject().put("reasoningEffort", it).put("description", "$it 설명")) }
    }

    private fun model(
        id: String,
        name: String = id.uppercase(),
        isDefault: Boolean = false,
        hidden: Boolean = false,
        default: String? = "medium",
        efforts: JSONArray? = eff("low", "medium", "high"),
    ) = JSONObject()
        .put("id", id).put("model", id).put("displayName", name).put("description", "$name 설명")
        .put("isDefault", isDefault).put("hidden", hidden)
        .apply {
            if (default != null) put("defaultReasoningEffort", default)
            if (efforts != null) put("supportedReasoningEfforts", efforts)
        }

    private fun result(vararg m: JSONObject) = JSONObject().put("data", JSONArray().apply { m.forEach { put(it) } })

    private fun info(id: String, isDefault: Boolean = false, vararg efforts: String) = ModelInfo(
        id = id, model = id, displayName = id.uppercase(), description = "", isDefault = isDefault, hidden = false,
        defaultEffort = efforts.firstOrNull(), efforts = efforts.map { EffortOption(it, "") },
    )

    // ---- parse ----

    @Test fun parseNormal() {
        val list = ModelCatalog.parse(result(model("a", isDefault = true, default = "high", efforts = eff("medium", "high"))))
        assertEquals(1, list.size)
        val m = list[0]
        assertEquals("a", m.id)
        assertEquals("a", m.model)
        assertEquals("A", m.displayName)
        assertEquals("A 설명", m.description)
        assertTrue(m.isDefault)
        assertEquals("high", m.defaultEffort)
        assertEquals(listOf(EffortOption("medium", "medium 설명"), EffortOption("high", "high 설명")), m.efforts)
    }

    @Test fun parseDropsHidden() {
        val list = ModelCatalog.parse(result(model("a"), model("b", hidden = true), model("c")))
        assertEquals(listOf("a", "c"), list.map { it.id })
    }

    @Test fun parseSkipsBrokenEntries() {
        val noModel = JSONObject().put("id", "x").put("displayName", "X")
        val noName = JSONObject().put("id", "y").put("model", "y")
        val wrongType = JSONObject().put("id", 5).put("model", "z").put("displayName", "Z")
        val data = JSONArray().put(noModel).put(model("ok")).put(noName).put("문자열").put(JSONObject.NULL).put(wrongType)
        val list = ModelCatalog.parse(JSONObject().put("data", data))
        assertEquals(listOf("ok"), list.map { it.id })
    }

    @Test fun parseSkipsBrokenEfforts() {
        val efforts = JSONArray()
            .put(JSONObject().put("reasoningEffort", "low").put("description", "빠름"))
            .put(JSONObject().put("description", "이름 없음"))
            .put(JSONObject().put("reasoningEffort", "").put("description", "빈 이름"))
            .put("엉뚱한 값")
            .put(JSONObject().put("reasoningEffort", "high"))
        val m = ModelCatalog.parse(result(model("a", efforts = efforts))).single()
        assertEquals(listOf(EffortOption("low", "빠름"), EffortOption("high", "")), m.efforts)
    }

    @Test fun parseDefaultFirstKeepingOrder() {
        val list = ModelCatalog.parse(result(model("a"), model("b"), model("c", isDefault = true), model("d")))
        assertEquals(listOf("c", "a", "b", "d"), list.map { it.id })
    }

    @Test fun parseNoEfforts() {
        val none = ModelCatalog.parse(result(model("a", efforts = null, default = null))).single()
        assertTrue(none.efforts.isEmpty())
        assertNull(none.defaultEffort)
        val blank = ModelCatalog.parse(result(model("b", default = ""))).single()
        assertNull(blank.defaultEffort)
    }

    @Test fun parseEmptyOrMissingData() {
        assertTrue(ModelCatalog.parse(JSONObject().put("data", JSONArray())).isEmpty())
        assertTrue(ModelCatalog.parse(JSONObject()).isEmpty())
    }

    // ---- effortLabel ----

    @Test fun effortLabels() {
        assertEquals("끔(가장 빠름)", ModelCatalog.effortLabel("none"))
        assertEquals("최소", ModelCatalog.effortLabel("minimal"))
        assertEquals("낮음", ModelCatalog.effortLabel("low"))
        assertEquals("보통", ModelCatalog.effortLabel("medium"))
        assertEquals("높음", ModelCatalog.effortLabel("high"))
        assertEquals("매우 높음", ModelCatalog.effortLabel("xhigh"))
    }

    @Test fun unknownEffortIsKeptAsIs() {
        assertEquals("ultra", ModelCatalog.effortLabel("ultra"))
        assertEquals("max_plus", ModelCatalog.effortLabel("max_plus"))
    }

    // ---- choiceLabel ----

    private val models = listOf(info("gpt-a", true, "low", "high"), info("gpt-b", false, "medium"))

    @Test fun labelAuto() {
        assertEquals("자동", ModelCatalog.choiceLabel(ModelChoice(), models))
    }

    @Test fun labelModelOnly() {
        assertEquals("GPT-B", ModelCatalog.choiceLabel(ModelChoice("gpt-b"), models))
    }

    @Test fun labelModelAndEffort() {
        assertEquals("GPT-A · 높음", ModelCatalog.choiceLabel(ModelChoice("gpt-a", "high"), models))
    }

    @Test fun labelAutoModelWithEffort() {
        assertEquals("자동 · 낮음", ModelCatalog.choiceLabel(ModelChoice(null, "low"), models))
    }

    @Test fun labelUnknownModelShownAsIs() {
        assertEquals("gpt-old", ModelCatalog.choiceLabel(ModelChoice("gpt-old"), models))
        assertEquals("gpt-old · 보통", ModelCatalog.choiceLabel(ModelChoice("gpt-old", "medium"), emptyList()))
    }

    // ---- sanitize ----

    @Test fun sanitizeKeepsEverythingWhenListEmpty() {
        val c = ModelChoice("whatever", "weird")
        assertEquals(c, ModelCatalog.sanitize(c, emptyList()))
    }

    @Test fun sanitizeDropsUnknownModel() {
        assertEquals(ModelChoice(null, "low"), ModelCatalog.sanitize(ModelChoice("gpt-old", "low"), models))
    }

    @Test fun sanitizeDropsUnsupportedEffort() {
        assertEquals(ModelChoice("gpt-b", null), ModelCatalog.sanitize(ModelChoice("gpt-b", "high"), models))
        assertEquals(ModelChoice("gpt-b", "medium"), ModelCatalog.sanitize(ModelChoice("gpt-b", "medium"), models))
    }

    @Test fun sanitizeChecksEffortAgainstDefaultModelWhenAuto() {
        assertEquals(ModelChoice(null, "high"), ModelCatalog.sanitize(ModelChoice(null, "high"), models))
        assertEquals(ModelChoice(null, null), ModelCatalog.sanitize(ModelChoice(null, "medium"), models))
    }

    @Test fun sanitizeFallsBackToFirstModelWhenNoDefault() {
        val noDefault = listOf(info("x", false, "low"), info("y", false, "high"))
        assertEquals(ModelChoice(null, "low"), ModelCatalog.sanitize(ModelChoice(null, "low"), noDefault))
        assertEquals(ModelChoice(null, null), ModelCatalog.sanitize(ModelChoice(null, "high"), noDefault))
    }

    @Test fun sanitizeUnknownModelThenEffortUsesDefaultModel() {
        // 없는 모델은 자동이 되고, 강도는 기본 모델(gpt-a: low/high) 기준으로 본다
        assertEquals(ModelChoice(null, "high"), ModelCatalog.sanitize(ModelChoice("gpt-old", "high"), models))
        assertEquals(ModelChoice(null, null), ModelCatalog.sanitize(ModelChoice("gpt-old", "medium"), models))
    }
}
