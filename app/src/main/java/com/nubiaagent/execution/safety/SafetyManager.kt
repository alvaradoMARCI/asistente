package com.nubiaagent.execution.safety

import android.content.Context
import android.util.Log
import androidx.room.*
import com.nubiaagent.cognitive.agent.AutonomyProfile
import com.nubiaagent.cognitive.agent.ToolRegistry
import com.nubiaagent.cognitive.agent.ToolRisk
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * # SafetyManager — The Security Guard Layer
 *
 * Middleware that validates **every action** before the ToolExecutor executes it.
 * This is the single chokepoint through which all tool invocations must pass,
 * ensuring that no action — no matter how it was triggered — bypasses safety checks.
 *
 * ## Architecture Position
 *
 * ```
 * AgentLoop → ToolExecutor → SafetyManager.validateAction() → [Execute / Deny / Confirm]
 *                                  │
 *                                  ├── AutonomyProfile check
 *                                  ├── Action classification (READ / WRITE_SAFE / WRITE_DESTRUCTIVE)
 *                                  ├── Rate-limit enforcement
 *                                  ├── Forbidden-action blocklist
 *                                  ├── BiometricGate (destructive actions)
 *                                  └── Audit log → Room DB
 * ```
 *
 * ## Action Classification Rules
 *
 * | Classification     | Cauto      | Balanceado  | Full Auto           |
 * |--------------------|------------|-------------|---------------------|
 * | READ               | Auto       | Auto        | Auto                |
 * | WRITE_SAFE         | Confirm    | Auto        | Auto                |
 * | WRITE_DESTRUCTIVE  | Biometric  | Biometric   | Biometric (always)  |
 * | FORBIDDEN          | Deny       | Deny        | Deny                |
 *
 * ## Rate Limits
 * - **5 destructive actions** per rolling 60-second window
 * - **20 total actions** per rolling 60-second window
 *
 * ## Forbidden Actions
 * Actions that can **never** be executed, regardless of autonomy profile:
 * - `system.factory_reset` — device wipe
 * - `app.uninstall` — removing applications
 * - `system.settings(developer_options, set)` — enabling developer options
 * - `system.settings(usb_debugging, set)` — enabling ADB
 * - `system.settings(oem_unlocking, set)` — enabling bootloader unlock
 * - `system.settings(install_non_market_apps, set)` — allowing unknown sources
 * - `system.wipe_data` — clearing user data
 * - `system.network_reset` — resetting all network config
 *
 * @property context Android context for accessing Room DB and BiometricGate
 * @property biometricGate Optional pre-configured BiometricGate (created internally if null)
 */
