package io.github.whateverayn.kkmap.location

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 画面回転などで Activity が作り直されても手動位置とシミュレーションを保つため, プロセス単位で持つ */
object DebugLocation {
    val manual = ManualLocation()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
}
