package com.personal.pocketagent.automation

import android.content.Context
import android.content.Intent
import com.personal.pocketagent.domain.model.MacroStep
import kotlinx.coroutines.delay

class MacroRunner(private val context: Context) {

    suspend fun executeMacro(steps: List<MacroStep>, onProgress: (String) -> Unit = {}): Result<String> {
        if (steps.isEmpty()) return Result.failure(Exception("Macro has no steps."))

        OverlayStateController.onMacroStarted(steps.size)

        for ((index, step) in steps.withIndex()) {
            if (OverlayStateController.isCancelRequested()) {
                OverlayStateController.onMacroFinished("Stopped by user")
                return Result.failure(Exception("Macro stopped via emergency button."))
            }

            val targetDesc = step.target.ifEmpty { step.value }
            onProgress("Step ${index + 1}/${steps.size}: ${step.action} ($targetDesc)")
            OverlayStateController.onStepUpdate(index, steps.size, "${step.action}: $targetDesc")

            val success = when (step.action) {
                "open_app" -> {
                    val launched = launchAppByName(step.target)
                    if (launched) delayWithCancelCheck(1200L)
                    launched
                }
                "wait_ms" -> {
                    val ms = step.value.toLongOrNull() ?: 1000L
                    delayWithCancelCheck(ms)
                    !OverlayStateController.isCancelRequested()
                }
                "click_text" -> {
                    val skipEditable = step.value == "skip_editable"
                    pollUntilSuccess(step.timeoutMs) {
                        AgentAccessibilityService.instance?.clickNodeByText(step.target, skipEditable) == true
                    }
                }
                "click_id" -> {
                    pollUntilSuccess(step.timeoutMs) {
                        AgentAccessibilityService.instance?.clickNodeByViewId(step.target) == true
                    }
                }
                "type_text" -> {
                    val typed = pollUntilSuccess(step.timeoutMs) {
                        AgentAccessibilityService.instance?.inputTextIntoField(step.value, step.target) == true
                    }
                    if (typed) delayWithCancelCheck(500L)
                    typed
                }
                "ime_enter" -> {
                    delayWithCancelCheck(300L)
                    AgentAccessibilityService.instance?.performImeEnter() == true
                }
                "global_action" -> {
                    AgentAccessibilityService.instance?.executeGlobalCommand(step.target) == true
                }
                else -> false
            }

            if (OverlayStateController.isCancelRequested()) {
                OverlayStateController.onMacroFinished("Stopped by user")
                return Result.failure(Exception("Macro stopped via emergency button."))
            }

            if (!success) {
                OverlayStateController.onMacroFinished("Failed at step ${index + 1}")
                return Result.failure(Exception("Stopped at Step ${index + 1} (${step.action} -> '${step.target}')."))
            }

            delayWithCancelCheck(450L)
        }

        OverlayStateController.onMacroFinished("Done (${steps.size} steps)")
        return Result.success("Completed all ${steps.size} macro steps.")
    }

    private suspend fun pollUntilSuccess(timeoutMs: Long, block: () -> Boolean): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (OverlayStateController.isCancelRequested()) return false
            if (block()) return true
            delay(250L)
        }
        return false
    }

    private suspend fun delayWithCancelCheck(totalMs: Long) {
        var elapsed = 0L
        val slice = 150L
        while (elapsed < totalMs) {
            if (OverlayStateController.isCancelRequested()) return
            delay(slice)
            elapsed += slice
        }
    }

    private fun launchAppByName(targetName: String): Boolean {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val apps = pm.queryIntentActivities(mainIntent, 0)
        val match = apps.firstOrNull { it.loadLabel(pm).toString().contains(targetName, ignoreCase = true) } ?: return false
        val launchIntent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
        launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(launchIntent)
        return true
    }
}
