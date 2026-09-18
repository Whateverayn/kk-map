package io.github.whateverayn.kkmap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.whateverayn.kkmap.core.map.KkMap
import io.github.whateverayn.kkmap.settings.AppSettings
import io.github.whateverayn.kkmap.ui.map.MapScreen
import io.github.whateverayn.kkmap.ui.theme.KkMapTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        KkMap.init(this)
        val appSettings = AppSettings(this)
        setContent {
            KkMapTheme {
                MapScreen(appSettings)
            }
        }
    }
}
