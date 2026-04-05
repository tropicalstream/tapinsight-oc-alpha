package com.rayneo.visionclaw.core.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class ResearchRouter(
    private val providerProvider: () -> String?,
    private val apiKeyProvider: () -> String?,
    private val modelProvider: () -> String?,
    private val geminiFallbackApiKeyProvider: () -> String? = { null },
    private val context: Context? = null,
    private val timeoutSecondsProvider: () -> Int = { 0 }
) {

    companion object {
        private const val TAG = "ResearchRouter"
        private const val GOOGLE_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val OPENAI_RESPONSES_URL = "https://api.openai.com/v1/responses"
        private const val GROQ_RESPONSES_URL = "https://api.groq.com/openai/v1/responses"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 45_000
        private const val DEFAULT_GEMINI_MODEL = "gemini-3.1-pro-preview"
        private const val DEFAULT_OPENAI_CODEX_MODEL = "gpt-5.2-codex"
        private const val DEFAULT_GROQ_MODEL = "llama-3.3-70b-versatile"
        private const val RESEARCH_SYSTEM_PROMPT =
            "You are a research assistant for RayNeo X3 AR glasses. " +
                "Give a detailed but readable brief with: overview, key facts, practical implications, " +
                "risks or caveats, and next things to explore. " +
                "Avoid code unless the user explicitly asks for it. " +
                "If the topic is time-sensitive, say briefly that you may be relying on model knowledge rather than live browsing."

        fun formatForDisplay(result: ResearchResult.Success): String =
            "[Research model: ${result.model}]\n${result.text.trim()}"
    }

    sealed class ResearchResult {
        data class Success(
            val text: String,
            val provider: String,
            val model: String
        ) : ResearchResult()

        data class Error(val message: String) : ResearchResult()
        object ApiKeyMissing : ResearchResult()
    }

    suspend fun research(topic: String): ResearchResult = withContext(Dispatchers.IO) {
        runPromptInternal(
            prompt = "Research this topic in detail: $topic",
            systemPrompt = RESEARCH_SYSTEM_PROMPT
        )
    }

    suspend fun runPrompt(
        prompt: String,
        systemPrompt: String = RESEARCH_SYSTEM_PROMPT
    ): ResearchResult = withContext(Dispatchers.IO) {
        runPromptInternal(prompt = prompt, systemPrompt = systemPrompt)
    }

    private suspend fun runPromptInternal(
        prompt: String,
        systemPrompt: String
    ): ResearchResult = withContext(Dispatchers.IO) {
        val resolvedProvider = resolveProvider(providerProvider())
        val apiKey = when (resolvedProvider) {
            Provider.GEMINI -> resolveApiKey(apiKeyProvider()) ?: resolveApiKey(geminiFallbackApiKeyProvider())
            Provider.OPENAI_CODEX, Provider.GROQ -> resolveApiKey(apiKeyProvider())
        }

        if (apiKey.isNullOrBlank()) {
            Log.w(TAG, "Missing API key for provider=$resolvedProvider")
            return@withContext ResearchResult.ApiKeyMissing
        }

        val model = resolveModel(resolvedProvider, modelProvider())

        return@withContext try {
            when (resolvedProvider) {
                Provider.GEMINI -> performGeminiResearch(apiKey, model, prompt, systemPrompt)
                Provider.OPENAI_CODEX -> performOpenAiResearch(apiKey, model, prompt, systemPrompt)
                Provider.GROQ -> performGroqResearch(apiKey, model, prompt, systemPrompt)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Research request failed provider=$resolvedProvider model=$model", e)
            ResearchResult.Error(e.localizedMessage ?: "Research request failed")
        }
    }

    private fun performGeminiResearch(
        apiKey: String,
        model: String,
        prompt: String,
        systemPrompt: String
    ): ResearchResult {
        val requestBody = JSONObject()
            .put("systemInstruction", JSONObject().put(
                "parts",
                JSONArray().put(JSONObject().put("text", systemPrompt))
            ))
            .put("contents", JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(
                        JSONObject().put(
                            "text",
                            prompt
                        )
                    ))
            ))
            .put("generationConfig", JSONObject()
                .put("temperature", 0.5)
                .put("maxOutputTokens", 1536)
            )

        var lastError: String? = null
        for (candidate in buildGeminiModelFallbacks(model)) {
            val response = postJson(
                url = "$GOOGLE_BASE_URL/$candidate:generateContent?key=$apiKey",
                requestBody = requestBody,
                headers = mapOf("Content-Type" to "application/json")
            )
            if (response.code in 200..299) {
                val text = extractGeminiText(response.body).ifBlank {
                    throw IllegalStateException("Gemini research returned empty output")
                }
                Log.d(TAG, "Gemini research succeeded model=$candidate chars=${text.length}")
                return ResearchResult.Success(text = text, provider = "gemini", model = candidate)
            }

            lastError = "Gemini research HTTP ${response.code}"
            Log.w(TAG, "Gemini research failed model=$candidate code=${response.code} body=${response.body.take(240)}")
            if (response.code != 404) {
                break
            }
        }

        throw IllegalStateException(lastError ?: "Gemini research unavailable")
    }

    private fun performOpenAiResearch(
        apiKey: String,
        model: String,
        prompt: String,
        systemPrompt: String
    ): ResearchResult {
        return performOpenAiCompatibleResearch(
            apiKey = apiKey,
            model = model,
            prompt = prompt,
            systemPrompt = systemPrompt,
            endpoint = OPENAI_RESPONSES_URL,
            providerLabel = "openai_codex"
        )
    }

    private fun performGroqResearch(
        apiKey: String,
        model: String,
        prompt: String,
        systemPrompt: String
    ): ResearchResult {
        return performOpenAiCompatibleResearch(
            apiKey = apiKey,
            model = model,
            prompt = prompt,
            systemPrompt = systemPrompt,
            endpoint = GROQ_RESPONSES_URL,
            providerLabel = "groq"
        )
    }

    private fun performOpenAiCompatibleResearch(
        apiKey: String,
        model: String,
        prompt: String,
        systemPrompt: String,
        endpoint: String,
        providerLabel: String
    ): ResearchResult {
        val requestBody = JSONObject()
            .put("model", model)
            .put("input", JSONArray()
                .put(JSONObject()
                    .put("role", "system")
                    .put("content", JSONArray().put(
                        JSONObject()
                            .put("type", "input_text")
                            .put("text", systemPrompt)
                    )))
                .put(JSONObject()
                    .put("role", "user")
                    .put("content", JSONArray().put(
                        JSONObject()
                            .put("type", "input_text")
                            .put("text", prompt)
                    )))
            )
            .put("max_output_tokens", 3072)

        val response = postJson(
            url = endpoint,
            requestBody = requestBody,
            headers = mapOf(
                "Content-Type" to "application/json",
                "Authorization" to "Bearer $apiKey"
            )
        )
        if (response.code !in 200..299) {
            throw IllegalStateException("$providerLabel research HTTP ${response.code}")
        }

        val text = extractOpenAiText(response.body).ifBlank {
            throw IllegalStateException("$providerLabel research returned empty output")
        }
        return ResearchResult.Success(text = text, provider = providerLabel, model = model)
    }

    private fun extractGeminiText(body: String): String {
        val root = JSONObject(body)
        val candidates = root.optJSONArray("candidates") ?: return ""
        for (i in 0 until candidates.length()) {
            val candidate = candidates.optJSONObject(i) ?: continue
            val content = candidate.optJSONObject("content") ?: continue
            val parts = content.optJSONArray("parts") ?: continue
            val builder = StringBuilder()
            for (j in 0 until parts.length()) {
                val text = parts.optJSONObject(j)?.optString("text", "").orEmpty().trim()
                if (text.isNotBlank()) {
                    if (builder.isNotEmpty()) builder.append('\n')
                    builder.append(text)
                }
            }
            val value = builder.toString().trim()
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun extractOpenAiText(body: String): String {
        val root = JSONObject(body)
        val topLevel = root.optString("output_text", "").trim()
        if (topLevel.isNotBlank()) return topLevel

        val output = root.optJSONArray("output") ?: return ""
        val builder = StringBuilder()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                val text = part.optString("text", "").trim()
                if (text.isBlank()) continue
                if (builder.isNotEmpty()) builder.append('\n')
                builder.append(text)
            }
        }
        return builder.toString().trim()
    }

    private fun postJson(
        url: String,
        requestBody: JSONObject,
        headers: Map<String, String>
    ): HttpResponse {
        val userTimeout = timeoutSecondsProvider()
        val effectiveReadTimeout = if (userTimeout > 0) userTimeout * 1000 else READ_TIMEOUT_MS
        val response = ActiveNetworkHttp.postJson(
            url = url,
            jsonBody = requestBody.toString(),
            headers = headers,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = effectiveReadTimeout
        )
        return HttpResponse(code = response.code, body = response.body)
    }

    private fun resolveApiKey(raw: String?): String? {
        val key = raw.orEmpty().trim()
        return key.takeIf { it.isNotBlank() }
    }

    private fun resolveModel(provider: Provider, configured: String?): String {
        val value = configured.orEmpty().trim()
        if (value.isNotBlank()) return value
        return when (provider) {
            Provider.GEMINI -> DEFAULT_GEMINI_MODEL
            Provider.OPENAI_CODEX -> DEFAULT_OPENAI_CODEX_MODEL
            Provider.GROQ -> DEFAULT_GROQ_MODEL
        }
    }

    private fun resolveProvider(raw: String?): Provider {
        return when (raw.orEmpty().trim().lowercase()) {
            "openai_codex", "openai-codex", "codex", "openai" -> Provider.OPENAI_CODEX
            "groq" -> Provider.GROQ
            else -> Provider.GEMINI
        }
    }

    private fun buildGeminiModelFallbacks(requested: String): List<String> {
        return listOf(
            requested.trim(),
            DEFAULT_GEMINI_MODEL,
            "gemini-3-flash-preview",
            "gemini-2.5-pro"
        ).filter { it.isNotBlank() }.distinct()
    }

    private enum class Provider {
        GEMINI,
        OPENAI_CODEX,
        GROQ
    }

    private data class HttpResponse(
        val code: Int,
        val body: String
    )
}
