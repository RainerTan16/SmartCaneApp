package com.tan.smartcane

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

class CrashActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val trace = (intent.getStringExtra("trace") ?: "Walang detalye").take(3500)
        val tv = TextView(this)
        tv.text = "NAG-CRASH ANG APP\nI-screenshot ito at ipadala:\n\n" + trace
        tv.textSize = 12f
        tv.setPadding(24, 24, 24, 24)
        tv.setTextIsSelectable(true)
        val sv = ScrollView(this)
        sv.addView(tv)
        setContentView(sv)
    }
}
