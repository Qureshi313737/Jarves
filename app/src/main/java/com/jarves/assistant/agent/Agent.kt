package com.jarves.assistant.agent

import android.content.Context
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.errors.RateLimitException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.WebFetchTool20250910
import com.anthropic.models.messages.WebFetchTool20260209
import com.anthropic.models.messages.WebSearchTool20250305
import com.anthropic.models.messages.WebSearchTool20260209
import com.jarves.assistant.Prefs
import com.jarves.assistant.tools.ToolOutput
import com.jarves.assistant.tools.ToolRegistry
import org.json.JSONObject
import java.time.Duration

/**
 * The "brain": a Claude tool-use loop. Each user command can take many steps
 * (search the web, open apps, read the screen, tap, type, download…) until the job is done.
 */
class Agent(private val ctx: Context, private val prefs: Prefs) {

    interface Callbacks {
        fun onText(text: String)
        fun onStep(text: String)
    }

    private val history = ArrayList<MessageParam>()
    private val tools = ToolRegistry(ctx)
    @Volatile private var cancelled = false
    @Volatile private var resetRequested = false
    private var lastActivity = 0L
    private var client: AnthropicClient? = null
    private var clientKey = ""
    private var useFallbacks = true

    fun cancel() { cancelled = true }

    fun reset() {
        cancelled = true
        resetRequested = true
    }

    /** Runs one user command to completion and returns the final reply text. Blocking. */
    fun run(userText: String, cb: Callbacks): String {
        cancelled = false
        val now = System.currentTimeMillis()
        if (resetRequested || now - lastActivity > IDLE_RESET_MS || history.size > MAX_HISTORY) {
            history.clear()
            resetRequested = false
        }
        val startSize = history.size
        history.add(MessageParam.ofUser(tools.contextHeader() + userText))
        var steps = 0
        try {
            while (true) {
                if (cancelled) return "Theek hai, maine kaam rok diya."
                if (++steps > MAX_STEPS) {
                    return "Is kaam mein bahut zyada steps lag gaye, isliye maine yahin rok diya. Aap thoda alag tarike se bata sakte hain?"
                }
                val resp: Message = try {
                    call()
                } catch (e: Exception) {
                    // Keep the history consistent: if the very first call failed, drop the command.
                    if (steps == 1) while (history.size > startSize) history.removeAt(history.size - 1)
                    else history.clear()
                    throw RuntimeException(friendlyError(e), e)
                }
                history.add(resp.toParam())

                val texts = ArrayList<String>()
                val toolUses = ArrayList<com.anthropic.models.messages.ToolUseBlock>()
                for (block in resp.content()) {
                    block.text().ifPresent { if (it.text().isNotBlank()) texts.add(it.text()) }
                    block.toolUse().ifPresent { toolUses.add(it) }
                    block.serverToolUse().ifPresent { stu ->
                        cb.onStep("🌐 ${stu.name()}: ${shortJson(stu._input())}")
                    }
                }
                val text = texts.joinToString("\n").trim()

                when (resp.stopReason().orElse(null)) {
                    StopReason.TOOL_USE -> {
                        if (toolUses.isEmpty()) return text
                        if (text.isNotEmpty()) cb.onStep("💬 $text")
                        val results = ArrayList<ContentBlockParam>()
                        for (tu in toolUses) {
                            val input = parseInput(tu._input())
                            val out: ToolOutput = if (cancelled) {
                                ToolOutput.error("Cancelled by the user.")
                            } else {
                                cb.onStep(tools.describe(tu.name(), input))
                                try {
                                    tools.execute(tu.name(), input)
                                } catch (e: Throwable) {
                                    ToolOutput.error("Tool failed: ${e.javaClass.simpleName}: ${e.message}")
                                }
                            }
                            results.add(ContentBlockParam.ofToolResult(toResultBlock(tu.id(), out)))
                        }
                        // Always answer every tool_use before stopping, so history stays valid.
                        history.add(MessageParam.ofUserOfContentBlockParams(results))
                    }
                    StopReason.PAUSE_TURN -> { /* server tool still working: send again to continue */ }
                    StopReason.REFUSAL -> {
                        history.clear()
                        return "Maaf kijiye, main is request mein madad nahi kar sakta."
                    }
                    StopReason.MAX_TOKENS -> return if (text.isNotEmpty()) "$text…" else "Jawab bahut lamba ho gaya, kripya chhota sawaal poochein."
                    else -> {
                        cb.onText(text)
                        return text.ifEmpty { "Ho gaya." }
                    }
                }
            }
        } finally {
            lastActivity = System.currentTimeMillis()
            if (resetRequested) { history.clear(); resetRequested = false }
        }
    }

