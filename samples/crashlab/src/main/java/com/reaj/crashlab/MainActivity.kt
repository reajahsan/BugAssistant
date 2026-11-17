package com.reaj.crashlab

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(PADDING, PADDING, PADDING, PADDING)
        }
        status = TextView(this)
        column.addView(TextView(this).apply {
            text = "Test app for BugAssistant. Trigger faults, then take a bug report and import it. " +
                "For leaks, keep this app open (do not swipe it away) while the bug report is taken."
        })
        column.addView(status)

        fun button(label: String, action: () -> Unit) =
            column.addView(Button(this).apply { text = label; setOnClickListener { action(); refresh() } })

        button("Leak 10 Activities") {
            startActivity(Intent(this, LeakyActivity::class.java).putExtra(LeakyActivity.EXTRA_REMAINING, 10))
        }
        button("Fill Java heap to 90%") { Faults.fillHeap(90) }
        button("Release leaks") { Faults.release() }
        button("Crash: OutOfMemoryError") { Faults.outOfMemory() }
        button("Crash: exception on main thread") { Faults.javaCrash() }
        button("Crash: exception on worker thread") { Faults.backgroundCrash() }
        button("Native crash (SIGSEGV)") { Faults.nativeCrash() }
        button("ANR: freeze 20 s (then tap the screen)") { Faults.freezeMainThread(ANR_SECONDS) }

        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        status.text = Faults.heapText()
    }

    private companion object {
        const val PADDING = 32
        const val ANR_SECONDS = 20L
    }
}
