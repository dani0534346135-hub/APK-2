package com.daniel.calcguard

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class CalcDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Toast.makeText(context, "הרשאת נעילת מסך הופעלה", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Toast.makeText(context, "הרשאת נעילת מסך בוטלה", Toast.LENGTH_SHORT).show()
    }
}
