package com.termkey.ime

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatLlmClientTest {

    @Test
    fun buildRequestJsonUsesModelAndNonStreamingMessages() {
        val json = OpenAiCompatLlmClient.buildRequestJson(
            config = OpenAiCompatLlmClient.Config(
                baseUrl = "https://api.openai.com/v1",
                apiKey = "secret",
                model = "gpt-4.1-mini",
            ),
            messages = listOf(
                OpenAiCompatLlmClient.Message("system", "sys"),
                OpenAiCompatLlmClient.Message("user", "hello"),
            ),
        )

        val root = JsonParser.parseString(json).asJsonObject
        assertEquals("gpt-4.1-mini", root.get("model").asString)
        assertTrue(!root.get("stream").asBoolean)
        assertEquals(2, root.getAsJsonArray("messages").size())
    }

    @Test
    fun parseResponseTextsHandlesStringContent() {
        val response = """
            {
              "choices": [
                {
                  "message": {
                    "content": "rewritten text"
                  }
                }
              ]
            }
        """.trimIndent()

        assertEquals(listOf("rewritten text"), OpenAiCompatLlmClient.parseResponseTexts(response))
    }

    @Test
    fun parseResponseTextsHandlesPartArrayContent() {
        val response = """
            {
              "choices": [
                {
                  "message": {
                    "content": [
                      { "type": "output_text", "text": "hello " },
                      { "type": "output_text", "text": "world" }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        assertEquals(listOf("hello world"), OpenAiCompatLlmClient.parseResponseTexts(response))
    }

    @Test
    fun parseResponseTextsReturnsMultipleChoices() {
        val response = """
            {
              "choices": [
                { "message": { "content": "option one" } },
                { "message": { "content": "option two" } },
                { "message": { "content": "option three" } }
              ]
            }
        """.trimIndent()

        assertEquals(
            listOf("option one", "option two", "option three"),
            OpenAiCompatLlmClient.parseResponseTexts(response),
        )
    }

    @Test
    fun parseErrorMessagePrefersServerMessage() {
        val body = """
            {
              "error": {
                "message": "bad key"
              }
            }
        """.trimIndent()

        assertEquals("LLM request failed: bad key", OpenAiCompatLlmClient.parseErrorMessage(body, 401))
    }
}
