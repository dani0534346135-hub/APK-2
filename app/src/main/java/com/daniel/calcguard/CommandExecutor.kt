package com.daniel.calcguard

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.telecom.TelecomManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import java.util.Locale

object CommandExecutor {

    private var activeRingtone: Ringtone? = null
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var ttsEngine: TextToSpeech? = null
    private var ttsReady = false

    fun processIncomingCommand(
        context: Context,
        sender: String,
        message: String,
        replyCallback: (String) -> Unit
    ) {
        val prefs = context.getSharedPreferences("CalcGuardPrefs", Context.MODE_PRIVATE)
        val allowedContact = prefs.getString("allowed_contact", "")?.trim() ?: ""

        if (allowedContact.isNotEmpty() && !sender.contains(allowedContact, ignoreCase = true)) {
            return
        }

        val trimmedMsg = message.trim()
        val rules = TriggerRule.deserializeList(prefs.getString("custom_triggers", null))

        val matchedRule = rules.firstOrNull { rule ->
            val kw = rule.keyword.trim()
            trimmedMsg.equals(kw, ignoreCase = true) ||
                (kw.endsWith(":") && trimmedMsg.startsWith(kw, ignoreCase = true))
        } ?: return

        val suffixPayload = if (trimmedMsg.length > matchedRule.keyword.trim().length) {
            trimmedMsg.substring(matchedRule.keyword.trim().length).trim().removePrefix(":").trim()
        } else {
            ""
        }

        val delayMs = (matchedRule.delaySeconds.coerceAtLeast(0)) * 1000L

        val runnable = Runnable {
            executeRuleAction(context, matchedRule, suffixPayload, replyCallback)
        }

        if (delayMs > 0L) {
            replyCallback("⏳ הפקודה תבוצע בעוד ${matchedRule.delaySeconds} שניות.")
            Handler(Looper.getMainLooper()).postDelayed(runnable, delayMs)
        } else {
            runnable.run()
        }
    }

