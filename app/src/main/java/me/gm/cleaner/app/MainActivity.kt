package me.gm.cleaner.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import me.gm.cleaner.client.ui.ServiceSettingsActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, ServiceSettingsActivity::class.java))
        finish()
    }
}
