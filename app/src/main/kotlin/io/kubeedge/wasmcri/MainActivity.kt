package io.kubeedge.wasmcri

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.io.File

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= 33) {
            val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            if (granted != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001
                )
            }
        }

        val socketPath = File(filesDir, "cri.sock").absolutePath
        findViewById<TextView>(R.id.socket_path).text = "unix://$socketPath"

        findViewById<Button>(R.id.btn_start).setOnClickListener {
            CriForegroundService.start(this)
        }
        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            CriForegroundService.stop(this)
        }
    }
}
