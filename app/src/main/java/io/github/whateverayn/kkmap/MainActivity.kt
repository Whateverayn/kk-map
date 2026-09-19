package io.github.whateverayn.kkmap

import android.app.Activity
import android.app.PictureInPictureParams
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.whateverayn.kkmap.core.map.KkMap
import io.github.whateverayn.kkmap.settings.AppSettings
import io.github.whateverayn.kkmap.settings.DataUsage
import io.github.whateverayn.kkmap.settings.PipAspect
import io.github.whateverayn.kkmap.ui.map.MapScreen
import io.github.whateverayn.kkmap.ui.theme.KkMapTheme

class MainActivity : ComponentActivity() {
    /** ピクチャーインピクチャー (PiP) の小窓で表示中か */
    private var inPip by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        DataUsage.markStart()
        KkMap.init(this)
        val appSettings = AppSettings(this)

        // 自動で PiP に入るかどうかと小窓の形は, MapScreen が追従の状態と設定に合わせて更新する
        inPip = isInPictureInPictureMode
        addOnPictureInPictureModeChangedListener { inPip = it.isInPictureInPictureMode }

        setContent {
            KkMapTheme {
                MapScreen(appSettings, inPip = inPip)
            }
        }
    }
}

/**
 * ピクチャーインピクチャー (PiP) の設定を更新する.
 * [autoEnter] が true なら, ホームに戻ったり他のアプリに切り替えたりしたときに自動で PiP の小窓になる (Android 12 以降).
 * 他のアプリを使っている間も地図と現在地を見られるようにするため
 */
fun Activity.updatePip(autoEnter: Boolean, aspect: PipAspect) {
    setPictureInPictureParams(
        PictureInPictureParams.Builder()
            .setAspectRatio(Rational(aspect.width, aspect.height))
            .setAutoEnterEnabled(autoEnter)
            .setSeamlessResizeEnabled(true)
            .build()
    )
}
