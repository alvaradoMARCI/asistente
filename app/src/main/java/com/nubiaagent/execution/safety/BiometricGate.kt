package com.nubiaagent.execution.safety

import android.app.Activity
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * # BiometricGate — Fingerprint & Face Verification for Destructive Actions
 *
 * A gatekeeper that requires biometric authentication (fingerprint or face)
 * before the agent can execute destructive actions. This is the human-in-the-loop
 * safeguard that prevents autonomous AI from performing irreversible operations
 * without explicit, verified user consent.
 *
 * ## How It Works
 *
 * ```
 * SafetyManager.RequiresConfirmation(BIOMETRIC)
 *     │
 *     ▼
 * BiometricGate.requestConfirmation(activity, title, description)
 *     │
 *     ├── Biometric available?
 *     │   ├── YES → Show BiometricPrompt (fingerprint / face)
 *     │   │         ├── Authenticated → return true
 *     │   │         ├── Failed       → return false
 *     │   │         └── Timeout (30s) → return false
 *     │   └── NO  → Fallback to device PIN/password
 *     │               ├── Verified → return true
 *     │               ├── Failed   → return false
 *   0 │               └── Timeout  → return false
 *     └── Activity destroyed? → return false
 * ```
 *
 * ## ZTE Nubia Neo 3 5G Considerations
 *
 * The Nubia Neo 3 5G (T8300 chip) supports:
 * - **Fingerprint**: Side-mounted capacitive sensor
 * - **Face Unlock**: Front camera (may not meet BiometricPrompt strong auth)
 * - **PIN/Password**: Always available as fallback
 *
 * The BiometricPrompt API automatically selects the strongest available
 * biometric. If only face unlock is enrolled (which is Class 2 / weak),
 * the API may still allow it with `BIOMETRIC_WEAK` — but we configure
 * `ALLOW_DEVICE_CREDENTIAL` to ensure a strong fallback path.
 *
 * ## Thread Safety
 *
 * This class is designed to be called from a coroutine scope. The
 * [requestConfirmation] method is a `suspend` function that internally
 * bridges the callback-based BiometricPrompt into a coroutine-friendly API.
 *
 * @constructor Creates a BiometricGate instance. No context is required at
 *              construction; the Activity is provided per-request.
 */
class BiometricGate {

    companion object {
        private const val TAG = "NubiaAgent/BioGate"

        /** Timeout for the biometric prompt in milliseconds. */
        private const val PROMPT_TIMEOUT_MS = 30_000L

        /**
         * Authenticators accepted by the prompt.
         *
         * We prefer biometrics (strong or weak) and always allow device
         * credential (PIN/password/pattern) as a fallback. This ensures
         * the gate works even when:
         * - No biometric hardware is present
         * - No biometric is enrolled
         * - The biometric fails repeatedly
         */
        private const val AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }

