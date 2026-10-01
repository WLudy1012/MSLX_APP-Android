package com.wludy.rolithax.launcher.ui.connect

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.wludy.rolithax.launcher.R

/** 竖屏扫码页：复用 ZXing 解码能力，只替换默认横屏布局和操作层。 */
class PairingScanActivity : CaptureActivity() {
    override fun initializeContent(): DecoratedBarcodeView {
        setContentView(R.layout.activity_pairing_scan)
        return findViewById(R.id.zxing_barcode_scanner)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        findViewById<android.view.View>(R.id.scan_close).setOnClickListener { finish() }
        val root = findViewById<View>(R.id.scan_root)
        val top = findViewById<View>(R.id.scan_top_bar)
        val bottom = findViewById<View>(R.id.scan_bottom_panel)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            top.setPadding(top.paddingLeft, 18.dpPx() + bars.top, top.paddingRight, top.paddingBottom)
            bottom.setPadding(bottom.paddingLeft, bottom.paddingTop, bottom.paddingRight, 28.dpPx() + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun Int.dpPx(): Int = (this * resources.displayMetrics.density).toInt()
}