    private fun executeRuleAction(
        context: Context,
        rule: TriggerRule,
        suffixPayload: String,
        replyCallback: (String) -> Unit
    ) {
        when (rule.action) {
            ActionType.SEND_LOCATION -> {
                setGpsState(context, true)
                fetchAndSendLocation(context, replyCallback)
            }
            ActionType.PLAY_ALARM -> {
                playAlarm(context)
                replyCallback("🔊 האזעקה הופעלה במכשיר.")
            }
            ActionType.STOP_ALARM -> {
                stopAlarm()
                replyCallback("🔇 האזעקה כובתה.")
            }
            ActionType.FLASHLIGHT_ON -> {
                val ok = setFlashlight(context, true)
                replyCallback(if (ok) "🔦 הפנס הודלק." else "⚠️ לא ניתן להדליק את הפנס.")
            }
            ActionType.FLASHLIGHT_OFF -> {
                val ok = setFlashlight(context, false)
                replyCallback(if (ok) "🌑 הפנס כובה." else "⚠️ לא ניתן לכבות את הפנס.")
            }
            ActionType.RINGER_NORMAL -> {
                setRingerNormal(context)
                replyCallback("🔔 מצב השמע הועבר לצלצול רגיל בעוצמה גבוהה.")
            }
            ActionType.VOLUME_UP -> {
                val steps = suffixPayload.toIntOrNull() ?: rule.extraText.trim().toIntOrNull() ?: 1
                val summary = adjustVolume(context, increase = true, steps = steps)
                replyCallback(summary)
            }
            ActionType.VOLUME_DOWN -> {
                val steps = suffixPayload.toIntOrNull() ?: rule.extraText.trim().toIntOrNull() ?: 1
                val summary = adjustVolume(context, increase = false, steps = steps)
                replyCallback(summary)
            }
            ActionType.DND_ON -> {
                val ok = setDndState(context, enable = true)
                replyCallback(
                    if (ok) "🔕 מצב נא לא להפריע הופעל."
                    else "⚠️ לא ניתן לשנות מצב נא לא להפריע (אשר הרשאת גישה למדיניות התראות)."
                )
            }
            ActionType.DND_OFF -> {
                val ok = setDndState(context, enable = false)
                replyCallback(
                    if (ok) "🔔 מצב נא לא להפריע כובה."
                    else "⚠️ לא ניתן לשנות מצב נא לא להפריע (אשר הרשאת גישה למדיניות התראות)."
                )
            }
            ActionType.SPEAK_TEXT -> {
                val textToSpeak = rule.extraText.trim().ifEmpty { suffixPayload }
                if (textToSpeak.isNotEmpty()) {
                    speakLoud(context, textToSpeak)
                    replyCallback("🗣️ ההודעה הוקראה בקול רם.")
                } else {
                    replyCallback("⚠️ לא סופק טקסט להקראה.")
                }
            }
            ActionType.LOCK_SCREEN -> {
                val ok = lockScreenNow(context)
                replyCallback(
                    if (ok) "🔒 המסך ננעל."
                    else "⚠️ נעילת מסך נכשלה (ודא שהאפליקציה מאושרת כמנהל מכשיר)."
                )
            }
            ActionType.GEOFENCE_START -> {
                val spec = suffixPayload.ifEmpty { rule.extraText.trim() }
                val started = startGeofence(context, spec)
                replyCallback(
                    if (started) "🗺️ גדר גיאוגרפית הופעלה."
                    else "⚠️ פורמט שגוי. הזן: קו רוחב,קו אורך,רדיוס במטרים (למשל: 32.02,34.75,200)."
                )
            }
            ActionType.GEOFENCE_STOP -> {
                stopGeofence(context)
                replyCallback("🛑 הגדר הגיאוגרפית הופסקה.")
            }
            ActionType.GPS_ON -> {
                setGpsState(context, true)
                replyCallback("📍 ה-GPS הופעל.")
            }
            ActionType.GPS_OFF -> {
                setGpsState(context, false)
                replyCallback("📴 ה-GPS כובה.")
            }
            ActionType.WIFI_ON -> {
                val ok = setWifiState(context, true)
                replyCallback(
                    if (ok) "📶 ה-Wi-Fi הופעל."
                    else "⚠️ הפעלת Wi-Fi נכשלה (ודא שהרשאות Wi-Fi / ADB מאושרות)."
                )
            }
            ActionType.WIFI_OFF -> {
                val ok = setWifiState(context, false)
                replyCallback(
                    if (ok) "📴 ה-Wi-Fi כובה."
                    else "⚠️ כיבוי Wi-Fi נכשל (ודא שהרשאות Wi-Fi / ADB מאושרות)."
                )
            }
            ActionType.BLUETOOTH_ON -> {
                val ok = setBluetoothState(context, true)
                replyCallback(
                    if (ok) "🔵 ה-Bluetooth הופעל."
                    else "⚠️ הפעלת Bluetooth נכשלה (ודא שהרשאות בלוטות' / ADB מאושרות)."
                )
            }
            ActionType.BLUETOOTH_OFF -> {
                val ok = setBluetoothState(context, false)
                replyCallback(
                    if (ok) "⚪ ה-Bluetooth כובה."
                    else "⚠️ כיבוי Bluetooth נכשל (ודא שהרשאות בלוטות' / ADB מאושרות)."
                )
            }
            ActionType.DATA_ON -> {
                val ok = setMobileDataState(context, true)
                if (ok) replyCallback("📶 הנתונים הסלולריים הופעלו.")
                else replyCallback("⚠️ נכשלה הפעלת נתונים (וודא שהרשאת ADB פעילה).")
            }
            ActionType.DATA_OFF -> {
                val ok = setMobileDataState(context, false)
                if (ok) replyCallback("📵 הנתונים הסלולריים כובו.")
                else replyCallback("⚠️ נכשל כיבוי נתונים (וודא שהרשאת ADB פעילה).")
            }
            ActionType.HOTSPOT_ON -> {
                startLocalHotspot(context, replyCallback)
            }
            ActionType.BATTERY_SAVER_ON -> {
                val ok = setBatterySaver(context, true)
                if (ok) replyCallback("🪫 מצב חיסכון בסוללה הופעל.")
                else replyCallback("⚠️ נכשלה הפעלת חיסכון בסוללה (וודא שהרשאת ADB פעילה).")
            }
            ActionType.MAKE_CALL -> {
                val number = rule.extraText.trim()
                if (number.isNotEmpty()) {
                    makeCall(context, number)
                    replyCallback("📞 מחייג למספר $number על רמקול...")
                } else {
                    replyCallback("⚠️ לא הוגדר מספר טלפון בטריגר.")
                }
            }
            ActionType.END_CALL -> {
                val ended = endCall(context)
                if (ended) replyCallback("📵 השיחה הפעילה נותקה.")
                else replyCallback("⚠️ אין שיחה פעילה לניתוק או שההרשאה חסרה.")
            }
            ActionType.DEVICE_STATUS -> {
                replyCallback(getFullDeviceStatus(context))
            }
            ActionType.CHANGE_PIN -> {
                val candidatePin = suffixPayload.ifEmpty { rule.extraText.trim() }
                if (candidatePin.length >= 3 && candidatePin.all { it.isDigit() }) {
                    context.getSharedPreferences("CalcGuardPrefs", Context.MODE_PRIVATE)
                        .edit()
                        .putString("secret_pin", candidatePin)
                        .apply()
                    replyCallback("🔐 סיסמת הכניסה למחשבון עודכנה בהצלחה.")
                } else {
                    replyCallback("⚠️ סיסמה חדשה חייבת להכיל לפחות 3 ספרות.")
                }
            }
            ActionType.CUSTOM_REPLY -> {
                val txt = rule.extraText.ifEmpty { "✅ הפקודה בוצעה בהצלחה." }
                replyCallback(txt)
            }
        }
    }

