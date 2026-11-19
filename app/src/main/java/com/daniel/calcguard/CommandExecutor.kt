package com.daniel.calcguard

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

object CommandExecutor {

    private var activeRingtone: Ringtone? = null

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

        val rules = TriggerRule.deserializeList(prefs.getString("custom_triggers", null))
        val matchedRule = rules.firstOrNull {
            message.trim().equals(it.keyword.trim(), ignoreCase = true)
        } ?: return

        when (matchedRule.action) {
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
            ActionType.GPS_ON -> {
                setGpsState(context, true)
                replyCallback("📍 ה-GPS הופעל.")
            }
            ActionType.GPS_OFF -> {
                setGpsState(context, false)
                replyCallback("📴 ה-GPS כובה.")
            }
            ActionType.MAKE_CALL -> {
                val number = matchedRule.extraText.trim()
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
                val bat = getBattery(context)
                replyCallback("📊 סטטוס מכשיר:\n🔋 סוללה: $bat%")
            }
            ActionType.CUSTOM_REPLY -> {
                val txt = matchedRule.extraText.ifEmpty { "✅ הפקודה בוצעה בהצלחה." }
                replyCallback(txt)
            }
        }
    }

    private fun setGpsState(context: Context, enable: Boolean) {
        try {
            val mode = if (enable) Settings.Secure.LOCATION_MODE_HIGH_ACCURACY else Settings.Secure.LOCATION_MODE_OFF
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.LOCATION_MODE, mode)
        } catch (_: Exception) { }
    }

    @SuppressLint("MissingPermission")
    private fun fetchAndSendLocation(context: Context, replyCallback: (String) -> Unit) {
        val fusedClient = LocationServices.getFusedLocationProviderClient(context)
        fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                val bat = getBattery(context)
                if (location != null) {
                    val link = "https://maps.google.com/?q=${location.latitude},${location.longitude}"
                    replyCallback("📍 מיקום נוכחי:\n$link\n🔋 סוללה: $bat%")
                } else {
                    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                    val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                        ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    if (last != null) {
                        val link = "https://maps.google.com/?q=${last.latitude},${last.longitude}"
                        replyCallback("📍 מיקום אחרון:\n$link\n🔋 סוללה: $bat%")
                    } else {
                        replyCallback("⚠️ ממתין לקליטת GPS, נסה שוב בעוד 10 שניות.\n🔋 סוללה: $bat%")
                    }
                }
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
}
