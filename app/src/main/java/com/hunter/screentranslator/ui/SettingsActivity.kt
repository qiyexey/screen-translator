package com.hunter.screentranslator.ui

import android.content.Intent
import android.os.Bundle
import com.hunter.screentranslator.databinding.ActivitySettingsBinding

/**
 * v1.12.0 二级菜单：设置列表。
 *
 * 主页只留功能入口，配置按"使用频率 + 主题"分成七组，各自一个三级页面。
 * 分组依据：改引擎密钥是低频高价值、调触发方式是低频但影响大、调外观是纯偏好、
 * 修权限是出问题时才来的 —— 混在一页里就是改造前那种 1423 行的长滚动。
 */
class SettingsActivity : BaseActivity() {

    private lateinit var b: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnBack.setOnClickListener { finish() }

        b.rowEngine.setOnClickListener {
            startActivity(Intent(this, EngineSettingsActivity::class.java))
        }
        b.rowTrigger.setOnClickListener {
            startActivity(Intent(this, TriggerSettingsActivity::class.java))
        }
        b.rowTts.setOnClickListener {
            startActivity(Intent(this, TtsSettingsActivity::class.java))
        }
        b.rowAsr.setOnClickListener {
            startActivity(Intent(this, AsrSettingsActivity::class.java))
        }
        b.rowBall.setOnClickListener {
            startActivity(Intent(this, BallStyleActivity::class.java))
        }
        b.rowPermission.setOnClickListener {
            startActivity(Intent(this, PermissionActivity::class.java))
        }
        b.rowAbout.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }
}
