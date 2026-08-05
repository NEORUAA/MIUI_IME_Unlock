package com.xposed.wetypehook

import android.app.BroadcastOptions
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import java.util.UUID

object ModuleBridgeContract {
    private const val TAG = "MIUIIME.ModuleBridge"
    const val ACTION_BRIDGE = "com.xposed.wetypehook.action.BRIDGE"
    const val ACTION_ACK_PREFIX = "com.xposed.wetypehook.action.BRIDGE_ACK"
    const val MESSAGE_SAVE_SETTINGS = 1
    const val MESSAGE_RECORD_ACTIVATION = 2
    const val RESULT_ACCEPTED = 1
    const val EXTRA_MESSAGE_TYPE = "message_type"
    const val EXTRA_SETTINGS = "settings"
    const val EXTRA_REVISION = "revision"
    const val EXTRA_ACK_ACTION = "ack_action"
    const val EXTRA_ACK_TOKEN = "ack_token"
    const val EXTRA_RESULT = "result"
    const val EXTRA_BRIDGE_PENDING_INTENT = "bridge_pending_intent"
    const val EXTRA_BRIDGE_SESSION_TOKEN = "bridge_session_token"
    const val ACK_TIMEOUT_MILLIS = 5_000L

    private const val MODULE_PACKAGE_NAME = "com.xposed.wetypehook"
    private const val BRIDGE_SESSION_PREFERENCES = "module_bridge_session"
    private const val KEY_BRIDGE_SESSION_TOKEN = "bridge_session_token"
    private const val SETTINGS_BRIDGE_REQUEST_CODE = 102

    fun explicitBridgeIntent(): Intent = Intent(ACTION_BRIDGE).setComponent(
        ComponentName(MODULE_PACKAGE_NAME, ModuleBridgeReceiver::class.java.name)
    )

    fun createSettingsBridgePendingIntent(context: Context): PendingIntent {
        check(context.packageName == MODULE_PACKAGE_NAME)
        val sessionToken = UUID.randomUUID().toString()
        check(
            context.getSharedPreferences(BRIDGE_SESSION_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_BRIDGE_SESSION_TOKEN, sessionToken)
                .commit()
        )
        val bridgeIntent = explicitBridgeIntent()
            .putExtra(EXTRA_MESSAGE_TYPE, MESSAGE_SAVE_SETTINGS)
            .putExtra(EXTRA_BRIDGE_SESSION_TOKEN, sessionToken)
        return PendingIntent.getBroadcast(
            context,
            SETTINGS_BRIDGE_REQUEST_CODE,
            bridgeIntent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    fun settingsBridgePendingIntent(intent: Intent): PendingIntent? {
        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_BRIDGE_PENDING_INTENT, PendingIntent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_BRIDGE_PENDING_INTENT)
        }
        return pendingIntent?.takeIf { it.creatorPackage == MODULE_PACKAGE_NAME }
    }

    fun hasValidSettingsBridgeSession(context: Context, intent: Intent): Boolean {
        val expectedToken = context
            .getSharedPreferences(BRIDGE_SESSION_PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_BRIDGE_SESSION_TOKEN, null)
            ?: return false
        return intent.getStringExtra(EXTRA_BRIDGE_SESSION_TOKEN) == expectedToken
    }

    fun sendWithIdentity(context: Context, intent: Intent): Boolean {
        intent.addFlags(
            Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND
        )
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = BroadcastOptions.makeBasic()
                    .setShareIdentityEnabled(true)
                    .toBundle()
                context.sendBroadcast(intent, null, options)
            } else {
                context.sendBroadcast(intent)
            }
        }.onFailure { error ->
            Log.e(TAG, "Failed to send ${intent.action} to ${intent.component ?: intent.`package`}", error)
        }.isSuccess
    }
}

class ModuleBridgeReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "MIUIIME.ModuleBridge"
        private const val WETYPE_PACKAGE_NAME = "com.tencent.wetype"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ModuleBridgeContract.ACTION_BRIDGE) return
        when (intent.getIntExtra(ModuleBridgeContract.EXTRA_MESSAGE_TYPE, 0)) {
            ModuleBridgeContract.MESSAGE_SAVE_SETTINGS -> importSettings(context, intent)
            ModuleBridgeContract.MESSAGE_RECORD_ACTIVATION -> recordActivation(context, intent)
        }
    }

    private fun importSettings(context: Context, intent: Intent) {
        if (!ModuleBridgeContract.hasValidSettingsBridgeSession(context, intent) &&
            !isTrustedSender(context, WETYPE_PACKAGE_NAME)
        ) {
            Log.w(TAG, "Rejected settings from an untrusted sender")
            sendAcknowledgement(context, intent, false)
            return
        }
        val settings = intent.getBundleExtra(ModuleBridgeContract.EXTRA_SETTINGS)
        val imported = settings?.let { bundle ->
            bundle.putLong(
                ModuleBridgeContract.EXTRA_REVISION,
                intent.getLongExtra(ModuleBridgeContract.EXTRA_REVISION, 0L)
            )
            runCatching { WeTypeSettings.importBridgedSettings(context, bundle) }
                .onFailure { error -> Log.e(TAG, "Failed to import bridged settings", error) }
                .getOrDefault(false)
        } ?: false
        if (imported) {
            Log.i(TAG, "Accepted WeType settings for remote synchronization")
        } else {
            Log.w(TAG, "WeType settings import was not persisted")
        }
        sendAcknowledgement(context, intent, imported)
    }

    private fun recordActivation(context: Context, intent: Intent) {
        val sourcePackage = intent.getStringExtra(ModuleActivationTracker.EXTRA_SOURCE_PACKAGE)
            ?: return
        if (!isTrustedSender(context, sourcePackage)) {
            Log.w(TAG, "Rejected activation for $sourcePackage from an untrusted sender")
            return
        }
        ModuleActivationTracker.recordActivation(
            context = context,
            sourcePackage = sourcePackage,
            sourceProcess = intent.getStringExtra(ModuleActivationTracker.EXTRA_SOURCE_PROCESS)
        )
    }

    private fun sendAcknowledgement(context: Context, request: Intent, accepted: Boolean) {
        val action = request.getStringExtra(ModuleBridgeContract.EXTRA_ACK_ACTION) ?: return
        val token = request.getStringExtra(ModuleBridgeContract.EXTRA_ACK_TOKEN) ?: return
        val acknowledgement = Intent(action)
            .setPackage(WETYPE_PACKAGE_NAME)
            .putExtra(ModuleBridgeContract.EXTRA_ACK_TOKEN, token)
            .putExtra(
                ModuleBridgeContract.EXTRA_REVISION,
                request.getLongExtra(ModuleBridgeContract.EXTRA_REVISION, 0L)
            )
            .putExtra(
                ModuleBridgeContract.EXTRA_RESULT,
                if (accepted) ModuleBridgeContract.RESULT_ACCEPTED else 0
            )
        ModuleBridgeContract.sendWithIdentity(context, acknowledgement)
    }

    private fun isTrustedSender(context: Context, expectedPackage: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        return context.packageManager.getPackagesForUid(sentFromUid)
            ?.contains(expectedPackage) == true
    }
}
