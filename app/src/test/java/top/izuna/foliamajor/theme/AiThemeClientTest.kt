package top.izuna.foliamajor.theme

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.izuna.foliamajor.online.HttpJson

/**
 * Tests for the request bodies and error formatting.
 *
 * Only the pure parts are covered: the network call itself is exercised on the emulator against a
 * stub server, because mocking OkHttp's dispatcher would test the mock rather than the request the
 * provider actually receives.
 */
class AiThemeClientTest {

    private val client = AiThemeClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val config = AiThemeConfig(
        provider = AiThemeProvider.OPENAI_COMPATIBLE,
        baseUrl = "https://api.example.com/v1/chat/completions",
        model = "test-model",
        apiKey = "sk-test",
    )

    @Test
    fun `openai request carries the system prompt and the source`() {
        val body = json.parseToJsonElement(client.openAiRequest("hello", config)).jsonObject
        val messages = body["messages"]!!.jsonArray

        assertEquals("test-model", body["model"]!!.jsonPrimitive.content)
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertTrue(
            messages[0].jsonObject["content"]!!.jsonPrimitive.content
                .startsWith("Analyze the mood of the provided song source text")
        )
        assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("hello", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `openai request asks for a json object`() {
        val body = json.parseToJsonElement(client.openAiRequest("hello", config)).jsonObject
        assertEquals("json_object", body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `gemini request wraps the prompt in systemInstruction and contents`() {
        val body = json.parseToJsonElement(client.geminiRequest("hello", config)).jsonObject

        val systemText = body["systemInstruction"]!!
            .jsonObject["parts"]!!
            .jsonArray[0]
            .jsonObject["text"]!!
            .jsonPrimitive.content
        assertTrue(systemText.startsWith("Analyze the mood of the provided song source text"))

        val userText = body["contents"]!!
            .jsonArray[0]
            .jsonObject["parts"]!!
            .jsonArray[0]
            .jsonObject["text"]!!
            .jsonPrimitive.content
        assertEquals("hello", userText)
    }

    @Test
    fun `gemini request constrains the response with a schema`() {
        val generationConfig = json.parseToJsonElement(client.geminiRequest("hello", config))
            .jsonObject["generationConfig"]!!
            .jsonObject

        assertEquals("application/json", generationConfig["responseMimeType"]!!.jsonPrimitive.content)

        val schema = generationConfig["responseSchema"]!!.jsonObject
        assertEquals("OBJECT", schema["type"]!!.jsonPrimitive.content)
        assertTrue(schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }.containsAll(listOf("light", "dark")))

        // Both halves need the four palette colours, otherwise a model may omit one and the theme
        // silently collapses to the fallback.
        for (half in listOf("light", "dark")) {
            val required = schema["properties"]!!
                .jsonObject[half]!!
                .jsonObject["required"]!!
                .jsonArray
                .map { it.jsonPrimitive.content }
            assertTrue(half, required.containsAll(listOf("backgroundColor", "primaryColor", "accentColor", "secondaryColor")))
        }
    }

    @Test
    fun `config requires all three fields`() {
        assertTrue(config.isConfigured)
        assertFalse(config.copy(apiKey = "").isConfigured)
        assertFalse(config.copy(baseUrl = "").isConfigured)
        assertFalse(config.copy(model = "  ").isConfigured)
    }

    @Test
    fun `fresh config targets an openai compatible endpoint`() {
        val defaults = AiThemeConfig()
        assertEquals(AiThemeProvider.OPENAI_COMPATIBLE, defaults.provider)
        assertEquals(AiThemeConfig.DEFAULT_OPENAI_URL, defaults.baseUrl)
        assertFalse(defaults.autoGenerate)
        assertFalse(defaults.isConfigured)
    }

    @Test
    fun `error body message is surfaced`() {
        val response = HttpJson.Response(
            401,
            """{"error": {"message": "Incorrect API key provided", "type": "invalid_request_error"}}""",
        )

        assertEquals(
            "AI provider error 401: Incorrect API key provided",
            client.describeFailure("AI provider", response),
        )
    }

    @Test
    fun `string shaped error is surfaced`() {
        val response = HttpJson.Response(429, """{"error": "rate limited"}""")
        assertEquals("Gemini error 429: rate limited", client.describeFailure("Gemini", response))
    }

    @Test
    fun `top level message is used when there is no error object`() {
        val response = HttpJson.Response(500, """{"message": "internal"}""")
        assertEquals("Gemini error 500: internal", client.describeFailure("Gemini", response))
    }

    @Test
    fun `non json error body degrades to the status`() {
        val response = HttpJson.Response(502, "<html>bad gateway</html>")
        assertEquals("Gemini error 502", client.describeFailure("Gemini", response))
    }

    @Test
    fun `response with no body degrades to the status`() {
        assertEquals("Gemini error 500", client.describeFailure("Gemini", HttpJson.Response(500, "")))
    }
}
