package com.termkey.ime

internal enum class LlmTextTool(
    val preferenceKey: String,
    val title: String,
    val systemPrompt: String,
    val userInstruction: String,
    val expectsMultipleOptions: Boolean,
) {
    POLISH(
        preferenceKey = "llm_tool_polish",
        title = "润色",
        systemPrompt = "You are a concise bilingual writing assistant. Rewrite the user's text for clarity and fluency while preserving meaning and tone.",
        userInstruction = "Polish the following text. Preserve the original language unless it is clearly mixed. Return exactly 3 distinct options in plain text using this format:\nOption 1: ...\nOption 2: ...\nOption 3: ...",
        expectsMultipleOptions = true,
    ),
    TRANSLATE(
        preferenceKey = "llm_tool_translate",
        title = "翻译",
        systemPrompt = "You are a precise translator between Chinese and English. Detect the source language and translate it to the other language. Return only the translated text.",
        userInstruction = "Translate the following text to the other language between Chinese and English.",
        expectsMultipleOptions = false,
    ),
    SUMMARIZE(
        preferenceKey = "llm_tool_summarize",
        title = "总结",
        systemPrompt = "You are a concise summarizer. Keep the key facts and intent, and return only the summary text.",
        userInstruction = "Summarize the following text in a compact form.",
        expectsMultipleOptions = false,
    ),
    EXPLAIN(
        preferenceKey = "llm_tool_explain",
        title = "解释",
        systemPrompt = "You are a concise explainer. Explain the text in simpler terms while preserving the important details. Return only the explanation text.",
        userInstruction = "Explain the following text clearly and simply.",
        expectsMultipleOptions = false,
    ),
}

internal data class LlmToolSource(
    val kind: Kind,
    val rawText: String,
) {
    enum class Kind {
        SELECTION,
        BEFORE_CURSOR,
    }

    val promptText: String = rawText.trim()
    val displayText: String = abbreviateWhitespace(promptText, 140)
}

internal data class LlmToolRequest(
    val tool: LlmTextTool,
    val source: LlmToolSource,
)

internal enum class LlmPreviewStatus {
    IDLE,
    LOADING,
    SUCCESS,
    ERROR,
    INVALIDATED,
}

internal data class LlmPreviewState(
    val request: LlmToolRequest,
    val status: LlmPreviewStatus,
    val resultOptions: List<String> = emptyList(),
    val selectedResultIndex: Int = 0,
    val errorMessage: String = "",
) {
    val selectedResultText: String
        get() = resultOptions.getOrNull(selectedResultIndex).orEmpty()
}

internal object LlmToolSupport {
    const val MAX_SOURCE_CHARS = 300

    fun resolveSource(
        selectedText: CharSequence?,
        beforeCursorText: CharSequence?,
    ): LlmToolSource? {
        val selected = selectedText?.toString().orEmpty()
        if (selected.isNotBlank()) {
            return LlmToolSource(LlmToolSource.Kind.SELECTION, selected)
        }

        val before = beforeCursorText?.toString().orEmpty()
        if (before.isBlank()) return null
        val normalized = before.takeLast(MAX_SOURCE_CHARS)
        if (normalized.isBlank()) return null
        return LlmToolSource(LlmToolSource.Kind.BEFORE_CURSOR, normalized)
    }

    fun isSourceStillValid(
        source: LlmToolSource,
        selectedText: CharSequence?,
        beforeCursorText: CharSequence?,
    ): Boolean {
        return when (source.kind) {
            LlmToolSource.Kind.SELECTION -> selectedText?.toString() == source.rawText
            LlmToolSource.Kind.BEFORE_CURSOR -> beforeCursorText?.toString()?.endsWith(source.rawText) == true
        }
    }

    fun buildMessages(request: LlmToolRequest): List<OpenAiCompatLlmClient.Message> {
        return listOf(
            OpenAiCompatLlmClient.Message(
                role = "system",
                content = request.tool.systemPrompt,
            ),
            OpenAiCompatLlmClient.Message(
                role = "user",
                content = buildString {
                    append(request.tool.userInstruction)
                    append("\n\n")
                    append(request.source.promptText)
                },
            ),
        )
    }

    fun extractResultOptions(request: LlmToolRequest, responseTexts: List<String>): List<String> {
        val normalized = responseTexts
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (!request.tool.expectsMultipleOptions) {
            return normalized
        }

        val parsed = normalized.flatMap(::splitOptionBlock)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        return if (parsed.isNotEmpty()) parsed else normalized
    }
}

private fun abbreviateWhitespace(text: String, maxChars: Int): String {
    val normalized = text.replace(Regex("\\s+"), " ").trim()
    if (normalized.length <= maxChars) return normalized
    return normalized.take(maxChars - 1).trimEnd() + "…"
}

private fun splitOptionBlock(text: String): List<String> {
    val matches = Regex("""(?im)^\s*Option\s*\d+\s*:\s*(.+?)(?=^\s*Option\s*\d+\s*:|\z)""", RegexOption.DOT_MATCHES_ALL)
        .findAll(text)
        .map { it.groupValues[1].trim() }
        .filter { it.isNotBlank() }
        .toList()
    return matches.ifEmpty { listOf(text.trim()) }
}
