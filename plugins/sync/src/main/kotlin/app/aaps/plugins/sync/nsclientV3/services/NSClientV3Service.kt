package app.aaps.plugins.sync.nsclientV3.services

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.annotation.OpenForTesting
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.nsclient.NSAlarm
import app.aaps.core.interfaces.nsclient.StoreDataForDb
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventDismissNotification
import app.aaps.core.interfaces.rx.events.EventNSClientNewLog
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.LongComposedKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.nssdk.mapper.toNSDeviceStatus
import app.aaps.core.nssdk.mapper.toNSFood
import app.aaps.core.nssdk.mapper.toNSSgvV3
import app.aaps.core.nssdk.mapper.toNSTreatment
import app.aaps.plugins.sync.nsShared.NSAlarmObject
import app.aaps.plugins.sync.nsShared.NsIncomingDataProcessor
import app.aaps.plugins.sync.nsShared.events.EventNSClientUpdateGuiStatus
import app.aaps.plugins.sync.nsclient.data.NSDeviceStatusHandler
import app.aaps.plugins.sync.nsclientV3.NSClientV3Plugin
import app.aaps.plugins.sync.nsclientV3.keys.NsclientBooleanKey
import dagger.android.DaggerService
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import org.json.JSONArray
import org.json.JSONObject
import java.net.URISyntaxException
import javax.inject.Inject

