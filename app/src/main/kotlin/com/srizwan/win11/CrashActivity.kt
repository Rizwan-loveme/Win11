package com.srizwan.win11

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Shown instead of a silent black screen / crash-restart loop whenever the
 * app's global uncaught-exception handler (see [Win11App]) catches a crash.
 * Plain Views only, nothing from the normal UI path, so it still renders
 * even if the crash happened inside the launcher's own UI setup.
 */
class CrashActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TRACE = "trace"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val trace = intent.getStringExtra(EXTRA_TRACE) ?: "(no trace attached)"

        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.WHITE)
        }

        val title = TextView(this).apply {
            text = "RizwanS2 ক্র্যাশ করেছে — এখানে কারণ দেখা যাচ্ছে"
            setTextColor(Color.BLACK)
            textSize = 18f
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(title)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val traceView = TextView(this).apply {
            text = trace
            setTextColor(Color.DKGRAY)
            setTextIsSelectable(true)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        scroll.addView(traceView)
        root.addView(scroll)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }

        btnRow.addView(Button(this).apply {
            text = "Copy"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("crash", trace))
                Toast.makeText(this@CrashActivity, "Copied", Toast.LENGTH_SHORT).show()
            }
        })
        btnRow.addView(Button(this).apply {
            text = "Full log"
            setOnClickListener {
                startActivity(Intent(this@CrashActivity, DebugLogActivity::class.java))
            }
        })
        btnRow.addView(Button(this).apply {
            text = "Restart app"
            setOnClickListener {
                val i = Intent(this@CrashActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(i)
                finish()
            }
        })
        root.addView(btnRow)

        setContentView(root)
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()
}
