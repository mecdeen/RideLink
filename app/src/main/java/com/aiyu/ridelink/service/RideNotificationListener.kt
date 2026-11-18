package com.aiyu.ridelink.service

import android.app.Notification
import android.app.Person
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.content.ComponentName
import android.os.Build
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.aiyu.ridelink.core.ClusterController
import com.aiyu.ridelink.core.nav.MapsNotificationParser
import com.aiyu.ridelink.core.nav.MapsTurnIcons
import com.aiyu.ridelink.core.notify.MessageAlert
import com.aiyu.ridelink.core.notify.SocialNotificationParser
import com.aiyu.ridelink.core.settings.AppLocale
import com.aiyu.ridelink.core.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Reads Google Maps directions, incoming calls, chat unread counts and the playing song. */
@AndroidEntryPoint
class RideNotificationListener : NotificationListenerService() {
    @Inject lateinit var controller: ClusterController
    @Inject lateinit var turnIcons: MapsTurnIcons
    @Inject lateinit var settings: SettingsRepository

    private val scope = MainScope()

    /** Last update handled per Maps notification key, so [syncMaps] can tell which ones never reached us. */
    private val seenMaps = HashMap<String, String>()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        when {
            sbn.packageName == MapsNotificationParser.MAPS_PACKAGE -> {
                seenMaps[sbn.key] = mapsUpdateId(sbn)
                val sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
                // Maps names only the street; the turn direction is in the large icon.
                val turn = runCatching { turnIcons.classify(n.getLargeIcon()) }.getOrNull()
                val step = MapsNotificationParser.parse(title, text, sub, turn?.maneuver)
                if (step == null) {
                    noteOnce("Maps not parsed: title=$title | text=$text | sub=$sub")
                    return
                }
                noteOnce(
                    "Maps: ${step.instruction} = ${step.maneuver} (" +
                        (turn?.let { "icon ${it.name.removePrefix("maneuver_")} %.2f".format(it.score) }
                            ?: "icon not matched, ${turnIcons.templateCount} Maps icons") + ")",
                )
                lastNavKey = sbn.key
                controller.onNavigation(step)
            }
            n.category == Notification.CATEGORY_CALL -> {
                val incoming = isIncoming(n)
                noteOnce("Call ${if (incoming) "ringing" else "not ringing"}: ${sbn.packageName} title=$title | text=$text")
                // Any app's call (phone, WhatsApp, Instagram, Messenger, Telegram...): ringing shows the caller,
                // an answered or dialled call keeps it up so the display's call indicator stays lit.
                if (incoming) controller.onIncomingCall(callerName(n) ?: return)
                else controller.onCallActive(callerName(n))
            }
            SocialNotificationParser.app(sbn.packageName) != null -> {
                val app = SocialNotificationParser.app(sbn.packageName)!!
                if (isNewMessage(sbn)) {
                    noteOnce("Chat: ${sbn.packageName} title=$title")
                    controller.onChatPosted(app, chatsInShade(app))
                    showMessage(sbn, MessageAlert.source(app))
                }
                if (app == SocialNotificationParser.App.WHATSAPP) offerChatReply(sbn)
            }
            // Delivery / shopping pop-up, once per order update.
            sbn.packageName in DELIVERY_APPS && (n.flags and Notification.FLAG_GROUP_SUMMARY) == 0 -> {
                val id = "${sbn.key}|${n.`when`}"
                if (id != lastDelivery) {
                    lastDelivery = id
                    controller.onDelivery(food = sbn.packageName in FOOD_APPS)
                }
            }
            // SMS sender on the SmartXonnect display; each message once (the SMS app reposts and groups).
            sbn.packageName == smsPackage && (n.flags and Notification.FLAG_GROUP_SUMMARY) == 0 && !title.isNullOrBlank() -> {
                val id = "${sbn.key}|${n.`when`}"
                if (id != lastSms) {
                    lastSms = id
                    controller.onSms(title)
                    showMessage(sbn, MessageAlert.Source.SMS)
                }
            }
            // Any other chat app (Telegram, Signal, Messenger Lite, Google Chat...): the message on the display.
            sbn.packageName != packageName && isChat(n) && isNewMessage(sbn) -> showMessage(sbn, MessageAlert.Source.OTHER)
        }
    }

    /**
     * WhatsApp auto-reply while riding: one-to-one chats only (group conversations are skipped), through the
     * notification's own reply action (RemoteInput), the same way a watch or Android Auto replies.
     */
    private fun offerChatReply(sbn: StatusBarNotification) {
        val n = sbn.notification
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0 || n.category == Notification.CATEGORY_CALL) return
        val extras = n.extras
        if (extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)) return
        val chat = (extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) ?: extras.getCharSequence(Notification.EXTRA_TITLE))
            ?.toString()?.takeIf { it.isNotBlank() && it != "WhatsApp" } ?: return
        val action = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true } ?: return
        controller.onChatMessage("${sbn.packageName}|${n.shortcutId ?: chat}") { text -> reply(action, text) }
    }

    private fun reply(action: Notification.Action, text: String): Boolean = runCatching {
        val inputs = action.remoteInputs
        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
        val fill = Intent()
        RemoteInput.addResultsToIntent(inputs, fill, results)
        action.actionIntent.send(this, 0, fill)
    }.isSuccess

    private fun mapsUpdateId(sbn: StatusBarNotification): String {
        val extras = sbn.notification.extras
        return listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT)
            .joinToString("|", prefix = "${sbn.postTime}|") { extras.getCharSequence(it)?.toString().orEmpty() }
    }

    /**
     * Compares the shade with what the callbacks delivered: Android can leave a listener bound but silent, so a route
     * start, step or end may never arrive. False when Android no longer serves this listener at all.
     */
    private fun syncMaps(): Boolean {
        val shade = runCatching { activeNotifications }.getOrNull() ?: return false
        val maps = shade.filter { it.packageName == MapsNotificationParser.MAPS_PACKAGE }
        seenMaps.keys.retainAll(maps.mapTo(HashSet()) { it.key })
        maps.filter { seenMaps[it.key] != mapsUpdateId(it) }.forEach {
            controller.note("Maps: picked up an update the listener missed")
            onNotificationPosted(it)
        }
        if (lastNavKey != null && maps.none { it.key == lastNavKey }) {
            lastNavKey = null
            controller.note("Maps: route ended while the listener was silent")
            controller.onNavigationEnded()
        }
        return true
    }

    private var lastSms: String? = null
    private var lastDelivery: String? = null
    private val smsPackage: String? by lazy { runCatching { Telephony.Sms.getDefaultSmsPackage(this) }.getOrNull() }

    private var lastNote: String? = null

    /** Console note, skipped when it repeats (Maps reposts every few seconds). */
    private fun noteOnce(text: String) {
        if (text == lastNote) return
        lastNote = text
        controller.note(text)
    }

    /** CallStyle puts the caller in EXTRA_CALL_PERSON and may leave the title empty. */
    private fun callerName(n: Notification): String? {
        val person = if (Build.VERSION.SDK_INT >= 31) {
            @Suppress("DEPRECATION")
            n.extras.getParcelable<Person>(Notification.EXTRA_CALL_PERSON)?.name?.toString()
        } else null
        return n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
            ?: person?.takeIf { it.isNotBlank() }
            ?: n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        seenMessages.remove(sbn.key)
        when {
            sbn.key == lastNavKey -> {
                lastNavKey = null
                controller.onNavigationEnded()
            }
            // WhatsApp swaps the ringing notification for a separate ongoing one: end only when no call is left.
            sbn.notification.category == Notification.CATEGORY_CALL ->
                if (runCatching { activeNotifications }.getOrNull().orEmpty().none { it.key != sbn.key && it.notification.category == Notification.CATEGORY_CALL }) {
                    controller.onCallEnded()
                }
            sbn.packageName == smsPackage -> controller.onSmsRead()
            SocialNotificationParser.app(sbn.packageName) != null -> {
                val app = SocialNotificationParser.app(sbn.packageName)!!
                if (chatsInShade(app) == 0) controller.onChatCleared(app)
            }
        }
    }

    private var music: MusicWatcher? = null

    override fun onListenerConnected() {
        instance = this
        // Android binds the listener again after a reboot, which would wake an app the rider exited. A running session
        // means the app was opened again and the saved flag is simply about to be cleared.
        scope.launch { if (settings.current().appExited && !ClusterService.running) release() }
        // A route started before the listener (re)bound is already in the shade; pick it up now.
        runCatching { activeNotifications }.getOrNull()
            ?.filter { it.packageName == MapsNotificationParser.MAPS_PACKAGE }
            ?.forEach(::onNotificationPosted)
        // Chats already in the shade are not new; only messages from now on raise the badge (stock clears on start).
        runCatching { activeNotifications }.getOrNull()
            ?.filter { SocialNotificationParser.app(it.packageName) != null }
            ?.forEach(::isNewMessage)
        music = MusicWatcher(this, ComponentName(this, RideNotificationListener::class.java), controller).also { it.start() }
    }

    override fun onListenerDisconnected() {
        instance = null
        music?.stop()
        music = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        music?.stop()
        music = null
        scope.cancel()
        super.onDestroy()
    }

    /** Last `when` + text per notification key: chat apps repost every chat on each message, only a change is new. */
    private val seenMessages = HashMap<String, String>()

    /**
     * A real new message: not a group summary or an ongoing notice (WhatsApp Web, backup), and not a silent repost.
     * Stock does the same per key + text (NotificationReceiverService.previousKey).
     */
    private fun isNewMessage(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if ((n.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT)) != 0) return false
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return false
        val id = "${n.`when`}|$text"
        return seenMessages.put(sbn.key, id) != id
    }

    private fun isChat(n: Notification) =
        n.category == Notification.CATEGORY_MESSAGE || n.extras.containsKey(Notification.EXTRA_MESSAGES)

    /** App, sender and text of a message notification, for the display's custom lines. */
    private fun showMessage(sbn: StatusBarNotification, source: MessageAlert.Source) {
        val extras = sbn.notification.extras
        val sender = (extras.getCharSequence(Notification.EXTRA_TITLE) ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE))
            ?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return
        val group = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)
        controller.onMessage(MessageAlert(source, appName(sbn), sender, text, group))
    }

    /**
     * The posting app's name. The builder stores its ApplicationInfo in the extras ("android.appInfo"), which avoids
     * the package-visibility rules a PackageManager lookup runs into on Android 11+.
     */
    private fun appName(sbn: StatusBarNotification): String = runCatching {
        @Suppress("DEPRECATION")
        val info = sbn.notification.extras.getParcelable<ApplicationInfo>("android.appInfo")
            ?: packageManager.getApplicationInfo(sbn.packageName, 0)
        info.loadLabel(packageManager).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: sbn.packageName.substringAfterLast('.')

    /** Chat notifications of [app] still in the shade (summaries left out). */
    private fun chatsInShade(app: SocialNotificationParser.App): Int =
        (runCatching { activeNotifications }.getOrNull() ?: return 0).count {
            SocialNotificationParser.app(it.packageName) == app &&
                (it.notification.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT)) == 0
        }

    /**
     * Ringing calls carry CallStyle type INCOMING (API 31+). Dialers without CallStyle: a full-screen intent,
     * or no running call timer (an answered call shows a chronometer).
     */
    private fun isIncoming(n: Notification): Boolean {
        val type = n.extras.getInt(Notification.EXTRA_CALL_TYPE, -1)
        if (type != -1) return type == Notification.CallStyle.CALL_TYPE_INCOMING
        return n.fullScreenIntent != null || !n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
    }

    companion object {
        @Volatile private var instance: RideNotificationListener? = null

        /** Kept across rebinds in one process, like the route it belongs to in ClusterController. */
        private var lastNavKey: String? = null

        /** Unbinds from the system so it stops keeping the process alive; ClusterService rebinds on the next start. */
        fun release() {
            instance?.let { runCatching { it.requestUnbind() } }
        }

        /** Catches up on missed Maps updates. False when the listener is not connected and needs [rebind]. */
        fun sync(): Boolean = instance?.syncMaps() == true

        /**
         * Asks Android to connect the listener again. [force] also switches the component off and on, which recovers
         * a stale binding that requestRebind alone ignores because Android still counts it as bound.
         */
        fun rebind(context: Context, force: Boolean = false) {
            val component = ComponentName(context, RideNotificationListener::class.java)
            if (force) runCatching {
                val pm = context.packageManager
                pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            }
            runCatching { requestRebind(component) }
        }

        /** Swiggy, Zomato, Blinkit, Zepto, Swiggy Instamart (same app), Dunzo. */
        private val FOOD_APPS = setOf("in.swiggy.android", "com.application.zomato", "com.grofers.customerapp", "com.zeptoconsumerapp", "com.dunzo.user")
        /** Food apps plus Amazon and Flipkart ("Order", as stock N251HelperService.sendSocialmediaNotification). */
        private val DELIVERY_APPS = FOOD_APPS + setOf("in.amazon.mShop.android.shopping", "com.flipkart.android")
    }
}
