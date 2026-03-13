package com.termkey.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmToolSupportTest {

    @Test
    fun resolveSourcePrefersSelection() {
        val source = LlmToolSupport.resolveSource(
            selectedText = "selected text",
            beforeCursorText = "before cursor",
        )

        assertNotNull(source)
        assertEquals(LlmToolSource.Kind.SELECTION, source?.kind)
        assertEquals("selected text", source?.rawText)
    }

    @Test
    fun resolveSourceFallsBackToBeforeCursorTail() {
        val source = LlmToolSupport.resolveSource(
            selectedText = "",
            beforeCursorText = "abc".repeat(150),
        )

        assertNotNull(source)
        assertEquals(LlmToolSource.Kind.BEFORE_CURSOR, source?.kind)
        assertEquals(LlmToolSupport.MAX_SOURCE_CHARS, source?.rawText?.length)
    }

    @Test
    fun sourceValidationMatchesSelectionExactly() {
        val source = LlmToolSource(LlmToolSource.Kind.SELECTION, "hello")

        assertTrue(LlmToolSupport.isSourceStillValid(source, "hello", "ignored"))
        assertFalse(LlmToolSupport.isSourceStillValid(source, "hello!", "ignored"))
    }

    @Test
    fun sourceValidationMatchesBeforeCursorSuffix() {
        val source = LlmToolSource(LlmToolSource.Kind.BEFORE_CURSOR, "tail")

        assertTrue(LlmToolSupport.isSourceStillValid(source, null, "longer tail"))
        assertFalse(LlmToolSupport.isSourceStillValid(source, null, "tail changed"))
    }

    @Test
    fun messageBuilderIncludesToolInstructionAndSourceText() {
        val request = LlmToolRequest(
            tool = LlmTextTool.SUMMARIZE,
            source = LlmToolSource(LlmToolSource.Kind.SELECTION, "Need a compact summary."),
        )

        val messages = LlmToolSupport.buildMessages(request)

        assertEquals(2, messages.size)
        assertTrue(messages[0].content.contains("summarizer", ignoreCase = true))
        assertTrue(messages[1].content.contains("Need a compact summary."))
    }

    @Test
    fun polishRequestsMultipleChoices() {
        assertTrue(LlmTextTool.POLISH.expectsMultipleOptions)
        assertFalse(LlmTextTool.TRANSLATE.expectsMultipleOptions)
    }

    @Test
    fun extractResultOptionsParsesOptionBlockForPolish() {
        val request = LlmToolRequest(
            tool = LlmTextTool.POLISH,
            source = LlmToolSource(LlmToolSource.Kind.SELECTION, "hello"),
        )

        val options = LlmToolSupport.extractResultOptions(
            request,
            listOf(
                """
                Option 1: First rewrite.
                Option 2: Second rewrite.
                Option 3: Third rewrite.
                """.trimIndent(),
            ),
        )

        assertEquals(listOf("First rewrite.", "Second rewrite.", "Third rewrite."), options)
    }
}
