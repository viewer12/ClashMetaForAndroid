package com.github.kr328.clash.agent.runtime

/**
 * User-facing text the engine needs but must not author itself.
 *
 * This module is deliberately free of Android dependencies so its logic can be
 * unit-tested on a plain JVM, which also means it has no resources and cannot
 * localise anything. It used to hardcode Chinese, so every locale saw Chinese
 * for approval prompts and run status. The host supplies a localised
 * implementation instead; [English] keeps the module usable, and its tests
 * meaningful, on its own.
 */
interface AgentStrings {
    /**
     * Describes an operation that is about to run, for the approval prompt and
     * the run trace. [target] is the locale-independent subject extracted from
     * the arguments (a profile name, group, proxy…) and may be empty;
     * [yamlLines] is 0 unless the call carries a YAML document.
     */
    fun operationSummary(toolName: String, target: String, yamlLines: Int): String

    fun retrying(attempt: Int, total: Int): String

    fun roundLimitReached(limit: Int): String

    fun unsupportedTool(toolName: String): String

    fun cancelled(operationSummary: String): String

    fun operationFailed(): String

    /** Stands in when a run finishes having produced no text of its own. */
    fun emptyReply(): String

    fun notConfigured(): String

    fun emptyPrompt(): String

    companion object {
        val English: AgentStrings = object : AgentStrings {
            override fun operationSummary(toolName: String, target: String, yamlLines: Int): String {
                val label = when (toolName) {
                    "profile_create" -> "Create and validate a profile"
                    "profile_replace_config" -> "Edit and apply the configuration"
                    "profile_restore_latest" -> "Restore a configuration backup"
                    "profile_activate" -> "Switch profile"
                    "profile_clone" -> "Duplicate profile"
                    "profile_update_metadata" -> "Edit profile details"
                    "profile_delete" -> "Delete profile"
                    "access_control_replace" -> "Change per-app access control"
                    "vpn_settings_update" -> "Change Android VPN settings"
                    "runtime_set_mode" -> "Switch tunnel mode"
                    "runtime_start" -> "Start the proxy"
                    "runtime_stop" -> "Stop the proxy"
                    "override_replace" -> "Change client overrides"
                    "override_clear" -> "Clear client overrides"
                    "proxy_select" -> "Switch proxy node"
                    "proxy_healthcheck" -> "Health-check proxies"
                    "provider_refresh" -> "Refresh provider"
                    "connection_close" -> "Close a connection"
                    "connections_close_all" -> "Close every connection"
                    else -> toolName
                }
                val subject = if (target.isBlank()) label else "$label: $target"
                return if (yamlLines > 0) "$subject · full YAML, $yamlLines lines" else subject
            }

            override fun retrying(attempt: Int, total: Int) =
                "Lost the model connection, retrying ($attempt/$total)"

            override fun roundLimitReached(limit: Int) =
                "Stopped safely after $limit rounds of tool calls; break the task into smaller steps and try again"

            override fun unsupportedTool(toolName: String) = "Unsupported operation: $toolName"

            override fun cancelled(operationSummary: String) = "Cancelled: $operationSummary"

            override fun operationFailed() = "Operation failed"

            override fun emptyReply() = "Done."

            override fun notConfigured() = "Set the endpoint, API key and model name first"

            override fun emptyPrompt() = "The message cannot be empty"
        }
    }
}
