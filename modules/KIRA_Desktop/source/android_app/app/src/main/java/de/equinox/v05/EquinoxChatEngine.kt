package de.equinox.v05

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

data class EquinoxChatMessage(
    val id: Long,
    val timestampMs: Long,
    val role: ChatRole,
    val text: String,
    val kind: ChatReplyKind? = null,
    val meta: String = "",
    val trace: String = "",
)

class EquinoxChatEngine(
    context: Context,
    private val repository: EquinoxKernelRepository,
) {
    companion object {
        // Canonical terminal identities. Kept here as aliases so UI and engine
        // cannot silently drift apart.
        const val CURATOR_PREFIX = EquinoxTerminalIdentity.CURATOR_PREFIX
        const val KIRA_PREFIX = EquinoxTerminalIdentity.KIRA_PREFIX
    }
    private val dir = File(context.filesDir, "equinox_unified").apply { mkdirs() }
    private val historyFile = File(dir, "chat_history_v1.json")
    // Redundant continuity copy. Android normally preserves filesDir across an APK
    // update; this second copy protects Kira from an accidental primary-file migration
    // or partial write between development builds. Uninstall / Clear storage still removes it.
    private val continuityDir = File(context.filesDir, "kira_continuity").apply { mkdirs() }
    private val historyBackupFile = File(continuityDir, "chat_history_v1.json")
    private val prefs = context.getSharedPreferences("equinox_chat_display", Context.MODE_PRIVATE)
    private val messages = mutableListOf<EquinoxChatMessage>()
    private val sequence = AtomicLong(0L)

    init {
        load()
        // v0.9.2 migration: older builds used persisted chat history itself as recall memory.
        // Import user turns once into the unified memory, then history returns to being UI-only.
        if (repository.textMemorySamples(1).isEmpty()) {
            repository.importLegacyTextTurns(messages.filter { it.role == ChatRole.USER }.map { it.text })
        }
    }

    @Synchronized fun history(): List<EquinoxChatMessage> = messages.toList()

    fun displayMode(): ChatDisplayMode = runCatching {
        ChatDisplayMode.valueOf(prefs.getString("mode", ChatDisplayMode.MATRIX.name) ?: ChatDisplayMode.MATRIX.name)
    }.getOrDefault(ChatDisplayMode.MATRIX)

    fun setDisplayMode(mode: ChatDisplayMode) {
        prefs.edit().putString("mode", mode.name).apply()
    }

    @Synchronized fun clear() {
        messages.clear()
        sequence.set(0L)
        save()
    }

    @Synchronized fun submit(
        input: String,
        contextSignals: List<ChatContextSignal> = emptyList(),
        moduleGeneration: ModuleTextGeneration? = null,
        learnToMind: Boolean = true,
        dialecticState: DialecticDecisionState? = null,
    ): Pair<EquinoxChatMessage, EquinoxChatMessage?> {
        val text = input.trim()
        require(text.isNotEmpty()) { "Leere Eingabe" }
        val now = System.currentTimeMillis()
        val userId = newMessageId(ChatRole.USER, text, now)
        val user = EquinoxChatMessage(userId, now, ChatRole.USER, text)
        val previous = messages.lastOrNull()?.id
        // v0.9.2: conversation history is UI only. Recall and occurrence come from the
        // persistent unified memory profile, so clearing chat does not erase knowledge.
        val priorMemory = repository.textMemorySamples(800)
        val occurrence = repository.textMemoryOccurrence(text).coerceAtLeast(1)
        messages += user
        if (learnToMind) {
            repository.commitChatMessage(user.id, user.role, user.text, previous)
            repository.commitChatContext(user.id, contextSignals)
        }

        val baseMatches = findBaseMatches(text)
        val plan = moduleGeneration?.let { generated ->
            ChatReplyPlan(
                kind = ChatReplyKind.MODULE,
                text = generated.text,
                occurrence = occurrence,
                focusTokens = ChatTextLogic.tokens(text).take(8),
                variant = occurrence + priorMemory.size,
            )
        } ?: ChatTextLogic.planReply(
            input = text,
            occurrence = occurrence,
            prior = priorMemory,
            baseMatches = baseMatches,
        )
        val resolved = ChatTextLogic.resolveReply(text, plan, contextSignals, dialecticState)
        val replyText = resolved.text
        val relationTrace = ChatTextLogic.relationTrace(text, plan, contextSignals, dialecticState)
        val replyNow = System.currentTimeMillis()
        val replyId = newMessageId(ChatRole.EQUINOX, replyText, replyNow)
        val meta = buildString {
            append("kind=").append(resolved.kind.name)
            if (resolved.kind != plan.kind) append(" · sourceKind=").append(plan.kind.name)
            append(" · occurrence=").append(plan.occurrence)
            if (plan.similarity > 0.0) append(" · similarity=").append("%.3f".format(plan.similarity))
            if (plan.relatedTexts.isNotEmpty()) append(" · candidates=").append(plan.relatedTexts.size)
            if (plan.baseMatches.isNotEmpty()) append(" · base=").append(plan.baseMatches.joinToString("|"))
            if (contextSignals.isNotEmpty()) append(" · contextSignals=").append(contextSignals.size)
            dialecticState?.let { d ->
                append(" · decision=").append(if (d.open) "OPEN" else d.winner.name)
                append(" · decisionConfidence=").append("%.3f".format(d.confidence))
            }
            moduleGeneration?.let { generated ->
                append(" · module=").append(generated.module.name)
                append(" · moduleConfidence=").append("%.3f".format(generated.confidence))
                if (generated.meta.isNotBlank()) append(" · moduleMeta=").append(generated.meta)
            }
            append(" · userId=").append(userId)
        }
        val assistant = EquinoxChatMessage(replyId, replyNow, ChatRole.EQUINOX, replyText, resolved.kind, meta, relationTrace)
        messages += assistant
        // Questions and immutable identity protocol are dialogue control, not learned facts.
        // Native wording is a projection of already selected memory, not new evidence.
        // Never feed Kira's own formulation back into the learner or it would self-reinforce.
        if (learnToMind && resolved.kind != ChatReplyKind.QUESTION && resolved.kind != ChatReplyKind.IDENTITY && resolved.kind != ChatReplyKind.NATIVE) {
            repository.commitChatMessage(assistant.id, assistant.role, assistant.text, user.id)
        }
        save()
        return user to assistant
    }

    /**
     * Read-only autonomous surface for live cross-modal output. This is UI/runtime
     * narration only: it is never committed as learned semantic evidence.
     */
    @Synchronized fun emitAutonomously(
        text: String,
        reason: String = "runtime_state",
        kind: ChatReplyKind = ChatReplyKind.NATIVE,
    ): EquinoxChatMessage? {
        val clean = text.trim()
        if (clean.isBlank()) return null
        val now = System.currentTimeMillis()
        val message = EquinoxChatMessage(
            id = newMessageId(ChatRole.EQUINOX, clean, now),
            timestampMs = now,
            role = ChatRole.EQUINOX,
            text = clean,
            kind = kind,
            meta = "kind=${kind.name} · autonomous=true · reason=$reason",
        )
        messages += message
        save()
        return message
    }

    /**
     * Allows non-chat runtimes (DREAM, sensor novelty, future planners) to let Kira
     * initiate a question. The question is persisted in chat history only and is
     * deliberately not fed back into learned semantic memory.
     */
    @Synchronized fun askAutonomously(question: String, reason: String = "open_state"): EquinoxChatMessage? {
        val clean = question.trim()
        if (clean.isBlank()) return null
        val text = if (clean.endsWith("?")) clean else "$clean?"
        val now = System.currentTimeMillis()
        val message = EquinoxChatMessage(
            id = newMessageId(ChatRole.EQUINOX, text, now),
            timestampMs = now,
            role = ChatRole.EQUINOX,
            text = text,
            kind = ChatReplyKind.QUESTION,
            meta = "kind=QUESTION · autonomous=true · reason=$reason",
        )
        messages += message
        save()
        return message
    }

    @Synchronized fun recallHints(input: String, limit: Int = 5): List<String> =
        repository.recallTextMemories(input, limit.coerceIn(1, 12))

    private fun findBaseMatches(text: String): List<String> {
        val candidates = linkedSetOf<String>()
        val trimmed = text.trim()
        if (trimmed.length in 2..80) candidates += trimmed
        ChatTextLogic.tokens(text).filter { it.length >= 3 }.forEach { token ->
            candidates += token
            candidates += token.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }

        // v0.9.8 native BASE package surfaces are the primary lookup. Legacy .eqkernel remains
        // a compatibility source but is no longer required for new Forge packages.
        val out = linkedSetOf<String>()
        candidates.forEach { candidate ->
            repository.baseSurfaceMatches(candidate, 4).forEach { hit ->
                if (hit.canonical.isNotBlank()) out += hit.canonical
            }
        }
        repository.forgeRuntime?.let { runtime ->
            candidates.filter { runtime.surfaceIds(it).isNotEmpty() }.forEach(out::add)
        }
        return out.take(5)
    }

    private fun newMessageId(role: ChatRole, text: String, timestamp: Long): Long {
        val n = sequence.incrementAndGet()
        return EquinoxId64.stableId("chat_message", "${role.name}:$timestamp:$n:${ChatTextLogic.normalize(text)}")
    }

    private fun save() {
        val rows = JSONArray()
        messages.takeLast(400).forEach { m ->
            rows.put(JSONObject()
                .put("id", m.id)
                .put("timestamp_ms", m.timestampMs)
                .put("role", m.role.name)
                .put("text", m.text)
                .put("kind", m.kind?.name ?: JSONObject.NULL)
                .put("meta", m.meta)
                .put("trace", m.trace))
        }
        val payload = JSONObject().put("format", "equinox-chat-history-v1").put("messages", rows).toString()
        historyFile.writeText(payload, Charsets.UTF_8)
        runCatching { historyBackupFile.writeText(payload, Charsets.UTF_8) }
    }

    private fun load() {
        val candidates = listOf(historyFile, historyBackupFile).filter { it.exists() }
        if (candidates.isEmpty()) return
        for (candidate in candidates) {
            val loaded = runCatching {
                val root = JSONObject(candidate.readText())
                require(root.optString("format") == "equinox-chat-history-v1")
                val restored = mutableListOf<EquinoxChatMessage>()
                val rows = root.optJSONArray("messages") ?: JSONArray()
                for (i in 0 until rows.length()) {
                    val r = rows.getJSONObject(i)
                    val kindName = r.optString("kind", "")
                    restored += EquinoxChatMessage(
                        id = r.getLong("id"),
                        timestampMs = r.getLong("timestamp_ms"),
                        role = ChatRole.valueOf(r.getString("role")),
                        text = r.getString("text"),
                        kind = kindName.takeIf { it.isNotBlank() }?.let { ChatReplyKind.valueOf(it) },
                        meta = r.optString("meta", ""),
                        trace = r.optString("trace", ""),
                    )
                }
                restored
            }.getOrNull() ?: continue
            messages.clear()
            messages += loaded
            sequence.set(messages.size.toLong())
            // Heal whichever copy was absent/corrupt without changing semantic data.
            save()
            return
        }
        messages.clear()
        sequence.set(0L)
    }
}
