package com.aloyo.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/**
 * MediaProjection授权辅助Activity
 * 用于获取截屏授权并返回结果
 */
class MediaProjectionAuthActivity : Activity() {

    companion object {
        private const val TAG = "MediaProjectionAuth"
        private const val REQUEST_MEDIA_PROJECTION = 2001

        /**
         * 创建启动Intent
         */
        fun createIntent(context: Context): Intent {
            return Intent(context, MediaProjectionAuthActivity::class.java)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 立即请求MediaProjection授权
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = projectionManager.createScreenCaptureIntent()
        startActivityForResult(intent, REQUEST_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                // 授权成功，将结果发送回主界面
                val resultIntent = Intent().apply {
                    putExtra("result_code", resultCode)
                    putExtra("result_data", data)
                }
                setResult(RESULT_OK, resultIntent)
            } else {
                setResult(RESULT_CANCELED)
            }
            finish()
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }
}