    /**
     * Requests biometric or device-credential confirmation from the user.
     *
     * This method displays the system biometric prompt and returns a [Deferred]
     * that resolves once the user authenticates, cancels, or the prompt times out.
     *
     * ## Usage
     *
     * ```kotlin
     * // From a coroutine scope:
     * val confirmed = biometricGate.requestConfirmation(
     *     scope    = this,
     *     context  = activity,
     *     title    = "Confirm Destructive Action",
     *     description = "Send SMS to +1234567890: \"Hello\""
     * ).await()
     * if (confirmed) {
     *     // Proceed with the destructive action
     * } else {
     *     // User denied, cancelled, or timed out
     * }
     * ```
     *
     * ## Fallback Behavior
     *
     * If biometric authentication is not available on the device (no hardware,
     * no enrollment, or hardware unavailable), the method automatically falls
     * back to device credential (PIN/password/pattern) verification.
     *
     * @param scope       The [CoroutineScope] on which to launch the async work.
     * @param context     The hosting [Activity] used to display the BiometricPrompt.
     *                    Must be a foreground activity or the prompt will fail.
     * @param title       The title displayed in the biometric prompt dialog.
     * @param description The descriptive text explaining what action is being confirmed.
     * @return A [Deferred] that resolves to `true` if the user authenticated
     *         successfully, or `false` if the user cancelled, failed, or the
     *         prompt timed out.
     */
    fun requestConfirmation(
        scope: CoroutineScope,
        context: Activity,
        title: String,
        description: String
    ): Deferred<Boolean> = scope.async {
        try {
            withTimeout(PROMPT_TIMEOUT_MS) {
                performBiometricConfirmation(context, title, description)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w(TAG, "Biometric prompt timed out after ${PROMPT_TIMEOUT_MS}ms")
            false
        }
    }

    /**
     * Internal implementation that bridges BiometricPrompt callbacks
     * into a coroutine suspension point.
     *
     * The flow:
     * 1. Check if biometric/auth is available via [BiometricManager].
     * 2. Create a [BiometricPrompt] with callbacks that resume the coroutine.
     * 3. Launch the prompt with a 30-second timeout.
     * 4. If the timeout fires before authentication, cancel the prompt and
     *    resume with `false`.
     *
     * @param context     The hosting Activity.
     * @param title       Prompt title.
     * @param description Prompt description.
     * @return `true` if authenticated, `false` otherwise.
     */
    private suspend fun performBiometricConfirmation(
        context: Activity,
        title: String,
        description: String
    ): Boolean = suspendCancellableCoroutine { continuation ->

        // ── Pre-flight check: Can we authenticate at all? ───────────────────
        val biometricManager = BiometricManager.from(context)
        val canAuthenticate = biometricManager.canAuthenticate(AUTHENTICATORS)

        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            val reason = when (canAuthenticate) {
                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                    "No biometric hardware available"
                BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                    "Biometric hardware currently unavailable"
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                    "No biometric or device credential enrolled"
                BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
                    "Security update required for biometric"
                BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED ->
                    "Biometric authentication not supported"
                BiometricManager.BIOMETRIC_STATUS_UNKNOWN ->
                    "Biometric status unknown"
                else -> "Unknown biometric error: $canAuthenticate"
            }
            Log.e(TAG, "Cannot authenticate: $reason")
            continuation.resume(false)
            return@suspendCancellableCoroutine
        }

        // ── Set up the executor and prompt on the main thread ───────────────
        val executor: Executor = ContextCompat.getMainExecutor(context)

        val biometricPrompt = BiometricPrompt(
            context,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    Log.i(TAG, "Biometric authentication succeeded")
                    if (continuation.isActive) {
                        continuation.resume(true)
                    }
                }

                override fun onAuthenticationFailed() {
                    // This is called for a single failed attempt — the prompt
                    // stays visible and allows retries. Do NOT resume here.
                    Log.w(TAG, "Biometric authentication failed (single attempt)")
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    Log.w(TAG, "Biometric authentication error: $errorCode — $errString")

                    if (!continuation.isActive) return

                    when (errorCode) {
                        // User-initiated cancellation
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON -> {
                            Log.i(TAG, "User cancelled biometric prompt")
                            continuation.resume(false)
                        }

                        // System cancelled the prompt (e.g., activity went to background)
                        BiometricPrompt.ERROR_CANCELED -> {
                            Log.w(TAG, "Biometric prompt cancelled by system")
                            continuation.resume(false)
                        }

                        // Too many failed attempts — lockout
                        BiometricPrompt.ERROR_LOCKOUT -> {
                            Log.w(TAG, "Biometric lockout — too many failed attempts")
                            continuation.resume(false)
                        }

                        BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> {
                            Log.e(TAG, "Permanent biometric lockout")
                            continuation.resume(false)
                        }

                        // Timeout expired
                        BiometricPrompt.ERROR_TIMEOUT -> {
                            Log.w(TAG, "Biometric prompt timed out")
                            continuation.resume(false)
                        }

                        // Hardware/service errors
                        BiometricPrompt.ERROR_HW_UNAVAILABLE,
                        BiometricPrompt.ERROR_HW_NOT_PRESENT,
                        BiometricPrompt.ERROR_NO_BIOMETRICS,
                        BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
                        BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED,
                        BiometricPrompt.ERROR_VENDOR -> {
                            Log.e(TAG, "Biometric hardware/service error: $errorCode")
                            continuation.resume(false)
                        }

                        else -> {
                            Log.e(TAG, "Unknown biometric error: $errorCode — $errString")
                            continuation.resume(false)
                        }
                    }
                }
            }
        )

        // ── Build the prompt info ───────────────────────────────────────────
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(description)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .setConfirmationRequired(false) // Auto-confirm after biometric match
            .build()

        // Note: setNegativeButtonText() cannot be used with DEVICE_CREDENTIAL
        // authenticator. The system automatically provides "Use PIN" as the
        // negative button when device credential is allowed.

        // ── Launch with timeout ─────────────────────────────────────────────
        Log.d(TAG, "Showing biometric prompt: $title")

        try {
            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch biometric prompt", e)
            if (continuation.isActive) {
                continuation.resume(false)
            }
            return@suspendCancellableCoroutine
        }

        // ── Timeout handler ─────────────────────────────────────────────────
        // The BiometricPrompt does not have a built-in timeout on all devices.
        // We implement our own 30-second timeout to prevent the prompt from
        // hanging indefinitely if the user walks away.
        continuation.invokeOnCancellation {
            Log.d(TAG, "Biometric confirmation cancelled externally")
            try {
                biometricPrompt.cancelAuthentication()
            } catch (e: Exception) {
                Log.w(TAG, "Error cancelling biometric prompt", e)
            }
        }
    }

    /**
     * Checks whether biometric or device-credential authentication is available
     * on this device without actually showing a prompt.
     *
     * Useful for UI state decisions (e.g., showing a lock icon next to
     * destructive actions).
     *
     * @param context Any Android context.
     * @return `true` if at least one authentication method is available and enrolled.
     */
    fun isBiometricAvailable(context: android.content.Context): Boolean {
        val biometricManager = BiometricManager.from(context)
        return biometricManager.canAuthenticate(AUTHENTICATORS) ==
                BiometricManager.BIOMETRIC_SUCCESS
    }
}
