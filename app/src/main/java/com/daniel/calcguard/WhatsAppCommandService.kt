package com.daniel.calcguard

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class WhatsAppCommandService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return

        val extras = sbn.notification.extras
        val sender = extras.getString(Notification.EXTRA_TITLE)?.trim() ?: return
        val message = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: return

        CommandExecutor.processIncomingCommand(this, sender, message) { replyText ->
            sendWhatsAppReply(sbn, replyText)
        }
    }

    private fun sendWhatsAppReply(sbn: StatusBarNotification, replyText: String) {
        val actions = sbn.notification.actions ?: return
        for (action in actions) {
            val remoteInputs = action.remoteInputs ?: continue
            for (remoteInput in remoteInputs) {
                val intent = Intent()
                val bundle = Bundle()
                bundle.putCharSequence(remoteInput.resultKey, replyText)
                RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, bundle)
                try {
                    action.actionIntent.send(this, 0, intent)
                } catch (_: Exception) { }
            }
        }
    }
}