@Suppress("SpellCheckingInspection")
class NSClientV3Service : DaggerService() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var nsClientV3Plugin: NSClientV3Plugin
    @Inject lateinit var config: Config
    @Inject lateinit var nsIncomingDataProcessor: NsIncomingDataProcessor
    @Inject lateinit var storeDataForDb: StoreDataForDb
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var nsDeviceStatusHandler: NSDeviceStatusHandler

    private val disposable = CompositeDisposable()

    private var wakeLock: PowerManager.WakeLock? = null
    private val binder: IBinder = LocalBinder()

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndroidAPS:NSClientService")
        wakeLock?.acquire()
        initializeWebSockets("onCreate")
    }

    override fun onDestroy() {
        super.onDestroy()
        shutdownWebsockets()
        disposable.clear()
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    inner class LocalBinder : Binder() {

        val serviceInstance: NSClientV3Service
            get() = this@NSClientV3Service
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int = START_STICKY

    var storageSocket: Socket? = null
    var alarmSocket: Socket? = null
    internal var wsConnected = false

    @OpenForTesting
    fun shutdownWebsockets() {
        storageSocket?.on(Socket.EVENT_CONNECT, onConnectStorage)
        storageSocket?.on(Socket.EVENT_DISCONNECT, onDisconnectStorage)
        storageSocket?.on("create", onDataCreateUpdate)
        storageSocket?.on("update", onDataCreateUpdate)
        storageSocket?.on("delete", onDataDelete)
        storageSocket?.disconnect()
        alarmSocket?.on(Socket.EVENT_CONNECT, onConnectAlarms)
        alarmSocket?.on(Socket.EVENT_DISCONNECT, onDisconnectAlarm)
        alarmSocket?.on("announcement", onAnnouncement)
        alarmSocket?.on("alarm", onAlarm)
        alarmSocket?.on("urgent_alarm", onUrgentAlarm)
        alarmSocket?.on("clear_alarm", onClearAlarm)
        alarmSocket?.disconnect()
        wsConnected = false
        storageSocket = null
        alarmSocket = null
    }

    /**
     * The library default reconnects every 1-5 s forever. This Nightscout sits behind a Tailscale mesh,
     * so while the mesh is down that was a DNS lookup and connect attempt every few seconds, all day.
     * Back off to two minutes instead; the REST path has its own 30 s -> 30 min backoff.
     */
    private fun wsOptions(): IO.Options = IO.Options.builder()
        .setReconnectionDelay(5_000)
        .setReconnectionDelayMax(120_000)
        .setRandomizationFactor(0.5)
        .build()

    @Suppress("SameParameterValue")
    fun initializeWebSockets(reason: String) {
        if (preferences.get(StringKey.NsClientUrl).isEmpty()) return
        val urlStorage = preferences.get(StringKey.NsClientUrl).lowercase().replace(Regex("/$"), "") + "/storage"
        val urlAlarm = preferences.get(StringKey.NsClientUrl).lowercase().replace(Regex("/$"), "") + "/alarm"
        if (!nsClientV3Plugin.isAllowed) {
            rxBus.send(EventNSClientNewLog("● WS", nsClientV3Plugin.blockingReason))
        } else if (preferences.get(NsclientBooleanKey.NsPaused)) {
            rxBus.send(EventNSClientNewLog("● WS", "paused"))
        } else {
            try {
                // java io.client doesn't support multiplexing. create 2 sockets
                storageSocket = IO.socket(urlStorage, wsOptions()).also { socket ->
                    socket.on(Socket.EVENT_CONNECT, onConnectStorage)
                    socket.on(Socket.EVENT_DISCONNECT, onDisconnectStorage)
                    rxBus.send(EventNSClientNewLog("► WS", "do connect storage $reason"))
                    socket.connect()
                    socket.on("create", onDataCreateUpdate)
                    socket.on("update", onDataCreateUpdate)
                    socket.on("delete", onDataDelete)
                }
                if (preferences.get(BooleanKey.NsClientNotificationsFromAnnouncements) ||
                    preferences.get(BooleanKey.NsClientNotificationsFromAlarms)
                )
                    alarmSocket = IO.socket(urlAlarm, wsOptions()).also { socket ->
                        socket.on(Socket.EVENT_CONNECT, onConnectAlarms)
                        socket.on(Socket.EVENT_DISCONNECT, onDisconnectAlarm)
                        rxBus.send(EventNSClientNewLog("► WS", "do connect alarm $reason"))
                        socket.connect()
                        socket.on("announcement", onAnnouncement)
                        socket.on("alarm", onAlarm)
                        socket.on("urgent_alarm", onUrgentAlarm)
                        socket.on("clear_alarm", onClearAlarm)
                    }
            } catch (_: URISyntaxException) {
                rxBus.send(EventNSClientNewLog("● WS", "Wrong URL syntax"))
            } catch (_: RuntimeException) {
                rxBus.send(EventNSClientNewLog("● WS", "RuntimeException"))
            }
        }
    }

    /**
     * Every socket.io listener and Ack runs on the library's EventThread, which logs a listener
     * exception and then rethrows it — there is no handler above it, so one malformed document
     * from the server (a missing srvModified on a v1-originated treatment, a Gson parse error)
     * took the whole app down, and could again on every reconnect. Log it and carry on instead.
     */
    private fun guarded(name: String, body: (Array<Any>) -> Unit) = Emitter.Listener { args ->
        try {
            body(args)
        } catch (e: Exception) {
            aapsLogger.error(LTag.NSCLIENT, "WS $name handler failed", e)
            rxBus.send(EventNSClientNewLog("◄ WS", "$name: ${e.javaClass.simpleName} ${e.message ?: ""}"))
        }
    }

    private fun guardedAck(name: String, body: (Array<Any>) -> Unit) = Ack { args ->
        try {
            body(args)
        } catch (e: Exception) {
            aapsLogger.error(LTag.NSCLIENT, "WS $name ack failed", e)
            rxBus.send(EventNSClientNewLog("◄ WS", "$name: ${e.javaClass.simpleName} ${e.message ?: ""}"))
        }
    }

    private val onConnectStorage = guarded("onConnectStorage") {
        val socketId = storageSocket?.id() ?: "NULL"
        rxBus.send(EventNSClientNewLog("◄ WS", "connected storage ID: $socketId"))
        if (storageSocket != null) {
            val authMessage = JSONObject().also {
                it.put("accessToken", preferences.get(StringKey.NsClientAccessToken))
                it.put("collections", JSONArray(arrayOf("devicestatus", "entries", "profile", "treatments", "foods", "settings")))
            }
            rxBus.send(EventNSClientNewLog("► WS", "requesting auth for storage"))
            storageSocket?.emit("subscribe", authMessage, guardedAck("subscribeStorage") { args ->
                val response = args[0] as JSONObject
                wsConnected = if (response.optBoolean("success")) {
                    rxBus.send(EventNSClientNewLog("◄ WS", "Subscribed for: ${response.optString("collections")}"))
                    // during disconnection updated data is not received
                    // thus run non WS load to get missing data
                    nsClientV3Plugin.initialLoadFinished = false
                    nsClientV3Plugin.executeLoopAsync("WS_CONNECT", forceNew = true)
                    true
                } else {
                    rxBus.send(EventNSClientNewLog("◄ WS", "Auth failed"))
                    false
                }
                rxBus.send(EventNSClientUpdateGuiStatus())
            })
        }
    }

    private val onConnectAlarms = guarded("onConnectAlarms") {
        val socket = alarmSocket
        val socketId = socket?.id() ?: "NULL"
        rxBus.send(EventNSClientNewLog("◄ WS", "connected alarms ID: $socketId"))
        if (socket != null) {
            val authMessage = JSONObject().also {
                it.put("accessToken", preferences.get(StringKey.NsClientAccessToken))
            }
            rxBus.send(EventNSClientNewLog("► WS", "requesting auth for alarms"))
            socket.emit("subscribe", authMessage, guardedAck("subscribeAlarms") { args ->
                val response = args[0] as JSONObject
                if (response.optBoolean("success")) rxBus.send(EventNSClientNewLog("◄ WS", response.optString("message")))
                else rxBus.send(EventNSClientNewLog("◄ WS", "Auth failed"))
            })
        }
    }

    private val onDisconnectStorage = guarded("onDisconnectStorage") { args ->
        aapsLogger.debug(LTag.NSCLIENT, "disconnect storage reason: ${args[0]}")
        rxBus.send(EventNSClientNewLog("◄ WS", "disconnect storage event"))
        wsConnected = false
        nsClientV3Plugin.initialLoadFinished = false
        rxBus.send(EventNSClientUpdateGuiStatus())
    }

    private val onDisconnectAlarm = guarded("onDisconnectAlarm") { args ->
        aapsLogger.debug(LTag.NSCLIENT, "disconnect alarm reason: ${args[0]}")
        rxBus.send(EventNSClientNewLog("◄ WS", "disconnect alarm event"))
    }

    private val onDataCreateUpdate = guarded("onDataCreateUpdate") { args ->
        val response = args[0] as JSONObject
        aapsLogger.debug(LTag.NSCLIENT, "onDataCreateUpdate: $response")
        val collection = response.getString("colName")
        val docJson = response.getJSONObject("doc")
        val docString = response.getString("doc")
        rxBus.send(EventNSClientNewLog("◄ WS CREATE/UPDATE", "$collection <i>$docString</i>"))
        // Absent on documents last touched through API v1 (careportal, older uploaders); such a
        // document is still processed, it just cannot move the high-water mark.
        val srvModified = docJson.optLong("srvModified", 0L)
        // Don't advance the high-water-mark until the initial catch-up load chain
        // has finished after a (re)connect. Otherwise the Load*Worker chain would
        // query "modifiedSince (just-bumped pointer)" and skip exactly the offline
        // window we need to backfill.
        if (nsClientV3Plugin.initialLoadFinished && srvModified > 0) {
            nsClientV3Plugin.lastLoadedSrvModified.set(collection, srvModified)
            nsClientV3Plugin.storeLastLoadedSrvModified()
        }
        when (collection) {
            "devicestatus" -> docString.toNSDeviceStatus().let { nsDeviceStatusHandler.handleNewData(arrayOf(it)) }
            "entries"      -> docString.toNSSgvV3()?.let {
                nsIncomingDataProcessor.processSgvs(listOf(it), doFullSync = false)
                storeDataForDb.storeGlucoseValuesToDb()
            }

            "profile"      ->
                nsIncomingDataProcessor.processProfile(docJson, doFullSync = false)

            "treatments"   -> docString.toNSTreatment()?.let {
                nsIncomingDataProcessor.processTreatments(listOf(it), doFullSync = false)
                storeDataForDb.storeTreatmentsToDb(fullSync = false)
            }

            "foods"        -> docString.toNSFood()?.let {
                nsIncomingDataProcessor.processFood(listOf(it))
                storeDataForDb.storeFoodsToDb()
            }

            "settings"     -> { /* nothing to do for now */
            }
        }
    }

    private val onDataDelete = guarded("onDataDelete") { args ->
        val response = args[0] as JSONObject
        aapsLogger.debug(LTag.NSCLIENT, "onDataDelete: $response")
        val collection = response.optString("colName") ?: return@guarded
        val identifier = response.optString("identifier") ?: return@guarded
        rxBus.send(EventNSClientNewLog("◄ WS DELETE", "$collection $identifier"))
        if (collection == "treatments") {
            storeDataForDb.addToDeleteTreatment(identifier)
            storeDataForDb.updateDeletedTreatmentsInDb()
        }
        if (collection == "entries") {
            storeDataForDb.addToDeleteGlucoseValue(identifier)
            storeDataForDb.updateDeletedGlucoseValuesInDb()
        }
    }

    private val onAnnouncement = guarded("onAnnouncement") { args ->

        /*
        {
        "level":0,
        "title":"Announcement",
        "message":"test",
        "plugin":{"name":"treatmentnotify","label":"Treatment Notifications","pluginType":"notification","enabled":true},
        "group":"Announcement",
        "isAnnouncement":true,
        "key":"9ac46ad9a1dcda79dd87dae418fce0e7955c68da"
        }
         */
        val data = args[0] as JSONObject
        rxBus.send(EventNSClientNewLog("◄ ANNOUNCEMENT", data.optString("message")))
        aapsLogger.debug(LTag.NSCLIENT, data.toString())
        if (preferences.get(BooleanKey.NsClientNotificationsFromAnnouncements))
            uiInteraction.addNotificationWithAction(NSAlarmObject(data))
    }
    private val onAlarm = guarded("onAlarm") { args ->

        /*
        {
        "level":1,
        "title":"Warning HIGH",
        "message":"BG Now: 5 -0.2 → mmol\/L\nRaw BG: 4.8 mmol\/L Čistý\nBG 15m: 4.8 mmol\/L\nIOB: -0.02U\nCOB: 0g",
        "eventName":"high",
        "plugin":{"name":"simplealarms","label":"Simple Alarms","pluginType":"notification","enabled":true},
        "pushoverSound":"climb",
        "debug":{"lastSGV":5,"thresholds":{"bgHigh":180,"bgTargetTop":75,"bgTargetBottom":72,"bgLow":70}},
        "group":"default",
        "key":"simplealarms_1"
        }
         */
        val data = args[0] as JSONObject
        rxBus.send(EventNSClientNewLog("◄ ALARM", data.optString("message")))
        aapsLogger.debug(LTag.NSCLIENT, data.toString())
        if (preferences.get(BooleanKey.NsClientNotificationsFromAlarms)) {
            val snoozedTo = preferences.get(LongComposedKey.NotificationSnoozedTo, data.optString("level"))
            if (snoozedTo == 0L || System.currentTimeMillis() > snoozedTo)
                uiInteraction.addNotificationWithAction(NSAlarmObject(data))
        }
    }

    private val onUrgentAlarm = guarded("onUrgentAlarm") { args: Array<Any> ->
        val data = args[0] as JSONObject
        rxBus.send(EventNSClientNewLog("◄ URGENT ALARM", data.optString("message")))
        aapsLogger.debug(LTag.NSCLIENT, data.toString())
        if (preferences.get(BooleanKey.NsClientNotificationsFromAlarms)) {
            val snoozedTo = preferences.get(LongComposedKey.NotificationSnoozedTo, data.optString("level"))
            if (snoozedTo == 0L || System.currentTimeMillis() > snoozedTo)
                uiInteraction.addNotificationWithAction(NSAlarmObject(data))
        }
    }

    private val onClearAlarm = guarded("onClearAlarm") { args ->

        /*
        {
        "clear":true,
        "title":"All Clear",
        "message":"default - Urgent was ack'd",
        "group":"default"
        }
         */
        val data = args[0] as JSONObject
        rxBus.send(EventNSClientNewLog("◄ CLEARALARM", data.optString("title")))
        aapsLogger.debug(LTag.NSCLIENT, data.toString())
        rxBus.send(EventDismissNotification(Notification.NS_ALARM))
        rxBus.send(EventDismissNotification(Notification.NS_URGENT_ALARM))
    }

    fun handleClearAlarm(originalAlarm: NSAlarm, silenceTimeInMilliseconds: Long) {
        alarmSocket?.emit("ack", originalAlarm.level, originalAlarm.group, silenceTimeInMilliseconds)
        rxBus.send(EventNSClientNewLog("► ALARMACK ", "${originalAlarm.level} ${originalAlarm.group} $silenceTimeInMilliseconds"))
    }
}
