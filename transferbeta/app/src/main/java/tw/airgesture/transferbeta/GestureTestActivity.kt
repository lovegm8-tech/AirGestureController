package tw.airgesture.transferbeta

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Deliberately opt-in operation playground; the main settings page stays guarded. */
class GestureTestActivity : Activity() {
    companion object {
        @Volatile var isVisible: Boolean = false
            private set
    }
    override fun onResume() {
        super.onResume()
        isVisible = true
    }
    override fun onPause() {
        isVisible = false
        super.onPause()
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setBackgroundColor(0xfff2f7ff.toInt())
        }
        root.addView(TextView(this).apply {
            text = "手勢操作測試區"
            textSize = 24f
            setTextColor(0xff102b51.toInt())
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "張掌上／下揮動：捲動下方清單\n張掌向右揮：返回上一頁\n張掌向左揮：從畫面右側向左滑動\n直接握拳保持 1.5 秒：點擊畫面中央\n食指停留：點擊游標指向的項目\n跨裝置抓取請回到相簿測試，必須先連線接收設備。"
            textSize = 15f
            setTextColor(0xff293950.toInt())
            setPadding(0, dp(14), 0, dp(14))
        })
        val status = TextView(this).apply {
            text = "等待手勢動作…"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xff097658.toInt())
        }
        root.addView(status)
        val scroll = ScrollView(this)
        val items = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (n in 1..28) {
            val index = n
            items.addView(Button(this).apply {
                text = "測試項目 $index · 上下滑動可看到更多"
                minHeight = dp(75)
                setOnClickListener { status.text = "已成功點擊第 $index 項" }
            })
        }
        scroll.addView(items)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(Button(this).apply {
            text = "回到設定首頁"
            setOnClickListener { finish() }
        })
        setContentView(root)
    }
}