class SafetyManager(
    private val context: Context,
    private val biometricGate: BiometricGate = BiometricGate()
) {

    companion object {
        private const val TAG = "NubiaAgent/Safety"

        // ──────────────────────────── Rate Limits ────────────────────────────
        /** Maximum destructive actions allowed in one minute. */
        private const val MAX_DESTRUCTIVE_PER_MINUTE = 5

        /** Maximum total actions allowed in one minute. */
        private const val MAX_ACTIONS_PER_MINUTE = 20

        /** Rolling window duration in milliseconds. */
        private const val RATE_WINDOW_MS = TimeUnit.MINUTES.toMillis(1)

        // ──────────────────────── Forbidden Actions ──────────────────────────
        /**
         * Actions that are **absolutely forbidden** — they can brick the device,
         * expose it to attacks, or cause irreversible data loss.
         *
         * Each entry maps a tool name to an optional parameter pattern that makes
         * it dangerous. If the pattern is `null`, the entire tool is forbidden.
         */
        private val FORBIDDEN_ACTIONS: Map<String, Set<String>?> = mapOf(
            "system.factory_reset" to null,           // Full device wipe
            "system.wipe_data" to null,               // Clear user data partition
            "system.network_reset" to null,           // Reset all network settings
            "app.uninstall" to null,                  // Removing applications
            "system.settings" to setOf(               // Dangerous setting modifications
                "developer_options",
                "usb_debugging",
                "oem_unlocking",
                "install_non_market_apps",
                "adb_enabled",
                "mock_location"
            )
        )
    }

    // ──────────────────────────── Audit Database ─────────────────────────────

    private val auditDao: AuditEntryDao by lazy {
        Room.databaseBuilder(
            context.applicationContext,
            AuditDatabase::class.java,
            "nubia_safety_audit"
        )
            .fallbackToDestructiveMigration()
            .build()
            .auditEntryDao()
    }

    // ──────────────────────────── Rate-Limit State ───────────────────────────

    /**
     * Timestamps of recent destructive actions, used for sliding-window rate limiting.
     * Thread-safe via [ConcurrentLinkedQueue].
     */
    private val destructiveTimestamps = ConcurrentLinkedQueue<Long>()

    /**
     * Timestamps of all recent actions (any classification).
     * Thread-safe via [ConcurrentLinkedQueue].
     */
    private val allActionTimestamps = ConcurrentLinkedQueue<Long>()

    // ═══════════════════════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Validates whether an action may be executed.
     *
     * This is the **single entry point** that the ToolExecutor must call before
     * executing any tool. The decision flow is:
     *
     * 1. **Forbidden check** — Hard deny if the action is on the blocklist.
     * 2. **Rate-limit check** — Deny if the caller has exceeded either rate limit.
     * 3. **Classification** — Determine action type from ToolRegistry.
     * 4. **Autonomy-profile check** — Map classification + profile to decision.
     * 5. **Audit** — Record the decision in the audit log.
     *
     * @param toolName    The registered tool name (e.g. `"sms.send"`, `"screen.read"`).
     * @param params      The parameters that will be passed to the tool, as a [JSONObject].
     * @param autonomyProfile  The current user-selected autonomy level.
     * @return A [SafetyDecision] indicating whether the action is approved, denied,
     *         or requires user confirmation.
     */
    fun validateAction(
        toolName: String,
        params: JSONObject,
        autonomyProfile: AutonomyProfile
    ): SafetyDecision {
        val now = System.currentTimeMillis()
        val paramsStr = params.toString()

        // ── Step 1: Forbidden-action blocklist ──────────────────────────────
        val forbiddenReason = checkForbidden(toolName, params)
        if (forbiddenReason != null) {
            Log.w(TAG, "BLOCKED (forbidden): $toolName — $forbiddenReason")
            val decision = SafetyDecision.Denied(reason = forbiddenReason)
            auditLog(toolName, paramsStr, decision)
            return decision
        }

        // ── Step 2: Rate-limit enforcement ──────────────────────────────────
        val rateLimitReason = checkRateLimits(now)
        if (rateLimitReason != null) {
            Log.w(TAG, "BLOCKED (rate limit): $toolName — $rateLimitReason")
            val decision = SafetyDecision.Denied(reason = rateLimitReason)
            auditLog(toolName, paramsStr, decision)
            return decision
        }

        // ── Step 3: Classify the action ─────────────────────────────────────
        val classification = classifyAction(toolName)

        // ── Step 4: Autonomy-profile decision matrix ────────────────────────
        val decision = when (classification) {
            ActionClassification.READ -> {
                // READ actions are always auto-approved, even in CAUTO
                SafetyDecision.Approved
            }

            ActionClassification.WRITE_SAFE -> when (autonomyProfile) {
                AutonomyProfile.CAUTO -> {
                    // In Cauto mode, even safe writes require simple confirmation
                    SafetyDecision.RequiresConfirmation(
                        confirmType = ConfirmType.USER_DIALOG
                    )
                }
                AutonomyProfile.BALANCEADO,
                AutonomyProfile.FULL_AUTO -> {
                    // Safe writes are auto-approved in Balanced and above
                    SafetyDecision.Approved
                }
            }

            ActionClassification.WRITE_DESTRUCTIVE -> {
                // Destructive actions ALWAYS require biometric confirmation,
                // regardless of autonomy profile. This is non-negotiable.
                SafetyDecision.RequiresConfirmation(
                    confirmType = ConfirmType.BIOMETRIC
                )
            }
        }

        // ── Step 5: Record in audit trail ───────────────────────────────────
        if (decision is SafetyDecision.Approved) {
            recordActionTimestamp(now, classification)
        }
        auditLog(toolName, paramsStr, decision)

        Log.d(
            TAG, "Validation: $toolName → $decision " +
                    "(class=$classification, profile=$autonomyProfile)"
        )

        return decision
    }

    /**
     * Records that a previously-confirmed action was successfully executed.
     *
     * Call this **after** the user confirms a [SafetyDecision.RequiresConfirmation]
     * and the action completes. This updates rate-limit counters retroactively
     * so that confirmed destructive actions count toward the rate limit.
     *
     * @param toolName The tool that was executed.
     */
    fun onActionExecuted(toolName: String) {
        val now = System.currentTimeMillis()
        val classification = classifyAction(toolName)
        recordActionTimestamp(now, classification)
        Log.d(TAG, "Action executed: $toolName (recorded for rate limiting)")
    }

    /**
     * Retrieves recent audit entries for diagnostics or user review.
     *
     * @param limit Maximum number of entries to return (default 50).
     * @return List of [AuditEntry] records, most recent first.
     */
    suspend fun getAuditLog(limit: Int = 50): List<AuditEntry> {
        return auditDao.getRecent(limit)
    }

    /**
     * Returns the current rate-limit status for display in the UI.
     *
     * @return A pair of (destructiveRemaining, totalRemaining) actions
     *         in the current rolling window.
     */
    fun getRateLimitStatus(): Pair<Int, Int> {
        val now = System.currentTimeMillis()
        pruneTimestamps(now)
        val destructiveRemaining = MAX_DESTRUCTIVE_PER_MINUTE - destructiveTimestamps.size
        val totalRemaining = MAX_ACTIONS_PER_MINUTE - allActionTimestamps.size
        return Pair(
            maxOf(0, destructiveRemaining),
            maxOf(0, totalRemaining)
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Internal Logic
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Checks the action against the forbidden-action blocklist.
     *
     * Some tools are entirely forbidden (e.g., `system.factory_reset`),
     * while others are only forbidden with specific parameter values
     * (e.g., `system.settings` with `setting=usb_debugging`).
     *
     * @return A denial reason string if forbidden, or `null` if allowed.
     */
    private fun checkForbidden(toolName: String, params: JSONObject): String? {
        val forbiddenPatterns = FORBIDDEN_ACTIONS[toolName]

        // Tool not in forbidden list — pass
        if (forbiddenPatterns == null && toolName !in FORBIDDEN_ACTIONS) {
            return null
        }

        // Entire tool is forbidden (null value means block all calls)
        if (toolName in FORBIDDEN_ACTIONS && forbiddenPatterns == null) {
            return "Action '$toolName' is permanently forbidden — it could brick or compromise the device."
        }

        // Tool is conditionally forbidden — check specific parameter values
        if (forbiddenPatterns != null) {
            val settingParam = params.optString("setting", "")
            val actionParam = params.optString("action", "")

            // Only block if the action is "set" or "toggle" (read/open is fine)
            if (actionParam in setOf("set", "toggle", "enable") && settingParam in forbiddenPatterns) {
                return "Setting '$settingParam' modification via '$toolName' is forbidden — " +
                        "it could compromise device security."
            }
        }

        return null
    }

    /**
     * Enforces sliding-window rate limits.
     *
     * @param now Current timestamp in milliseconds.
     * @return A denial reason if a rate limit is exceeded, or `null` if OK.
     */
    private fun checkRateLimits(now: Long): String? {
        pruneTimestamps(now)

        if (allActionTimestamps.size >= MAX_ACTIONS_PER_MINUTE) {
            return "Rate limit exceeded: ${MAX_ACTIONS_PER_MINUTE} actions per minute. " +
                    "Please wait before trying again."
        }

        // Note: destructive rate limit is checked at classification time
        // but we pre-emptively check here to catch the case early
        if (destructiveTimestamps.size >= MAX_DESTRUCTIVE_PER_MINUTE) {
            return "Destructive action rate limit exceeded: ${MAX_DESTRUCTIVE_PER_MINUTE} " +
                    "per minute. Please wait before attempting another destructive action."
        }

        return null
    }

    /**
     * Classifies a tool into its risk category using the [ToolRegistry].
     *
     * If the tool is not registered, it defaults to [ActionClassification.WRITE_DESTRUCTIVE]
     * for safety — unknown actions are treated as maximum risk.
     *
     * @param toolName The registered tool name.
     * @return The action's classification.
     */
    private fun classifyAction(toolName: String): ActionClassification {
        val toolDef = ToolRegistry.getTool(toolName)
        return when (toolDef?.risk) {
            ToolRisk.READ -> ActionClassification.READ
            ToolRisk.WRITE_SAFE -> ActionClassification.WRITE_SAFE
            ToolRisk.WRITE_DESTRUCTIVE -> ActionClassification.WRITE_DESTRUCTIVE
            null -> {
                // Unknown tool — treat as destructive for safety
                Log.w(TAG, "Unknown tool '$toolName' — defaulting to WRITE_DESTRUCTIVE")
                ActionClassification.WRITE_DESTRUCTIVE
            }
        }
    }

    /**
     * Records an action timestamp in the rate-limit queues.
     *
     * @param now            Current timestamp.
     * @param classification The action's risk classification.
     */
    private fun recordActionTimestamp(now: Long, classification: ActionClassification) {
        allActionTimestamps.add(now)
        if (classification == ActionClassification.WRITE_DESTRUCTIVE) {
            destructiveTimestamps.add(now)
        }
    }

    /**
     * Removes timestamps older than the rolling window from both queues.
     *
     * @param now Current timestamp in milliseconds.
     */
    private fun pruneTimestamps(now: Long) {
        val cutoff = now - RATE_WINDOW_MS
        while (allActionTimestamps.peek()?.let { it < cutoff } == true) {
            allActionTimestamps.poll()
        }
        while (destructiveTimestamps.peek()?.let { it < cutoff } == true) {
            destructiveTimestamps.poll()
        }
    }

    /**
     * Writes an audit entry to the Room database.
     *
     * This runs on a background thread via Room's async API. Failures are
     * logged but never block the validation pipeline.
     *
     * @param toolName  The tool that was validated.
     * @param params    The JSON parameters as a string.
     * @param decision  The resulting safety decision.
     */
    private fun auditLog(toolName: String, params: String, decision: SafetyDecision) {
        val entry = AuditEntry(
            toolName = toolName,
            params = params.take(500), // Truncate to prevent DB bloat
            decision = when (decision) {
                is SafetyDecision.Approved -> "APPROVED"
                is SafetyDecision.Denied -> "DENIED:${decision.reason}"
                is SafetyDecision.RequiresConfirmation -> "REQUIRES_${decision.confirmType.name}"
            }
        )

        // Fire-and-forget audit write — never block the caller
        Thread {
            try {
                auditDao.insert(entry)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write audit entry", e)
            }
        }.start()
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Safety Decision Types
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Represents the outcome of a safety validation check.
 *
 * The ToolExecutor must inspect this result before executing any action:
 * - [Approved]: Execute immediately.
 * - [Denied]: Do not execute. Inform the user via [reason].
 * - [RequiresConfirmation]: Pause execution and request user confirmation
 *   of the specified [confirmType].
 */
sealed class SafetyDecision {

    /** The action is approved for immediate execution. */
    data object Approved : SafetyDecision()

    /** The action is permanently denied. [reason] explains why. */
    data class Denied(val reason: String) : SafetyDecision()

    /**
     * The action requires explicit user confirmation before execution.
     *
     * The [confirmType] determines what kind of confirmation UI to show:
     * - [ConfirmType.BIOMETRIC]: Fingerprint or face scan required.
     * - [ConfirmType.USER_DIALOG]: Simple yes/no dialog.
     */
    data class RequiresConfirmation(val confirmType: ConfirmType) : SafetyDecision()
}

/**
 * The type of user confirmation required for a [SafetyDecision.RequiresConfirmation].
 */
enum class ConfirmType {
    /** Fingerprint or face biometric verification (with PIN fallback). */
    BIOMETRIC,

    /** Simple user-facing dialog with Approve/Deny buttons. */
    USER_DIALOG,

    /** Device PIN or password verification (non-biometric). */
    PIN
}

/**
 * Internal classification of an action's risk level.
 *
 * Maps directly from [ToolRisk] in the ToolRegistry but is kept as a separate
 * enum to decouple the safety layer from the tool registration layer.
 */
enum class ActionClassification {
    /** Read-only operation. No state change on the device. */
    READ,

    /** Write operation that is reversible or low-impact. */
    WRITE_SAFE,

    /** Write operation that is destructive, irreversible, or high-impact. */
    WRITE_DESTRUCTIVE
}

// ═══════════════════════════════════════════════════════════════════════════════
// Audit Log — Room Entities and DAOs
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * A single audit entry recording a safety validation decision.
 *
 * Every call to [SafetyManager.validateAction] produces one [AuditEntry],
 * regardless of the outcome. This provides a complete forensic trail of
 * every action the agent attempted to take.
 *
 * @property id         Auto-generated primary key.
 * @property timestamp  When the validation occurred (epoch millis).
 * @property toolName   The tool that was validated.
 * @property params     The JSON parameters (truncated to 500 chars).
 * @property decision   The outcome: `APPROVED`, `DENIED:<reason>`, or `REQUIRES_<type>`.
 */
@Entity(tableName = "safety_audit")
data class AuditEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val toolName: String,
    val params: String,
    val decision: String
)

/**
 * Data access object for the safety audit log.
 */
@Dao
interface AuditEntryDao {

    @Query("SELECT * FROM safety_audit ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<AuditEntry>

    @Query("SELECT * FROM safety_audit WHERE toolName = :toolName ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getByTool(toolName: String, limit: Int = 50): List<AuditEntry>

    @Query("SELECT * FROM safety_audit WHERE decision LIKE 'DENIED%' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getDenied(limit: Int = 50): List<AuditEntry>

    @Query("SELECT * FROM safety_audit WHERE timestamp > :since ORDER BY timestamp DESC")
    suspend fun getSince(since: Long): List<AuditEntry>

    @Insert
    fun insert(entry: AuditEntry)

    @Query("DELETE FROM safety_audit WHERE timestamp < :cutoffTimestamp")
    suspend fun deleteOlderThan(cutoffTimestamp: Long): Int

    @Query("SELECT COUNT(*) FROM safety_audit")
    suspend fun count(): Int
}

/**
 * Room database for the safety audit log.
 *
 * Kept separate from [com.nubiaagent.cognitive.memory.db.NubiaDatabase] to
 * isolate concerns — the safety audit is a write-heavy operational log,
 * while the cognitive database is a read-heavy knowledge store.
 */
@Database(
    entities = [AuditEntry::class],
    version = 1,
    exportSchema = true
)
abstract class AuditDatabase : RoomDatabase() {
    abstract fun auditEntryDao(): AuditEntryDao
}
