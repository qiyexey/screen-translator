package com.hunter.screentranslator.ui

import android.os.Bundle
import androidx.core.widget.doAfterTextChanged
import com.hunter.screentranslator.App
import com.hunter.screentranslator.R
import com.hunter.screentranslator.databinding.ActivityGlossaryBinding
import com.hunter.screentranslator.util.EdgeToEdge
import com.hunter.screentranslator.util.Glossary

/**
 * v1.29.0：自定义术语表。一整块纯文本，格式与匹配规则见 [Glossary]。
 * 边输入边显示"已识别 n 条"，格式写错（比如漏了等号）当场就能看出来。
 */
class GlossaryActivity : BaseActivity() {

    private lateinit var b: ActivityGlossaryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityGlossaryBinding.inflate(layoutInflater)
        setContentView(b.root)
        EdgeToEdge.install(this)
        b.topAppBar.setNavigationOnClickListener { finish() }

        b.etGlossary.setText(App.prefs.glossaryRaw)
        refreshCount()
        b.etGlossary.doAfterTextChanged { refreshCount() }

        b.btnSave.setOnClickListener {
            val raw = b.etGlossary.text?.toString().orEmpty()
            App.prefs.glossaryRaw = raw
            toast(getString(R.string.glossary_saved, Glossary.parse(raw).size))
            finish()
        }
    }

    private fun refreshCount() {
        val n = Glossary.parse(b.etGlossary.text?.toString().orEmpty()).size
        b.tvCount.text = getString(R.string.glossary_count, n)
    }
}
