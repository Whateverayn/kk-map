package io.github.whateverayn.kkmap.settings

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.os.Process
import java.io.File
import java.math.BigDecimal
import java.math.MathContext

/** 受信・送信のバイト数 */
data class Traffic(val rx: Long, val tx: Long) {
    val total: Long get() = rx + tx
    operator fun plus(other: Traffic) = Traffic(rx + other.rx, tx + other.tx)
}

/** ある期間の通信量 (Wi-Fi とモバイル通信の内訳) */
data class PeriodUsage(val wifi: Traffic, val mobile: Traffic) {
    val total: Traffic get() = wifi + mobile
}

/** 通信量と地図キャッシュの大きさ (設定画面に出す) */
data class DataUsageSnapshot(
    /** アプリを起動してから */
    val session: Traffic,
    /** 端末を起動してから (OS の集計で, 端末の再起動で 0 に戻る) */
    val sinceBoot: Traffic,
    /** MapLibre の地図キャッシュ (mbgl-offline.db) の大きさ (バイト). 無ければ 0 */
    val mapCacheBytes: Long,
)

/** 期間ごとの通信量. 取得できなければ null (OS の記録が無いなど) */
data class DataUsageHistory(
    val last24Hours: PeriodUsage?,
    val last30Days: PeriodUsage?,
    val sinceInstall: PeriodUsage?,
    /** インストールしてからの日数 (平均の計算用) */
    val daysSinceInstall: Double,
)

/**
 * このアプリの通信量.
 *
 * - 起動してから / 端末を起動してから: TrafficStats (アプリ (UID) ごとの, 端末起動からの累計). アプリ起動時の値との差を "起動してから" にする
 * - 期間ごと: NetworkStatsManager (OS が記録している UID ごとの履歴). 自分のアプリの分は権限なしで取れる.
 *   OS が履歴を持っているのは直近の一定期間だけなので, "インストールしてから" はその範囲内の合計になる
 *
 * 地図タイル以外 (スタイルの文字・アイコンの取得など) も含めた, アプリの通信すべてが対象.
 * 位置情報の測位は Google Play 開発者サービスが行うので含まれない.
 */
object DataUsage {
    private val uid = Process.myUid()
    private var start: Traffic? = null

    /** アプリの起動時に1回呼ぶ (2回目以降は何もしない) */
    fun markStart() {
        if (start == null) start = uidTraffic()
    }

    fun snapshot(context: Context): DataUsageSnapshot {
        val now = uidTraffic()
        val s = start ?: now
        return DataUsageSnapshot(
            session = Traffic((now.rx - s.rx).coerceAtLeast(0), (now.tx - s.tx).coerceAtLeast(0)),
            sinceBoot = now,
            mapCacheBytes = File(context.filesDir, MAP_CACHE_FILE).takeIf { it.exists() }?.length() ?: 0,
        )
    }

    /** 期間ごとの通信量. OS への問い合わせでブロックするので, メインスレッド以外から呼ぶこと */
    fun history(context: Context): DataUsageHistory {
        val manager = context.getSystemService(NetworkStatsManager::class.java)
        val now = System.currentTimeMillis()
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
        fun period(from: Long) = runCatching {
            PeriodUsage(
                wifi = query(manager, ConnectivityManager.TYPE_WIFI, from, now),
                mobile = query(manager, ConnectivityManager.TYPE_MOBILE, from, now),
            )
        }.getOrNull()
        return DataUsageHistory(
            last24Hours = period(now - HOUR_MILLIS * 24),
            last30Days = period(now - DAY_MILLIS * 30),
            sinceInstall = period(installed),
            daysSinceInstall = (now - installed).toDouble() / DAY_MILLIS,
        )
    }

    @Suppress("DEPRECATION") // TYPE_WIFI / TYPE_MOBILE は NetworkStatsManager の引数としては現役
    private fun query(manager: NetworkStatsManager, networkType: Int, from: Long, to: Long): Traffic {
        var rx = 0L
        var tx = 0L
        manager.queryDetailsForUid(networkType, null, from, to, uid).use { stats ->
            val bucket = NetworkStats.Bucket()
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                rx += bucket.rxBytes
                tx += bucket.txBytes
            }
        }
        return Traffic(rx, tx)
    }

    private fun uidTraffic() = Traffic(
        TrafficStats.getUidRxBytes(uid).coerceAtLeast(0),
        TrafficStats.getUidTxBytes(uid).coerceAtLeast(0),
    )

    /** バイト数を 2 進の単位 (1 KiB = 1024 B) で, 有効数字 4 桁で表す. 例: 12.34 MiB, 1.234 GiB */
    fun format(bytes: Double): String {
        var value = bytes
        var unit = 0
        while (value >= 1024 && unit < UNITS.lastIndex) {
            value /= 1024
            unit++
        }
        if (unit == 0) return "${bytes.toLong()} B"
        val rounded = BigDecimal(value).round(MathContext(4)).toPlainString()
        return "$rounded ${UNITS[unit]}"
    }

    fun format(bytes: Long): String = format(bytes.toDouble())

    private val UNITS = listOf("B", "KiB", "MiB", "GiB", "TiB")
    private const val MAP_CACHE_FILE = "mbgl-offline.db"
    private const val HOUR_MILLIS = 60 * 60 * 1000L
    private const val DAY_MILLIS = 24 * HOUR_MILLIS
}
