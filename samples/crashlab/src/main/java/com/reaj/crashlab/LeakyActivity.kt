package com.reaj.crashlab

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Classic Activity leak: each instance stores itself (and its view tree) in a static list, then finishes.
 * The finished Activities can never be garbage collected, so `dumpsys meminfo` keeps counting them.
 */
class LeakyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        repeat(VIEWS_PER_SCREEN) { root.addView(TextView(this).apply { text = "Leaked row $it" }) }
        setContentView(root)
        Faults.leakedActivities += this

        val remaining = intent.getIntExtra(EXTRA_REMAINING, 1) - 1
        if (remaining > 0) {
            startActivity(Intent(this, LeakyActivity::class.java).putExtra(EXTRA_REMAINING, remaining))
        }
        finish()
    }

    companion object {
        const val EXTRA_REMAINING = "remaining"
        private const val VIEWS_PER_SCREEN = 200
    }
}
