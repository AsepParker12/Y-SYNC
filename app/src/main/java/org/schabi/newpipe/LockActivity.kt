package org.schabi.newpipe

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Y-SYNC: one-time access code screen shown before MainActivity. */
class LockActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (AccessGate.isUnlocked(this)) {
            openMain()
            return
        }
        setContentView(R.layout.activity_lock)
        val code = findViewById<EditText>(R.id.lock_code)
        val error = findViewById<TextView>(R.id.lock_error)
        val button = findViewById<Button>(R.id.lock_button)

        fun submit() {
            if (AccessGate.tryUnlock(this, code.text.toString())) {
                openMain()
            } else {
                error.visibility = View.VISIBLE
                code.text.clear()
            }
        }
        button.setOnClickListener { submit() }
        code.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
