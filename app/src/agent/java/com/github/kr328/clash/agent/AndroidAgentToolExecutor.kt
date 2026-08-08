package com.github.kr328.clash.agent

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.StringRes
import androidx.core.content.getSystemService
import com.github.kr328.clash.BuildConfig
import com.github.kr328.clash.R
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.agent.model.AgentToolExecutionResult
import com.github.kr328.clash.agent.runtime.AgentToolExecutor
import com.github.kr328.clash.agent.settings.AgentBackupStore
import com.github.kr328.clash.agent.tools.AgentExecutableTools
import com.github.kr328.clash.agent.tools.AgentToolSpec
import com.github.kr328.clash.core.model.Provider
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.core.model.ConfigurationOverride
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.model.AccessControlMode
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.ProcessExitDiagnostics
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.io.File

class AndroidAgentToolExecutor(
    private val context: Context,
    private val startVpn: suspend () -> Boolean,
    private val stopVpn: suspend () -> Unit,
) : AgentToolExecutor {
    override val tools: List<AgentToolSpec> = AgentExecutableTools.all
    private val backups = AgentBackupStore(context)

    override suspend fun execute(name: String, arguments: JsonObject): AgentToolExecutionResult = when (name) {
        "profiles_list" -> profilesList()
        "profile_read_config" -> profileRead(arguments)
        "profile_create" -> profileCreate(arguments)
        "profile_replace_config" -> profileReplace(arguments)
        "profile_restore_latest" -> profileRestore(arguments)
        "profile_activate" -> profileActivate(arguments)
        "profile_clone" -> profileClone(arguments)
        "profile_update_metadata" -> profileUpdateMetadata(arguments)
        "profile_delete" -> profileDelete(arguments)
        "installed_apps" -> installedApps()
        "access_control_read" -> accessControlRead()
        "access_control_replace" -> accessControlReplace(arguments)
        "vpn_settings_read" -> vpnSettingsRead()
        "vpn_settings_update" -> vpnSettingsUpdate(arguments)
        "network_info" -> networkInfo()
        "logs_recent" -> logsRecent()
        "app_exit_history" -> appExitHistory()
        "runtime_status" -> runtimeStatus()
        "runtime_set_mode" -> runtimeSetMode(arguments)
        "runtime_start" -> runtimeStart()
        "runtime_stop" -> runtimeStop()
        "override_read" -> overrideRead(arguments)
        "override_replace" -> overrideReplace(arguments)
        "override_clear" -> overrideClear(arguments)
        "proxy_groups" -> proxyGroups()
        "proxy_select" -> proxySelect(arguments)
        "proxy_healthcheck" -> proxyHealthcheck(arguments)
        "providers_list" -> providersList()
        "provider_refresh" -> providerRefresh(arguments)
        "connections_list" -> connectionsList()
        "connection_close" -> connectionClose(arguments)
        "connections_close_all" -> connectionsCloseAll()
        else -> AgentToolExecutionResult(false, "Unknown tool: $name", str(R.string.agent_engine_unsupported_tool, name))
    }

    private suspend fun profilesList(): AgentToolExecutionResult {
        val profiles = withProfile { queryAll() }
        val body = buildJsonObject {
            put("profiles", buildJsonArray {
                profiles.forEach { profile ->
                    add(buildJsonObject {
                        put("id", profile.uuid.toString())
                        put("name", profile.name)
                        put("type", profile.type.name)
                        put("active", profile.active)
                        put("imported", profile.imported)
                        put("pending", profile.pending)
                        put("updated_at", profile.updatedAt)
                    })
                }
            })
        }
        return ok(body.toString(), str(R.string.agent_x_profiles_read, profiles.size))
    }

    private suspend fun profileRead(arguments: JsonObject): AgentToolExecutionResult {
        val profile = resolveProfile(arguments.optionalString("profile_id"))
        val yaml = readProfileConfig(profile.uuid)
        val body = buildJsonObject {
            put("profile_id", profile.uuid.toString())
            put("name", profile.name)
            put("active", profile.active)
            put("sha256", AgentBackupStore.sha256(yaml))
            put("yaml", yaml)
        }
        return ok(body.toString(), str(R.string.agent_x_profile_read, profile.name))
    }

    private suspend fun profileCreate(arguments: JsonObject): AgentToolExecutionResult {
        val name = arguments.requiredString("name").trim().take(80).ifBlank { str(R.string.agent_x_default_profile_name) }
        val yaml = normalizeYaml(arguments.requiredString("yaml"))
        val activate = arguments.optionalBoolean("activate") ?: true
        val uuid = withProfile { create(Profile.Type.File, name) }
        try {
            withProfile {
                writeProfileConfig(uuid, yaml)
                commit(uuid)
                val profile = queryByUUID(uuid) ?: error(str(R.string.agent_x_profile_missing_after_commit))
                if (activate) setActive(profile)
            }
        } catch (error: Throwable) {
            runCatching { withProfile { delete(uuid) } }
            throw IllegalArgumentException(str(R.string.agent_x_create_failed, error.message ?: ""), error)
        }
        val body = buildJsonObject {
            put("profile_id", uuid.toString())
            put("name", name)
            put("active", activate)
            put("sha256", AgentBackupStore.sha256(yaml))
            put("validated", true)
        }
        return ok(body.toString(), str(if (activate) R.string.agent_x_created_activated else R.string.agent_x_created, name))
    }

    private suspend fun profileReplace(arguments: JsonObject): AgentToolExecutionResult {
        val profile = resolveProfile(arguments.optionalString("profile_id"))
        val expected = arguments.requiredString("expected_sha256")
        val replacement = normalizeYaml(arguments.requiredString("yaml"))
        val activate = arguments.optionalBoolean("activate") ?: profile.active
        val current = readProfileConfig(profile.uuid)
        val currentHash = AgentBackupStore.sha256(current)
        require(currentHash.equals(expected, ignoreCase = true)) {
            str(R.string.agent_x_sha_mismatch, currentHash)
        }
        if (current == replacement) return ok(
            "{\"unchanged\":true,\"sha256\":\"$currentHash\"}",
            str(R.string.agent_x_unchanged),
        )

        backups.create(profile.uuid, current)
        var replacementWritten = false
        try {
            withProfile {
                writeProfileConfig(profile.uuid, replacement, expected)
                replacementWritten = true
                commit(profile.uuid)
                val committed = queryByUUID(profile.uuid) ?: error(str(R.string.agent_x_unreadable_after_commit))
                if (activate) setActive(committed)
            }
        } catch (error: Throwable) {
            val rollback = if (replacementWritten) runCatching {
                withProfile {
                    writeProfileConfig(profile.uuid, current)
                    commit(profile.uuid)
                    if (profile.active) queryByUUID(profile.uuid)?.let { setActive(it) }
                }
            } else Result.success(Unit)
            val suffix = if (rollback.isSuccess) str(R.string.agent_x_rollback_ok) else str(R.string.agent_x_rollback_failed, rollback.exceptionOrNull()?.message ?: "")
            throw IllegalArgumentException(str(R.string.agent_x_apply_failed, error.message ?: "", suffix), error)
        }

        val newHash = AgentBackupStore.sha256(replacement)
        val body = buildJsonObject {
            put("profile_id", profile.uuid.toString())
            put("sha256", newHash)
            put("validated", true)
            put("backup_created", true)
            put("active", activate)
        }
        return ok(body.toString(), str(R.string.agent_x_applied, profile.name))
    }

    private suspend fun profileRestore(arguments: JsonObject): AgentToolExecutionResult {
        val profile = resolveProfile(arguments.requiredString("profile_id"))
        val backup = backups.latest(profile.uuid) ?: error(str(R.string.agent_x_no_backup))
        val current = readProfileConfig(profile.uuid)
        val content = backups.read(backup)
        backups.create(profile.uuid, current)
        try {
            withProfile {
                writeProfileConfig(profile.uuid, content)
                commit(profile.uuid)
                queryByUUID(profile.uuid)?.let { if (profile.active) setActive(it) }
            }
        } catch (error: Throwable) {
            val rollback = runCatching {
                withProfile {
                    writeProfileConfig(profile.uuid, current)
                    commit(profile.uuid)
                    if (profile.active) queryByUUID(profile.uuid)?.let { setActive(it) }
                }
            }
            val suffix = if (rollback.isSuccess) str(R.string.agent_x_restore_rollback_ok) else str(R.string.agent_x_restore_rollback_failed, rollback.exceptionOrNull()?.message ?: "")
            throw IllegalArgumentException(str(R.string.agent_x_restore_failed, error.message ?: "", suffix), error)
        }
        return ok(
            "{\"restored\":true,\"sha256\":\"${backup.sha256}\",\"timestamp\":${backup.timestamp}}",
            str(R.string.agent_x_restored, profile.name),
        )
    }

    private suspend fun profileActivate(arguments: JsonObject): AgentToolExecutionResult {
        val profile = resolveProfile(arguments.requiredString("profile_id"))
        require(profile.imported) { str(R.string.agent_x_not_imported) }
        withProfile { setActive(profile) }
        return ok("{\"active_profile_id\":\"${profile.uuid}\"}", str(R.string.agent_x_activated, profile.name))
    }

    private suspend fun profileClone(arguments: JsonObject): AgentToolExecutionResult {
        val source = resolveProfile(arguments.requiredString("profile_id"))
        val uuid = withProfile { clone(source.uuid) }
        return ok("{\"profile_id\":\"$uuid\",\"pending\":true}", str(R.string.agent_x_cloned, source.name))
    }

    private suspend fun profileDelete(arguments: JsonObject): AgentToolExecutionResult {
        val profile = resolveProfile(arguments.requiredString("profile_id"))
        withProfile { delete(profile.uuid) }
        return ok("{\"deleted_profile_id\":\"${profile.uuid}\"}", str(R.string.agent_x_deleted, profile.name))
    }

    private suspend fun profileUpdateMetadata(arguments: JsonObject): AgentToolExecutionResult {
        val profile = resolveProfile(arguments.requiredString("profile_id"))
        val name = arguments.optionalString("name")?.trim()?.take(80)?.takeIf(String::isNotBlank) ?: profile.name
        val source = arguments.optionalString("source")?.trim() ?: profile.source
        val interval = arguments.optionalLong("update_interval_minutes")?.let { minutes ->
            require(minutes == 0L || minutes in 15..525_600) { str(R.string.agent_x_bad_interval) }
            java.util.concurrent.TimeUnit.MINUTES.toMillis(minutes)
        } ?: profile.interval
        try {
            withProfile {
                patch(profile.uuid, name, source, interval, profile.ageSecretKey)
                commit(profile.uuid)
            }
        } catch (error: Throwable) {
            runCatching {
                withProfile {
                    if (profile.pending) {
                        patch(profile.uuid, profile.name, profile.source, profile.interval, profile.ageSecretKey)
                    } else {
                        release(profile.uuid)
                    }
                }
            }
            throw IllegalArgumentException(str(R.string.agent_x_metadata_failed, error.message ?: ""), error)
        }
        return ok(
            buildJsonObject {
                put("profile_id", profile.uuid.toString())
                put("name", name)
                put("source_changed", source != profile.source)
                put("update_interval_minutes", java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(interval))
                put("validated", true)
            }.toString(),
            str(R.string.agent_x_metadata_updated, name),
        )
    }

    private fun installedApps(): AgentToolExecutionResult {
        val pm = context.packageManager
        val packages = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") pm.getInstalledPackages(0)
        }.asSequence().mapNotNull { info ->
            val application = info.applicationInfo ?: return@mapNotNull null
            val launchable = pm.getLaunchIntentForPackage(info.packageName) != null
            val system = application.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
                application.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
            if (!launchable && system) return@mapNotNull null
            AppEntry(
                label = application.loadLabel(pm).toString(),
                packageName = info.packageName,
                uid = application.uid,
                system = system,
            )
        }.distinctBy(AppEntry::packageName).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label }).toList()
        val body = buildJsonObject {
            put("apps", buildJsonArray {
                packages.forEach { app -> add(buildJsonObject {
                    put("label", app.label)
                    put("package", app.packageName)
                    put("uid", app.uid)
                    put("system", app.system)
                }) }
            })
        }
        return ok(body.toString(), str(R.string.agent_x_apps_read, packages.size))
    }

    private fun accessControlRead(): AgentToolExecutionResult {
        val store = ServiceStore(context)
        return ok(buildJsonObject {
            put("mode", accessModeName(store.accessControlMode))
            put("packages", buildJsonArray { store.accessControlPackages.sorted().forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        }.toString(), str(R.string.agent_x_access_read))
    }

    private suspend fun accessControlReplace(arguments: JsonObject): AgentToolExecutionResult {
        val mode = when (arguments.requiredString("mode").lowercase()) {
            "accept_all" -> AccessControlMode.AcceptAll
            "accept_selected" -> AccessControlMode.AcceptSelected
            "deny_selected" -> AccessControlMode.DenySelected
            else -> error(str(R.string.agent_x_bad_access_mode))
        }
        val packages = arguments["packages"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet()
            ?: error(str(R.string.agent_x_missing_param, "packages"))
        val installed = installedPackageNames()
        val unknown = packages - installed
        require(unknown.isEmpty()) { str(R.string.agent_x_unknown_packages, unknown.take(12).joinToString()) }
        val store = ServiceStore(context)
        val selected = if (mode == AccessControlMode.AcceptAll) emptySet() else packages
        val changed = store.accessControlMode != mode || store.accessControlPackages != selected
        store.accessControlMode = mode
        store.accessControlPackages = selected
        val restarted = restartIfRequested(changed && (arguments.optionalBoolean("restart_if_running") ?: true))
        return ok(buildJsonObject {
            put("mode", accessModeName(mode))
            put("package_count", store.accessControlPackages.size)
            put("vpn_restarted", restarted)
        }.toString(), str(if (restarted) R.string.agent_x_access_applied_restarted else R.string.agent_x_access_applied))
    }

    private fun vpnSettingsRead(): AgentToolExecutionResult {
        val service = ServiceStore(context)
        val ui = UiStore(context)
        return ok(buildJsonObject {
            put("enable_vpn", ui.enableVpn)
            put("bypass_private_network", service.bypassPrivateNetwork)
            put("dns_hijacking", service.dnsHijacking)
            put("system_proxy", service.systemProxy)
            put("allow_bypass", service.allowBypass)
            put("allow_ipv6", service.allowIpv6)
            put("tun_stack_mode", service.tunStackMode)
            put("dynamic_notification", service.dynamicNotification)
        }.toString(), str(R.string.agent_x_vpn_read))
    }

    private suspend fun vpnSettingsUpdate(arguments: JsonObject): AgentToolExecutionResult {
        val service = ServiceStore(context)
        val ui = UiStore(context)
        var changed = false
        arguments.optionalBoolean("enable_vpn")?.let { changed = changed || ui.enableVpn != it; ui.enableVpn = it }
        arguments.optionalBoolean("bypass_private_network")?.let { changed = changed || service.bypassPrivateNetwork != it; service.bypassPrivateNetwork = it }
        arguments.optionalBoolean("dns_hijacking")?.let { changed = changed || service.dnsHijacking != it; service.dnsHijacking = it }
        arguments.optionalBoolean("system_proxy")?.let { changed = changed || service.systemProxy != it; service.systemProxy = it }
        arguments.optionalBoolean("allow_bypass")?.let { changed = changed || service.allowBypass != it; service.allowBypass = it }
        arguments.optionalBoolean("allow_ipv6")?.let { changed = changed || service.allowIpv6 != it; service.allowIpv6 = it }
        arguments.optionalBoolean("dynamic_notification")?.let { changed = changed || service.dynamicNotification != it; service.dynamicNotification = it }
        arguments.optionalString("tun_stack_mode")?.lowercase()?.let {
            require(it in setOf("system", "gvisor", "mixed")) { str(R.string.agent_x_bad_tun_stack) }
            changed = changed || service.tunStackMode != it
            service.tunStackMode = it
        }
        require(changed || arguments.keys.any { it != "restart_if_running" }) { str(R.string.agent_x_no_vpn_changes) }
        val restarted = restartIfRequested(changed && (arguments.optionalBoolean("restart_if_running") ?: true))
        return ok(buildJsonObject {
            put("updated", changed)
            put("vpn_restarted", restarted)
        }.toString(), str(if (restarted) R.string.agent_x_vpn_updated_restarted else R.string.agent_x_vpn_updated))
    }

    private fun networkInfo(): AgentToolExecutionResult {
        val connectivity = checkNotNull(context.getSystemService<ConnectivityManager>())
        val network = connectivity.activeNetwork ?: return ok("{\"connected\":false}", str(R.string.agent_x_no_network))
        val capabilities = connectivity.getNetworkCapabilities(network)
        val links = connectivity.getLinkProperties(network)
        val transports = buildList {
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("wifi")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("cellular")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("ethernet")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("vpn")
        }
        val body = buildJsonObject {
            put("connected", true)
            put("transports", buildJsonArray { transports.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            put("validated", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            put("metered", connectivity.isActiveNetworkMetered)
            put("interface", links?.interfaceName ?: "")
            put("dns_servers", buildJsonArray { links?.dnsServers?.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.hostAddress.orEmpty())) } })
            put("routes", buildJsonArray { links?.routes?.take(32)?.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.toString())) } })
        }
        return ok(body.toString(), str(R.string.agent_x_network_read))
    }

    private fun logsRecent(): AgentToolExecutionResult {
        val file = context.cacheDir.resolve("logs").listFiles()
            ?.filter(File::isFile)?.maxByOrNull(File::lastModified)
            ?: return ok("{\"available\":false,\"lines\":[]}", str(R.string.agent_x_no_logs))
        val lines = file.useLines { sequence -> sequence.takeLastBounded(300) }
        val body = buildJsonObject {
            put("available", true)
            put("file", file.name)
            put("lines", buildJsonArray { lines.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.take(2000))) } })
        }
        return ok(body.toString().take(120_000), str(R.string.agent_x_logs_read, lines.size))
    }

    private fun appExitHistory(): AgentToolExecutionResult {
        val body = ProcessExitDiagnostics.read(context)
        return ok(body, str(R.string.agent_x_exits_read))
    }

    private suspend fun runtimeStatus(): AgentToolExecutionResult {
        val active = withProfile { queryActive() }
        val body = withClash {
            val state = queryTunnelState()
            val groups = queryProxyGroupNames(true)
            buildJsonObject {
                put("vpn_running", com.github.kr328.clash.remote.Remote.broadcasts.clashRunning)
                put("active_profile", active?.name ?: "")
                put("active_profile_id", active?.uuid?.toString() ?: "")
                put("mode", state.mode.name)
                put("core_version", com.github.kr328.clash.core.bridge.Bridge.nativeCoreVersion())
                put("app_version", BuildConfig.VERSION_NAME)
                put("traffic_total", queryTrafficTotal())
                put("selectable_groups", buildJsonArray { groups.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                put("provider_count", queryProviders().size)
            }
        }
        return ok(body.toString(), str(R.string.agent_x_runtime_read))
    }

    private suspend fun runtimeStart(): AgentToolExecutionResult {
        if (Remote.broadcasts.clashRunning) return ok("{\"started\":true,\"already_running\":true}", str(R.string.agent_x_already_running))
        val started = startVpn()
        return if (started) ok("{\"started\":true}", str(R.string.agent_x_started))
        else AgentToolExecutionResult(false, "{\"started\":false,\"reason\":\"vpn_permission_denied\"}", str(R.string.agent_x_vpn_denied))
    }

    private suspend fun runtimeSetMode(arguments: JsonObject): AgentToolExecutionResult {
        val requested = arguments.requiredString("mode")
        val mode = TunnelState.Mode.entries.firstOrNull { it.name.equals(requested, true) }
            ?: error(str(R.string.agent_x_bad_mode, requested))
        withClash {
            val override = queryOverride(Clash.OverrideSlot.Session)
            override.mode = mode
            patchOverride(Clash.OverrideSlot.Session, override)
        }
        return ok("{\"mode\":${quote(mode.name.lowercase())}}", str(R.string.agent_x_mode_set, mode.name))
    }

    private suspend fun runtimeStop(): AgentToolExecutionResult {
        if (!Remote.broadcasts.clashRunning) return ok("{\"stopped\":true,\"already_stopped\":true}", str(R.string.agent_x_already_stopped))
        stopVpn()
        for (attempt in 0 until 50) {
            if (!Remote.broadcasts.clashRunning) break
            delay(100)
        }
        require(!Remote.broadcasts.clashRunning) { str(R.string.agent_x_vpn_stop_timeout) }
        return ok("{\"stopped\":true}", str(R.string.agent_x_stopped))
    }

    private suspend fun overrideRead(arguments: JsonObject): AgentToolExecutionResult {
        val slot = overrideSlot(arguments.requiredString("slot"))
        val value = withClash { queryOverride(slot) }
        val content = OVERRIDE_JSON.encodeToString(ConfigurationOverride.serializer(), value)
        return ok(
            buildJsonObject { put("slot", slot.name.lowercase()); put("override", Json.parseToJsonElement(content)) }.toString(),
            str(R.string.agent_x_override_read, slot.name.lowercase()),
        )
    }

    private suspend fun overrideReplace(arguments: JsonObject): AgentToolExecutionResult {
        val slot = overrideSlot(arguments.requiredString("slot"))
        val raw = arguments.requiredString("json")
        val value = runCatching { OVERRIDE_JSON.decodeFromString(ConfigurationOverride.serializer(), raw) }
            .getOrElse { throw IllegalArgumentException(str(R.string.agent_x_bad_override_json, it.message ?: ""), it) }
        withClash { patchOverride(slot, value) }
        return ok("{\"slot\":${quote(slot.name.lowercase())},\"applied\":true}", str(R.string.agent_x_override_applied, slot.name.lowercase()))
    }

    private suspend fun overrideClear(arguments: JsonObject): AgentToolExecutionResult {
        val slot = overrideSlot(arguments.requiredString("slot"))
        withClash { clearOverride(slot) }
        return ok("{\"slot\":${quote(slot.name.lowercase())},\"cleared\":true}", str(R.string.agent_x_override_cleared, slot.name.lowercase()))
    }

    private suspend fun proxyGroups(): AgentToolExecutionResult {
        val body = withClash {
            val names = queryProxyGroupNames(false)
            buildJsonObject {
                put("groups", buildJsonArray {
                    names.forEach { name ->
                        val group = queryProxyGroup(name, ProxySort.Default)
                        add(buildJsonObject {
                            put("name", name)
                            put("selected", group.now)
                            put("proxies", buildJsonArray { group.proxies.forEach { proxy ->
                                add(kotlinx.serialization.json.JsonPrimitive(proxy.name))
                            } })
                        })
                    }
                })
            }
        }
        return ok(body.toString(), str(R.string.agent_x_groups_read))
    }

    private suspend fun proxySelect(arguments: JsonObject): AgentToolExecutionResult {
        val group = arguments.requiredString("group")
        val proxy = arguments.requiredString("proxy")
        val changed = withClash { patchSelector(group, proxy) }
        require(changed) { str(R.string.agent_x_bad_selector) }
        return ok("{\"group\":${quote(group)},\"selected\":${quote(proxy)}}", str(R.string.agent_x_proxy_selected, group, proxy))
    }

    private suspend fun proxyHealthcheck(arguments: JsonObject): AgentToolExecutionResult {
        val group = arguments.requiredString("group")
        withClash { healthCheck(group) }
        return ok("{\"group\":${quote(group)},\"checked\":true}", str(R.string.agent_x_healthcheck_done, group))
    }

    private suspend fun providersList(): AgentToolExecutionResult {
        val providers = withClash { queryProviders() }
        val body = buildJsonObject {
            put("providers", buildJsonArray {
                providers.forEach { provider -> add(buildJsonObject {
                    put("name", provider.name)
                    put("type", provider.type.name)
                    put("updated_at", provider.updatedAt)
                    put("vehicle_type", provider.vehicleType.name)
                }) }
            })
        }
        return ok(body.toString(), str(R.string.agent_x_providers_read, providers.size))
    }

    private suspend fun providerRefresh(arguments: JsonObject): AgentToolExecutionResult {
        val requestedType = arguments.requiredString("type")
        val type = Provider.Type.entries.firstOrNull { it.name.equals(requestedType, true) }
            ?: error(str(R.string.agent_x_bad_provider_type, requestedType))
        val name = arguments.requiredString("name")
        withClash { updateProvider(type, name) }
        return ok("{\"updated\":true}", str(R.string.agent_x_provider_refreshed, name))
    }

    private suspend fun connectionsList(): AgentToolExecutionResult {
        val content = withClash { queryConnections() }
        return ok(content.take(120_000), str(R.string.agent_x_connections_read))
    }

    private suspend fun connectionClose(arguments: JsonObject): AgentToolExecutionResult {
        val id = arguments.requiredString("id")
        val closed = withClash { closeConnection(id) }
        require(closed) { str(R.string.agent_x_bad_connection) }
        return ok("{\"closed\":true,\"id\":${quote(id)}}", str(R.string.agent_x_connection_closed))
    }

    private suspend fun connectionsCloseAll(): AgentToolExecutionResult {
        withClash { closeAllConnections() }
        return ok("{\"closed_all\":true}", str(R.string.agent_x_connections_closed))
    }

    private suspend fun resolveProfile(id: String?): Profile = withProfile {
        if (id.isNullOrBlank()) queryActive() else runCatching { UUID.fromString(id) }.getOrNull()?.let { queryByUUID(it) }
    } ?: error(if (id.isNullOrBlank()) str(R.string.agent_x_no_active_profile) else str(R.string.agent_x_profile_not_found, id))

    private suspend fun readProfileConfig(uuid: UUID): String {
        val directory = context.cacheDir.resolve("agent-transfer").apply { mkdirs() }
        val temporary = File.createTempFile("config-read-", ".yaml", directory)
        try {
            val descriptor = ParcelFileDescriptor.open(
                temporary,
                ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE,
            )
            descriptor.use { destination -> withProfile { copyConfiguration(uuid, destination) } }
            return withContext(Dispatchers.IO) { temporary.readText() }
        } finally {
            temporary.delete()
        }
    }

    private suspend fun writeProfileConfig(uuid: UUID, content: String, expectedSha256: String? = null) {
        val directory = context.cacheDir.resolve("agent-transfer").apply { mkdirs() }
        val temporary = withContext(Dispatchers.IO) {
            File.createTempFile("config-", ".yaml", directory).apply { writeText(content) }
        }
        try {
            val descriptor = ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY)
            descriptor.use { source -> withProfile { replaceConfiguration(uuid, source, expectedSha256) } }
        } finally {
            temporary.delete()
        }
    }

    private fun normalizeYaml(yaml: String): String = yaml.trim().let {
        require(it.isNotBlank()) { str(R.string.agent_x_empty_yaml) }
        if (it.endsWith('\n')) it else "$it\n"
    }

    /** Every user-facing string goes through resources so it follows the locale. */
    private fun str(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun ok(content: String, summary: String) = AgentToolExecutionResult(true, content, summary)
    private fun quote(value: String) = kotlinx.serialization.json.JsonPrimitive(value).toString()

    private fun JsonObject.requiredString(name: String): String = this[name]?.jsonPrimitive?.contentOrNull
        ?.takeIf(String::isNotBlank) ?: error(str(R.string.agent_x_missing_param, name))
    private fun JsonObject.optionalString(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.optionalBoolean(name: String): Boolean? = this[name]?.jsonPrimitive?.booleanOrNull
    private fun JsonObject.optionalLong(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull

    private fun installedPackageNames(): Set<String> {
        val packages = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") context.packageManager.getInstalledPackages(0)
        }
        return packages.mapTo(mutableSetOf()) { it.packageName }
    }

    private suspend fun restartIfRequested(restart: Boolean): Boolean {
        if (!restart || !Remote.broadcasts.clashRunning) return false
        stopVpn()
        for (attempt in 0 until 50) {
            if (!Remote.broadcasts.clashRunning) break
            delay(100)
        }
        require(!Remote.broadcasts.clashRunning) { str(R.string.agent_x_vpn_stop_timeout_saved) }
        require(startVpn()) { str(R.string.agent_x_vpn_restart_failed) }
        return true
    }

    private fun accessModeName(mode: AccessControlMode): String = when (mode) {
        AccessControlMode.AcceptAll -> "accept_all"
        AccessControlMode.AcceptSelected -> "accept_selected"
        AccessControlMode.DenySelected -> "deny_selected"
    }

    private fun Sequence<String>.takeLastBounded(limit: Int): List<String> {
        val buffer = ArrayDeque<String>(limit)
        forEach { line ->
            if (buffer.size == limit) buffer.removeFirst()
            buffer.addLast(line)
        }
        return buffer.toList()
    }

    private fun overrideSlot(value: String): Clash.OverrideSlot = when (value.lowercase()) {
        "session" -> Clash.OverrideSlot.Session
        "persist", "persistent" -> Clash.OverrideSlot.Persist
        else -> error(str(R.string.agent_x_bad_slot))
    }

    private data class AppEntry(val label: String, val packageName: String, val uid: Int, val system: Boolean)

    companion object {
        private val OVERRIDE_JSON = Json {
            ignoreUnknownKeys = false
            encodeDefaults = false
        }
    }

}
