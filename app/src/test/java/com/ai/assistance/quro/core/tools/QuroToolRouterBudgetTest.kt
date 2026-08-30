package com.ai.assistance.quro.core.tools

import com.ai.assistance.quro.core.QuroToolSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuroToolRouterBudgetTest {
    private fun spec(name: String, description: String = "description-$name") =
        QuroToolSpec(name, description, """{"type":"object","properties":{}}""")

    @Test
    fun `always on set keeps complete P40 operation chain without full catalog`() {
        val required = setOf(
            "launch_app", "read_screen", "find_ui_element", "tap_screen", "input_text", "search_in_app",
            "activate_app_search", "paste_focused_text", "send_message_in_app",
            "screenshot", "visual_analysis", "aci_list", "aci_call",
        )
        assertTrue(QuroToolRouter.ALWAYS_ON.containsAll(required))
        assertTrue(QuroToolRouter.ALWAYS_ON.size <= 24)
        assertFalse(QuroToolRouter.ALWAYS_ON.contains("ai_type_text"))
    }

    @Test
    fun `core registry keeps visual verification tools available for custom drawn apps`() {
        val registry = QuroToolRegistry()
        registry.register(ScreenshotTool())
        registry.register(VisualAnalysisTool())

        val names = registry.coreSpecs().map { it.name }.toSet()
        assertTrue(names.contains("screenshot"))
        assertTrue(names.contains("visual_analysis"))
    }

    @Test
    fun `dynamic schemas are bounded and catalog does not repeat every description`() {
        val specs = QuroToolRouter.ALWAYS_ON.map(::spec) +
            (0 until 20).map { spec("custom_$it", "UNIQUE_VERBOSE_DESCRIPTION_$it") }
        val router = QuroToolRouter(specs)

        (0 until 20).forEach { i ->
            router.handle("tool_router", """{"action":"get_schema","name":"custom_$i"}""")
        }

        val active = router.activeSpecs()
        assertTrue(active.size <= 1 + QuroToolRouter.ALWAYS_ON.size + QuroToolRouter.MAX_LOADED)
        val catalog = active.first { it.name == "tool_router" }.description
        assertFalse(catalog.contains("UNIQUE_VERBOSE_DESCRIPTION_0"))
    }
}