    private fun call(): Message {
        val c = client()
        return try {
            c.messages().create(buildParams(useFallbacks))
        } catch (e: BadRequestException) {
            if (useFallbacks && (e.message ?: "").contains("fallback", ignoreCase = true)) {
                useFallbacks = false
                c.messages().create(buildParams(false))
            } else throw e
        }
    }

    private fun buildParams(withFallbacks: Boolean): MessageCreateParams {
        val model = prefs.model
        val effort = when (prefs.effort) {
            "low" -> OutputConfig.Effort.LOW
            "high" -> OutputConfig.Effort.HIGH
            else -> OutputConfig.Effort.MEDIUM
        }
        val b = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .system(SystemPrompt.build(prefs))
            .messages(ArrayList(history))
            .thinking(ThinkingConfigAdaptive.builder().build())
            .outputConfig(OutputConfig.builder().effort(effort).build())
            // Automatic prompt caching: the long system prompt + tools + earlier steps are re-used cheaply.
            .cacheControl(CacheControlEphemeral.builder().build())

        for (t in tools.definitions()) b.addTool(t)
        val newWebTools = !model.startsWith("claude-haiku")
        if (newWebTools) {
            b.addTool(WebSearchTool20260209.builder().maxUses(8L).build())
            b.addTool(WebFetchTool20260209.builder().maxUses(8L).build())
        } else {
            b.addTool(WebSearchTool20250305.builder().maxUses(8L).build())
            b.addTool(WebFetchTool20250910.builder().maxUses(8L).build())
        }
        // If a safety classifier declines, let the API retry on a suitable fallback model.
        if (withFallbacks && (model == "claude-opus-5-5" || model == "claude-sonnet-5-5")) {
            b.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        return b.build()
    }

    private fun client(): AnthropicClient {
        val key = prefs.apiKey
        val existing = client
        if (existing != null && key == clientKey) return existing
        val c = AnthropicOkHttpClient.builder()
            .apiKey(key)
            .timeout(Duration.ofMinutes(5))
            .maxRetries(3)
            .build()
        client = c
        clientKey = key
        return c
    }

    private fun toResultBlock(id: String, out: ToolOutput): ToolResultBlockParam {
        val b = ToolResultBlockParam.builder().toolUseId(id)
        val img = out.imageJpegBase64
        if (img != null) {
            b.contentOfBlocks(
                listOf(
                    ToolResultBlockParam.Content.Block.ofText(out.text.ifEmpty { "(image)" }),
                    ToolResultBlockParam.Content.Block.ofImage(
                        Base64ImageSource.builder()
                            .data(img)
                            .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                            .build()
                    ),
                )
            )
        } else {
            b.content(out.text.ifEmpty { "OK" })
        }
        if (out.isError) b.isError(true)
        return b.build()
    }

    private fun parseInput(v: JsonValue): JSONObject = try {
        @Suppress("UNCHECKED_CAST")
        JSONObject(v.convert(Map::class.java) as Map<String, Any?>)
    } catch (e: Exception) {
        JSONObject()
    }

    private fun shortJson(v: JsonValue): String {
        val o = parseInput(v)
        val s = o.optString("query").ifEmpty { o.optString("url") }.ifEmpty { o.toString() }
        return if (s.length > 80) s.take(80) + "…" else s
    }

    private fun friendlyError(e: Exception): String = when (e) {
        is UnauthorizedException -> "API key galat hai. Settings mein sahi Anthropic API key daalein."
        is RateLimitException -> "Bahut zyada requests ho gayi (rate limit). Thodi der baad try karein."
        is AnthropicServiceException -> "Claude API error (${e.statusCode()}): ${e.message}"
        else -> "Internet/connection problem: ${e.message ?: e.javaClass.simpleName}"
    }

    companion object {
        const val MAX_STEPS = 60
        const val MAX_HISTORY = 160
        const val IDLE_RESET_MS = 30 * 60 * 1000L
    }
}
