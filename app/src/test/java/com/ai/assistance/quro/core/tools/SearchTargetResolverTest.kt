package com.ai.assistance.quro.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTargetResolverTest {
    private fun node(
        text: String = "",
        hint: String = "",
        description: String = "",
        resourceId: String = "",
        className: String = "android.view.View",
        left: Int = 20,
        top: Int = 80,
        right: Int = 1060,
        bottom: Int = 180,
        clickable: Boolean = false,
        editable: Boolean = false,
        enabled: Boolean = true,
    ) = SearchTargetResolver.Node(
        text, hint, description, resourceId, className,
        left, top, right, bottom, clickable, editable, enabled,
    )

    @Test
    fun `search intent accepts search expressions but not ordinary chat request`() {
        assertTrue(SearchTargetResolver.isSearchIntent("搜索栏"))
        assertTrue(SearchTargetResolver.isSearchIntent("search box"))
        assertTrue(SearchTargetResolver.isSearchIntent("放大镜"))
        assertFalse(SearchTargetResolver.isSearchIntent("找张三聊天"))
    }

    @Test
    fun `editable search field ranks ahead of search entry`() {
        val result = SearchTargetResolver.rank(
            listOf(
                node(text = "搜索", clickable = true),
                node(hint = "搜索联系人", className = "android.widget.EditText", editable = true),
            ),
        )
        assertEquals(SearchTargetResolver.Kind.EDITABLE_FIELD, result.first().kind)
    }

    @Test
    fun `resource id searchbar is a search entry`() {
        val result = SearchTargetResolver.rank(
            listOf(node(resourceId = "com.example:id/searchbar", clickable = true)),
        )
        assertEquals(SearchTargetResolver.Kind.SEARCH_ENTRY, result.single().kind)
    }

    @Test
    fun `search hint edit text is editable field`() {
        val result = SearchTargetResolver.rank(
            listOf(node(hint = "搜索商品", className = "android.widget.EditText", editable = true)),
        )
        assertEquals(SearchTargetResolver.Kind.EDITABLE_FIELD, result.single().kind)
    }

    @Test
    fun `unlabelled wide upper field needs geometry fallback`() {
        val candidate = node(className = "android.widget.EditText", editable = true)
        assertTrue(SearchTargetResolver.rank(listOf(candidate), allowGeometryFallback = true).isNotEmpty())
        assertTrue(SearchTargetResolver.rank(listOf(candidate), allowGeometryFallback = false).isEmpty())
    }

    @Test
    fun `bottom chat editor is not a search candidate`() {
        val nodes = listOf(
            node(right = 1080, bottom = 2240),
            node(
                hint = "发送消息",
                resourceId = "com.example:id/chat_composer",
                className = "android.widget.EditText",
                top = 1900,
                bottom = 2020,
                editable = true,
            ),
        )
        assertTrue(SearchTargetResolver.rank(nodes).none { it.node.resourceId.contains("chat_composer") })
    }

    @Test
    fun `disabled and unrelated controls are excluded`() {
        val result = SearchTargetResolver.rank(
            listOf(
                node(text = "搜索", clickable = true, enabled = false),
                node(text = "购物车", clickable = true),
                node(description = "加号", clickable = true),
            ),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `wide top container beats voice search and submit button`() {
        val result = SearchTargetResolver.rank(
            listOf(
                node(
                    className = "android.view.ViewGroup",
                    left = 110,
                    top = 150,
                    right = 820,
                    bottom = 250,
                    clickable = true,
                ),
                node(text = "语音搜索", left = 120, top = 270, right = 430, bottom = 360, clickable = true),
                node(text = "搜索", left = 850, top = 150, right = 1030, bottom = 250, clickable = true),
            ),
        )

        assertEquals(1, result.size)
        assertEquals(110, result.single().node.left)
        assertTrue(result.single().reasons.contains("顶部宽搜索容器兜底"))
    }

    @Test
    fun `voice and image search are never input entries`() {
        val result = SearchTargetResolver.rank(
            listOf(
                node(text = "语音搜索", clickable = true),
                node(description = "图片搜索", clickable = true),
                node(resourceId = "camera_search", clickable = true),
            ),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `multiple editors without locator are ambiguous`() {
        val nodes = listOf(
            node(className = "android.widget.EditText", editable = true),
            node(className = "android.widget.EditText", top = 300, bottom = 400, editable = true),
        )
        val result = SearchTargetResolver.selectEditable(nodes)
        assertEquals(SearchTargetResolver.SelectionStatus.AMBIGUOUS, result.status)
        assertEquals(2, result.candidateIndexes.size)
    }

    @Test
    fun `resource id and coordinates select exact editor`() {
        val nodes = listOf(
            node(resourceId = "com.example:id/message", editable = true),
            node(resourceId = "com.example:id/search_input", top = 300, bottom = 400, editable = true),
        )
        assertEquals(
            1,
            SearchTargetResolver.selectEditable(nodes, targetResourceId = "search_input").index,
        )
        assertEquals(
            1,
            SearchTargetResolver.selectEditable(nodes, targetX = 500, targetY = 350).index,
        )
    }
}
