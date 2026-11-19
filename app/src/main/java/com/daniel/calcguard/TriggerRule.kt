package com.daniel.calcguard

import org.json.JSONArray
import org.json.JSONObject

enum class ActionType(val displayName: String) {
    SEND_LOCATION("שלח מיקום GPS + סוללה"),
    PLAY_ALARM("הפעל אזעקה חזקה"),
    STOP_ALARM("כבה אזעקה"),
    GPS_ON("הפעל מיקום (GPS)"),
    GPS_OFF("כבה מיקום (GPS)"),
    MAKE_CALL("חייג למספר מוגדר (על רמקול)"),
    END_CALL("נתק שיחה פעילה"),
    DEVICE_STATUS("שלח סטטוס סוללה"),
    CUSTOM_REPLY("שלח הודעת אישור מותאמת")
}

data class TriggerRule(
    val id: String,
    val keyword: String,
    val action: ActionType,
    val extraText: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("keyword", keyword)
        put("action", action.name)
        put("extraText", extraText)
    }

    companion object {
        fun fromJson(obj: JSONObject): TriggerRule = TriggerRule(
            id = obj.getString("id"),
            keyword = obj.getString("keyword"),
            action = ActionType.valueOf(obj.getString("action")),
            extraText = obj.optString("extraText", "")
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
