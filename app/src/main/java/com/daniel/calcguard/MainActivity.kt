package com.daniel.calcguard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var display: TextView
    private var currentInput = ""
    private var firstOperand = 0.0
    private var pendingOperator = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        display = findViewById(R.id.tvDisplay)

        val numberButtons = listOf(
            R.id.btn0, R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4,
            R.id.btn5, R.id.btn6, R.id.btn7, R.id.btn8, R.id.btn9
        )

        for (id in numberButtons) {
            findViewById<Button>(id).setOnClickListener { btn ->
                val digit = (btn as Button).text.toString()
                currentInput += digit
                display.text = currentInput
            }
        }

        findViewById<Button>(R.id.btnDot).setOnClickListener {
            if (!currentInput.contains(".")) {
                currentInput = if (currentInput.isEmpty()) "0." else "$currentInput."
                display.text = currentInput
            }
        }

        findViewById<Button>(R.id.btnBackspace).setOnClickListener {
            if (currentInput.isNotEmpty()) {
                currentInput = currentInput.dropLast(1)
                display.text = currentInput.ifEmpty { "0" }
            }
        }

        findViewById<Button>(R.id.btnClear).setOnClickListener {
            currentInput = ""
            firstOperand = 0.0
            pendingOperator = ""
            display.text = "0"
        }

        val ops = mapOf(
            R.id.btnPlus to "+",
            R.id.btnMinus to "-",
            R.id.btnMul to "*",
            R.id.btnDiv to "/"
        )

        for ((id, op) in ops) {
            findViewById<Button>(id).setOnClickListener {
                firstOperand = currentInput.toDoubleOrNull() ?: 0.0
                pendingOperator = op
                currentInput = ""
            }
        }

        findViewById<Button>(R.id.btnEquals).setOnClickListener {
            val prefs = getSharedPreferences("CalcGuardPrefs", Context.MODE_PRIVATE)
            val secretPin = prefs.getString("secret_pin", "123456")

            if (currentInput == secretPin && pendingOperator.isEmpty()) {
                currentInput = ""
                display.text = "0"
                startActivity(Intent(this, SettingsActivity::class.java))
                return@setOnClickListener
            }

            val secondOperand = currentInput.toDoubleOrNull() ?: 0.0
            val result = when (pendingOperator) {
                "+" -> firstOperand + secondOperand
                "-" -> firstOperand - secondOperand
                "*" -> firstOperand * secondOperand
                "/" -> if (secondOperand != 0.0) firstOperand / secondOperand else 0.0
                else -> secondOperand
            }

            val formatted = if (result % 1.0 == 0.0) result.toLong().toString() else result.toString()
            display.text = formatted
            currentInput = formatted
            pendingOperator = ""
        }
    }
}
