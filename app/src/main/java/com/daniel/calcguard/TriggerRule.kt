package com.daniel.calcguard

import org.json.JSONArray
import org.json.JSONObject

enum class ActionType(val displayName: String) {
    SEND_LOCATION("שלח מיקום GPS + סוללה"),
    PLAY_ALARM("הפעל אזעקה חזקה"),
    STOP_ALARM("כבה אזעקה"),
    FLASHLIGHT_ON("הדלק פנס (LED)"),
    FLASHLIGHT_OFF("כבה פנס (LED)"),
    RINGER_NORMAL("העבר מצב שמע לצלצול רגיל"),
    DND_ON("הפעל מצב נא לא להפריע"),
    DND_OFF("כבה מצב נא לא להפריע"),
    SPEAK_TEXT("הקרא טקסט בקול רם (TTS)"),
    LOCK_SCREEN("נעילת מסך מיידית (Device Admin)"),
    GEOFENCE_START("הפעל גדר גיאוגרפית (קואורדינטות,רדיוס)"),
    GEOFENCE_STOP("כבה גדר גיאוגרפית"),
    GPS_ON("הפעל מיקום (GPS)"),
    GPS_OFF("כבה מיקום (GPS)"),
    DATA_ON("הפעל נתונים סלולריים (ADB)"),
    DATA_OFF("כבה נתונים סלולריים (ADB)"),
    HOTSPOT_ON("הפעל נקודה חמה מקומית (Hotspot)"),
    BATTERY_SAVER_ON("הפעל חיסכון בסוללה (ADB)"),
    MAKE_CALL("חייג למספר מוגדר (על רמקול)"),
    END_CALL("נתק שיחה פעילה"),
    DEVICE_STATUS("שלח דוח סטטוס מלא (סוללה, טעינה, רשת)"),
    CHANGE_PIN("שנה סיסמת כניסה למחשבון"),
    CUSTOM_REPLY("שלח הודעת אישור מותאמת")
}

data class TriggerRule(
    val id: String,
    val keyword: String,
    val action: ActionType,
    val extraText: String = "",
    val delaySeconds: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("keyword", keyword)
        put("action", action.name)
        put("extraText", extraText)
        put("delaySeconds", delaySeconds)
    }

    companion object {
        fun fromJson(obj: JSONObject): TriggerRule = TriggerRule(
            id = obj.getString("id"),
            keyword = obj.getString("keyword"),
            action = ActionType.valueOf(obj.getString("action")),
            extraText = obj.optString("extraText", ""),
            delaySeconds = obj.optInt("delaySeconds", 0)
        )

        fun serializeList(list: List<TriggerRule>): String {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun deserializeList(jsonStr: String?): MutableList<TriggerRule> {
            val result = mutableListOf<TriggerRule>()
            if (jsonStr.isNullOrEmpty()) return result
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                result.add(fromJson(arr.getJSONObject(i)))
            }
            return result
        }
    }
}
