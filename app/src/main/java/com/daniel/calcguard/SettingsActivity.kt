package com.daniel.calcguard

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.util.UUID

class SettingsActivity : AppCompatActivity() {

    private lateinit var rulesList: MutableList<TriggerRule>
    private lateinit var triggersContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences("CalcGuardPrefs", Context.MODE_PRIVATE)

        val etPin = findViewById<EditText>(R.id.etSecretPin)
        val etContact = findViewById<EditText>(R.id.etAllowedContact)
        val tvAdbStatus = findViewById<TextView>(R.id.tvAdbStatus)
        triggersContainer = findViewById(R.id.triggersContainer)

        etPin.setText(prefs.getString("secret_pin", "123456"))
        etContact.setText(prefs.getString("allowed_contact", ""))

        val hasAdb = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
        tvAdbStatus.text = if (hasAdb) "✅ הרשאת ADB פעילה" else "⚠️ הרשאת ADB חסרה (נדרשת להדלקת GPS מרחוק)"

        val savedJson = prefs.getString("custom_triggers", null)
        rulesList = if (savedJson == null) {
            mutableListOf(
                TriggerRule(UUID.randomUUID().toString(), "מיקום", ActionType.SEND_LOCATION),
                TriggerRule(UUID.randomUUID().toString(), "אזעקה", ActionType.PLAY_ALARM),
                TriggerRule(UUID.randomUUID().toString(), "נתק", ActionType.END_CALL)
            )
        } else {
            TriggerRule.deserializeList(savedJson)
        }

        renderTriggers()

        findViewById<Button>(R.id.btnAddTrigger).setOnClickListener {
            showAddTriggerDialog()
        }

        findViewById<Button>(R.id.btnGrantPerms).setOnClickListener {
            val perms = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.CALL_PHONE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                perms.add(Manifest.permission.ANSWER_PHONE_CALLS)
            }
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), 101)
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val newPin = etPin.text.toString().trim()
            if (newPin.length < 3) {
                Toast.makeText(this, "הסיסמה חייבת להכיל לפחות 3 ספרות", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit()
                .putString("secret_pin", newPin)
                .putString("allowed_contact", etContact.text.toString().trim())
                .putString("custom_triggers", TriggerRule.serializeList(rulesList))
                .apply()

            Toast.makeText(this, "הסיסמה וההגדרות נשמרו בהצלחה!", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun renderTriggers() {
        triggersContainer.removeAllViews()
        for (rule in rulesList) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(24, 20, 24, 20)
                setBackgroundResource(R.drawable.bg_card)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.setMargins(0, 0, 0, 14)
                layoutParams = params
            }

            val info = TextView(this).apply {
                val extra = if (rule.extraText.isNotEmpty()) "\nיעד/תוכן: ${rule.extraText}" else ""
                text = "מילת טריגר: \"${rule.keyword}\"\nפעולה: ${rule.action.displayName}$extra"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val btnDelete = Button(this).apply {
                text = "מחק"
                setBackgroundColor(0xFFD32F2F.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                setOnClickListener {
                    rulesList.remove(rule)
                    renderTriggers()
                }
            }

            row.addView(info)
            row.addView(btnDelete)
            triggersContainer.addView(row)
        }
    }

    private fun showAddTriggerDialog() {
        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val etKeyword = EditText(this).apply {
            hint = "מילת טריגר (למשל: חייג)"
        }

        val spinner = Spinner(this)
        val actions = ActionType.values()
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            actions.map { it.displayName }
        )

        val etExtra = EditText(this).apply {
            visibility = View.GONE
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val selected = actions[pos]
                etExtra.visibility = if (selected == ActionType.MAKE_CALL || selected == ActionType.CUSTOM_REPLY) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
                etExtra.hint = if (selected == ActionType.MAKE_CALL) "מספר טלפון לחיוג" else "טקסט תגובה מותאם"
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        dialogLayout.addView(etKeyword)
        dialogLayout.addView(spinner)
        dialogLayout.addView(etExtra)

        AlertDialog.Builder(this)
            .setTitle("הוספת טריגר (WhatsApp + SMS)")
            .setView(dialogLayout)
            .setPositiveButton("הוסף") { _, _ ->
                val kw = etKeyword.text.toString().trim()
                if (kw.isNotEmpty()) {
                    rulesList.add(
                        TriggerRule(
                            id = UUID.randomUUID().toString(),
                            keyword = kw,
                            action = actions[spinner.selectedItemPosition],
                            extraText = etExtra.text.toString().trim()
                        )
                    )
                    renderTriggers()
                }
            }
            .setNegativeButton("ביטול", null)
            .show()
    }
}
