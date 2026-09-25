package com.mslx.console.ui.connect

import android.os.Bundle
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.mslx.console.R

/** 竖屏扫码页：复用 ZXing 解码能力，只替换默认横屏布局和操作层。 */
class PairingScanActivity : CaptureActivity() {
    override fun initializeContent(): DecoratedBarcodeView {
        setContentView(R.layout.activity_pairing_scan)
        return findViewById(R.id.zxing_barcode_scanner)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        findViewById<android.view.View>(R.id.scan_close).setOnClickListener { finish() }
    }
}