    private fun adjustVolume(context: Context, increase: Boolean, steps: Int): String {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val count = steps.coerceIn(1, 15)
            val streams = listOf(AudioManager.STREAM_RING, AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION)

            if (increase && am.ringerMode == AudioManager.RINGER_MODE_SILENT) {
                am.ringerMode = AudioManager.RINGER_MODE_NORMAL
            }

            repeat(count) {
                for (stream in streams) {
                    val direction = if (increase) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                    am.adjustStreamVolume(stream, direction, 0)
                }
            }

            val currentRing = am.getStreamVolume(AudioManager.STREAM_RING)
            val maxRing = am.getStreamMaxVolume(AudioManager.STREAM_RING).coerceAtLeast(1)
            val pctRing = (currentRing * 100) / maxRing

            val currentMedia = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxMedia = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val pctMedia = (currentMedia * 100) / maxMedia

            val label = if (increase) "🔊 הווליום הוגבר" else "🔉 הווליום הונמך"
            "$label ($count צעדים)\n🔔 צלצול: $pctRing% | 🎵 מדיה: $pctMedia%"
        } catch (_: Exception) {
            "⚠️ לא ניתן לשנות את עוצמת הווליום."
        }
    }

    @Suppress("DEPRECATION")
    private fun setWifiState(context: Context, enable: Boolean): Boolean {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wm.isWifiEnabled = enable
            true
        } catch (_: Exception) {
            try {
                Settings.Global.putInt(context.contentResolver, "wifi_on", if (enable) 1 else 0)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun setBluetoothState(context: Context, enable: Boolean): Boolean {
        return try {
            val adapter: BluetoothAdapter? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                bm.adapter
            } else {
                BluetoothAdapter.getDefaultAdapter()
            }
            if (adapter != null) {
                if (enable) adapter.enable() else adapter.disable()
                return true
            }
            false
        } catch (_: Exception) {
            try {
                Settings.Global.putInt(context.contentResolver, "bluetooth_on", if (enable) 1 else 0)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun lockScreenNow(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, CalcDeviceAdminReceiver::class.java)
            if (dpm.isAdminActive(adminComponent)) {
                dpm.lockNow()
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun startGeofence(context: Context, spec: String): Boolean {
        val parts = spec.split(",").map { it.trim() }
        if (parts.size < 2) return false
        val lat = parts[0].toDoubleOrNull() ?: return false
        val lng = parts[1].toDoubleOrNull() ?: return false
        val radius = parts.getOrNull(2)?.toFloatOrNull() ?: 200f

        context.getSharedPreferences("CalcGuardPrefs", Context.MODE_PRIVATE)
            .edit()
            .putFloat("geofence_lat", lat.toFloat())
            .putFloat("geofence_lng", lng.toFloat())
            .putFloat("geofence_radius", radius)
            .apply()

        val intent = Intent(context, GeofenceService::class.java).apply {
            putExtra(GeofenceService.EXTRA_LAT, lat)
            putExtra(GeofenceService.EXTRA_LNG, lng)
            putExtra(GeofenceService.EXTRA_RADIUS, radius)
        }
        ContextCompat.startForegroundService(context, intent)
        return true
    }

    private fun stopGeofence(context: Context) {
        try {
            context.stopService(Intent(context, GeofenceService::class.java))
        } catch (_: Exception) { }
    }

    @Suppress("DEPRECATION")
    fun sendSmsDirect(context: Context, destination: String, text: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                SmsManager.getDefault()
            }
            val parts = smsManager.divideMessage(text)
            smsManager.sendMultipartTextMessage(destination, null, parts, null, null)
        } catch (_: Exception) { }
    }

    private fun setDndState(context: Context, enable: Boolean): Boolean {
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !nm.isNotificationPolicyAccessGranted) {
                return false
            }
            val filter = if (enable) {
                NotificationManager.INTERRUPTION_FILTER_PRIORITY
            } else {
                NotificationManager.INTERRUPTION_FILTER_ALL
            }
            nm.setInterruptionFilter(filter)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun speakLoud(context: Context, text: String) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)

            val doSpeak = {
                ttsEngine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "CalcGuardTTS")
            }

            if (ttsEngine != null && ttsReady) {
                doSpeak()
            } else {
                ttsReady = false
                ttsEngine = TextToSpeech(context.applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        val hebrewRes = ttsEngine?.setLanguage(Locale("iw", "IL"))
                        if (hebrewRes == TextToSpeech.LANG_MISSING_DATA || hebrewRes == TextToSpeech.LANG_NOT_SUPPORTED) {
                            ttsEngine?.language = Locale.getDefault()
                        }
                        ttsReady = true
                        doSpeak()
                    }
                }
            }
        } catch (_: Exception) { }
    }

    private fun setFlashlight(context: Context, enable: Boolean): Boolean {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cm.cameraIdList.firstOrNull() ?: return false
            cm.setTorchMode(cameraId, enable)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun setRingerNormal(context: Context) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.ringerMode = AudioManager.RINGER_MODE_NORMAL
            val maxRing = am.getStreamMaxVolume(AudioManager.STREAM_RING)
            am.setStreamVolume(AudioManager.STREAM_RING, maxRing, 0)
        } catch (_: Exception) { }
    }

    private fun setBatterySaver(context: Context, enable: Boolean): Boolean {
        return try {
            Settings.Global.putInt(context.contentResolver, "low_power", if (enable) 1 else 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun setGpsState(context: Context, enable: Boolean) {
        try {
            val mode = if (enable) Settings.Secure.LOCATION_MODE_HIGH_ACCURACY else Settings.Secure.LOCATION_MODE_OFF
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.LOCATION_MODE, mode)
        } catch (_: Exception) { }
    }

    private fun setMobileDataState(context: Context, enable: Boolean): Boolean {
        return try {
            Settings.Global.putInt(context.contentResolver, "mobile_data", if (enable) 1 else 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocalHotspot(context: Context, replyCallback: (String) -> Unit) {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            hotspotReservation?.close()
            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
                    hotspotReservation = reservation
                    val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        reservation?.softApConfiguration?.ssid ?: "AndroidAP"
                    } else {
                        @Suppress("DEPRECATION")
                        reservation?.wifiConfiguration?.SSID ?: "AndroidAP"
                    }
                    val pass = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        reservation?.softApConfiguration?.passphrase ?: ""
                    } else {
                        @Suppress("DEPRECATION")
                        reservation?.wifiConfiguration?.preSharedKey ?: ""
                    }
                    replyCallback("📡 נקודה חמה הופעלה!\nשם רשת: $ssid\nסיסמה: $pass")
                }

                override fun onFailed(reason: Int) {
                    replyCallback("⚠️ הפעלת נקודה חמה נכשלה (קוד: $reason). וודא שהרשאת המיקום מאושרת.")
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            replyCallback("⚠️ שגיאה בהפעלת נקודה חמה: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchAndSendLocation(context: Context, replyCallback: (String) -> Unit) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val bat = getBattery(context)

        val bestLast = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).mapNotNull { provider ->
            try { lm.getLastKnownLocation(provider) } catch (_: Exception) { null }
        }.maxByOrNull { it.time }

        if (bestLast != null) {
            val link = "https://maps.google.com/?q=${bestLast.latitude},${bestLast.longitude}"
            replyCallback("📍 מיקום:\n$link\n🔋 סוללה: $bat%")
            return
        }

        try {
            val provider = if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                LocationManager.NETWORK_PROVIDER
            } else {
                LocationManager.GPS_PROVIDER
            }
            @Suppress("DEPRECATION")
            lm.requestSingleUpdate(provider, { loc ->
                val link = "https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
                replyCallback("📍 מיקום נוכחי:\n$link\n🔋 סוללה: $bat%")
            }, Looper.getMainLooper())
        } catch (_: Exception) {
            replyCallback("⚠️ לא הצלחתי למשוך מיקום. וודא שהרשאת מיקום מאושרת תמיד.\n🔋 סוללה: $bat%")
        }
    }

    private fun playAlarm(context: Context) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            activeRingtone = RingtoneManager.getRingtone(context.applicationContext, uri)
            activeRingtone?.play()
        } catch (_: Exception) { }
    }

    private fun stopAlarm() {
        try {
            activeRingtone?.stop()
        } catch (_: Exception) { }
    }

    @SuppressLint("MissingPermission")
    private fun makeCall(context: Context, phoneNumber: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$phoneNumber")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.mode = AudioManager.MODE_IN_CALL
                am.isSpeakerphoneOn = true
            } catch (_: Exception) { }
        }, 2500)
    }

    @SuppressLint("MissingPermission")
    private fun endCall(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS)
            != PackageManager.PERMISSION_GRANTED
        ) return false
        val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        return tm?.endCall() ?: false
    }

    private fun getBattery(context: Context): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun getFullDeviceStatus(context: Context): String {
        val bat = getBattery(context)
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val chargeStr = if (isCharging) "בטעינה ⚡" else "לא בטעינה"

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val netStr = when {
            caps == null -> "אין חיבור רשת"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi 📶"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "רשת סלולרית 📡"
            else -> "מחובר"
        }

        return "📊 דוח מצב מכשיר:\n🔋 סוללה: $bat% ($chargeStr)\n🌐 חיבור: $netStr"
    }
}
