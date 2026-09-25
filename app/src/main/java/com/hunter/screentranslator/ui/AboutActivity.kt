package com.hunter.screentranslator.ui

import android.content.Intent
import android.os.Bundle
import com.hunter.screentranslator.databinding.ActivityAboutBinding
import com.hunter.screentranslator.util.EdgeToEdge

/** v1.12.0 用法说明独立成页（原来是一大段文字压在主页最底部） */
class AboutActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(b.root)
        EdgeToEdge.install(this)
        b.topAppBar.setNavigationOnClickListener { finish() }
        // 引导只自动出现一次，这里给一个手动重看的入口
        b.btnShowOnboarding.setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
    }
}
