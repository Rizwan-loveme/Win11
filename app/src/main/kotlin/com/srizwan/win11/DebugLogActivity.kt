package com.srizwan.win11

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Plain-Views log viewer. Reachable from the "RizwanS2 debug log" notification
 * (posted once at app startup, see [MainActivity.postDebugLogNotification]) so
 * it still opens even when the launcher's own home screen is stuck black —
 * the notification shade is a separate system surface from the launcher UI.
 *
 * No internet, no second device needed: "Copy all" puts the whole log on the
 * clipboard, "Share" opens the normal Android share sheet (e.g. to paste it
 * into any messaging app) so the log can get off the phone without a cable.
 */
class DebugLogActivity : AppCompatActivity() {

    private lateinit var textView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.WHITE)
        }

        root.addView(TextView(this).apply {
            text = "Debug log — ${Logger.logFilePath()}"
            setTextColor(Color.BLACK)
            textSize = 14f
            setPadding(0, 0, 0, pad / 2)
        })

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        textView = TextView(this).apply {
            setTextColor(Color.DKGRAY)
            setTextIsSelectable(true)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        scroll.addView(textView)
        root.addView(scroll)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, pad / 2, 0, 0)
        }
        btnRow.addView(Button(this).apply {
            text = "Refresh"
            setOnClickListener { refresh() }
        })
        btnRow.addView(Button(this).apply {
            text = "Copy all"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("debug log", Logger.readAll()))
                Toast.makeText(this@DebugLogActivity, "Copied", Toast.LENGTH_SHORT).show()
            }
        })
        btnRow.addView(Button(this).apply {
            text = "Share"
            setOnClickListener {
                try {
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, Logger.readAll())
                            },
                            "Share debug log"
                        )
                    )
                } catch (e: Exception) { }
            }
        })
        btnRow.addView(Button(this).apply {
            text = "Clear"
            setOnClickListener {
                Logger.clear()
                refresh()
            }
        })
        root.addView(btnRow)

        setContentView(root)
        refresh()
    }

    private fun refresh() {
        textView.text = Logger.readAll()
    }
}
