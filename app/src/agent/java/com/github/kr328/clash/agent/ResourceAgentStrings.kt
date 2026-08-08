package com.github.kr328.clash.agent

import android.content.Context
import com.github.kr328.clash.R
import com.github.kr328.clash.agent.runtime.AgentStrings

/**
 * Supplies the engine's user-facing text from resources, so approval prompts and
 * run status follow the device language instead of being pinned to whatever the
 * engine happened to be written in.
 */
class ResourceAgentStrings(context: Context) : AgentStrings {
    // Application context: this outlives the activity, since a run continues
    // while the screen is closed.
    private val context = context.applicationContext

    override fun operationSummary(toolName: String, target: String, yamlLines: Int): String {
        val label = operationLabel(toolName)
        val withTarget = if (target.isBlank()) {
            label
        } else {
            context.getString(R.string.agent_op_with_target, label, target)
        }
        return if (yamlLines > 0) {
            context.getString(R.string.agent_op_with_yaml, withTarget, yamlLines)
        } else {
            withTarget
        }
    }

    /** Falls back to the raw tool name, which is still more use than nothing. */
    private fun operationLabel(toolName: String): String = when (toolName) {
        "profile_create" -> context.getString(R.string.agent_op_profile_create)
        "profile_replace_config" -> context.getString(R.string.agent_op_profile_replace_config)
        "profile_restore_latest" -> context.getString(R.string.agent_op_profile_restore_latest)
        "profile_activate" -> context.getString(R.string.agent_op_profile_activate)
        "profile_clone" -> context.getString(R.string.agent_op_profile_clone)
        "profile_update_metadata" -> context.getString(R.string.agent_op_profile_update_metadata)
        "profile_delete" -> context.getString(R.string.agent_op_profile_delete)
        "access_control_replace" -> context.getString(R.string.agent_op_access_control_replace)
        "vpn_settings_update" -> context.getString(R.string.agent_op_vpn_settings_update)
        "runtime_set_mode" -> context.getString(R.string.agent_op_runtime_set_mode)
        "runtime_start" -> context.getString(R.string.agent_op_runtime_start)
        "runtime_stop" -> context.getString(R.string.agent_op_runtime_stop)
        "override_replace" -> context.getString(R.string.agent_op_override_replace)
        "override_clear" -> context.getString(R.string.agent_op_override_clear)
        "proxy_select" -> context.getString(R.string.agent_op_proxy_select)
        "proxy_healthcheck" -> context.getString(R.string.agent_op_proxy_healthcheck)
        "provider_refresh" -> context.getString(R.string.agent_op_provider_refresh)
        "connection_close" -> context.getString(R.string.agent_op_connection_close)
        "connections_close_all" -> context.getString(R.string.agent_op_connections_close_all)
        else -> toolName
    }

    override fun retrying(attempt: Int, total: Int): String =
        context.getString(R.string.agent_engine_retrying, attempt, total)

    override fun roundLimitReached(limit: Int): String =
        context.getString(R.string.agent_engine_round_limit, limit)

    override fun unsupportedTool(toolName: String): String =
        context.getString(R.string.agent_engine_unsupported_tool, toolName)

    override fun cancelled(operationSummary: String): String =
        context.getString(R.string.agent_engine_cancelled, operationSummary)

    override fun operationFailed(): String =
        context.getString(R.string.agent_engine_op_failed)

    override fun emptyReply(): String =
        context.getString(R.string.agent_engine_empty_reply)

    override fun notConfigured(): String =
        context.getString(R.string.agent_engine_not_configured)

    override fun emptyPrompt(): String =
        context.getString(R.string.agent_engine_empty_prompt)
}
