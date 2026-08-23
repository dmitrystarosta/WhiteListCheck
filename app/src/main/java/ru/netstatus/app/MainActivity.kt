package ru.netstatus.app

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.TelephonyManager
import android.text.format.DateFormat
import android.view.View
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

const val REPO_RELEASES = "https://github.com/dmitrystarosta/WhiteListCheck/releases"
const val RUSTORE_URL = "https://www.rustore.ru/catalog/app/ru.netstatus.app"
const val SITE_URL = "https://belyjspisok.ru/"
const val REPO_URL = "https://github.com/dmitrystarosta/WhiteListCheck"

// ---------- Модель данных ----------

data class Probe(val name: String, val url: String)
data class ProbeResult(val probe: Probe, val ok: Boolean, val ms: Long, val note: String)

enum class Verdict { NO_INTERNET, WHITELIST, NORMAL, VPN_OR_ABROAD, UNKNOWN }

data class ScanState(
    val running: Boolean = false,
    val networkType: String = "",
    // Имя оператора мобильной сети; пусто, если сеть не мобильная или нет SIM.
    val operator: String = "",
    val verdict: Verdict? = null,
    val groupA: List<ProbeResult> = emptyList(), // белый список / всегда доступные
    val groupB: List<ProbeResult> = emptyList(), // обычный интернет вне списка
    val groupC: List<ProbeResult> = emptyList(), // заблокированные (контроль)
    val configSource: String = "встроенный список",
    // Время (мс), когда получен ЭТОТ результат. 0 = экран ещё ничего не показывал.
    // Нужен, чтобы при возврате в приложение отличить свежий внутренний результат
    // от более новой проверки, сделанной снаружи (виджет / фон).
    val checkedAt: Long = 0L
)

// ---------- Конфигурация проб ----------

object ProbeConfig {
    // Базовый список зашит в приложение — работает даже когда сервер конфига недоступен.
    val defaultA = listOf(
        Probe("Яндекс", "https://ya.ru/favicon.ico"),
        Probe("ВКонтакте", "https://vk.com/favicon.ico"),
        Probe("Госуслуги", "https://www.gosuslugi.ru/favicon.ico"),
        Probe("Mail.ru", "https://mail.ru/favicon.ico")
    )
    val defaultB = listOf(
        Probe("Habr", "https://habr.com/favicon.ico"),
        Probe("4PDA", "https://4pda.to/favicon.ico"),
        Probe("Google", "https://www.google.com/favicon.ico"),
        Probe("Википедия", "https://ru.wikipedia.org/favicon.ico")
    )
    val defaultC = listOf(
        Probe("Instagram*", "https://www.instagram.com/favicon.ico"),
        Probe("X (Twitter)", "https://x.com/favicon.ico"),
        Probe("Rutracker", "https://rutracker.org/favicon.ico")
    )

    // URL обновляемого конфига. Разместите JSON на хостинге, который сам входит
    // в белый список, иначе в момент ограничений обновление не скачается.
    const val REMOTE_CONFIG_URL = ""

    // Формат JSON: {"a":[{"name":"...","url":"..."}], "b":[...], "c":[...]}
    fun parse(json: String): Triple<List<Probe>, List<Probe>, List<Probe>>? = try {
        val o = JSONObject(json)
        fun arr(key: String) = o.getJSONArray(key).let { a ->
            (0 until a.length()).map {
                val p = a.getJSONObject(it)
                Probe(p.getString("name"), p.getString("url"))
            }
        }
        Triple(arr("a"), arr("b"), arr("c"))
    } catch (e: Exception) { null }
}

// ---------- Пользовательские списки (хранятся в SharedPreferences) ----------

object ProbeStore {
    private const val PREFS = "netstatus"
    private const val KEY = "custom_lists"

    // Возвращает пользовательские списки, а если их нет или они повреждены — встроенные.
    fun load(ctx: Context): Triple<List<Probe>, List<Probe>, List<Probe>> {
        val json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
            ?: return Triple(ProbeConfig.defaultA, ProbeConfig.defaultB, ProbeConfig.defaultC)
        return ProbeConfig.parse(json)
            ?: Triple(ProbeConfig.defaultA, ProbeConfig.defaultB, ProbeConfig.defaultC)
    }

    fun save(ctx: Context, a: List<Probe>, b: List<Probe>, c: List<Probe>) {
        fun arr(list: List<Probe>) = JSONArray().apply {
            list.forEach { put(JSONObject().put("name", it.name).put("url", it.url)) }
        }
        val json = JSONObject().put("a", arr(a)).put("b", arr(b)).put("c", arr(c)).toString()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, json).apply()
    }

    fun reset(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    fun isCustom(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY)
}

// Превращает введённый пользователем домен в пробу.
// Принимает «pikabu.ru», «https://pikabu.ru», «pikabu.ru/что-угодно».
// Возвращает null, если строка не похожа на домен.
fun probeFromDomain(input: String): Probe? {
    val d = input.trim().lowercase()
        .removePrefix("https://").removePrefix("http://")
        .removePrefix("www.").substringBefore("/")
    val domainRegex = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")
    return if (domainRegex.matches(d)) Probe(d, "https://$d/favicon.ico") else null
}

// ---------- Сетевые проверки ----------

object Scanner {

    // Лёгкий HTTPS-запрос. Считаем сайт доступным, если получили любой HTTP-ответ.
    private suspend fun check(probe: Probe): ProbeResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val conn = URL(probe.url).openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.requestMethod = "HEAD"
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            conn.disconnect()
            ProbeResult(probe, true, System.currentTimeMillis() - start, "HTTP $code")
        } catch (e: Exception) {
            ProbeResult(probe, false, System.currentTimeMillis() - start, humanError(e))
        }
    }

    // Переводим технические исключения на человеческий язык
    private fun humanError(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "адрес не найден (DNS)"
        is java.net.SocketTimeoutException -> "нет ответа (таймаут)"
        is java.net.ConnectException -> "соединение сброшено"
        is javax.net.ssl.SSLException -> "ошибка шифрования (TLS)"
        else -> e.javaClass.simpleName
    }

    suspend fun scanGroup(probes: List<Probe>): List<ProbeResult> = coroutineScope {
        probes.map { async { check(it) } }.awaitAll()
    }

    fun networkType(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "нет сети"
        return when {
            // VPN проверяется ПЕРВЫМ: начиная с Android 12 сеть VPN сообщает
            // и свой «нижележащий» транспорт (Wi-Fi или мобильный), поэтому
            // при проверке в другом порядке ветка VPN никогда не сработает.
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "мобильный интернет"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet (кабель)"
            else -> "другое"
        }
    }

    // Имя оператора мобильной сети (например «Tele2», «МТС»). Не требует
    // опасных разрешений — getNetworkOperatorName() доступен без READ_PHONE_STATE.
    // Возвращает имя ОБСЛУЖИВАЮЩЕЙ сети (учитывает роуминг), а не SIM.
    // ВАЖНО: вызывать только когда реально мобильный интернет — при Wi-Fi
    // со вставленной SIM метод всё равно вернёт оператора, и он ложно вылез бы
    // в чипе. Гейт по типу сети делается на стороне вызова (см. runScan).
    fun operatorName(context: Context): String = try {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        tm.networkOperatorName?.trim().orEmpty()
    } catch (e: Exception) { "" }

    // Вердикт по большинству: один упавший сайт не должен давать ложную тревогу.
    fun verdict(a: List<ProbeResult>, b: List<ProbeResult>, c: List<ProbeResult>): Verdict {
        val aOk = a.count { it.ok } >= (a.size + 1) / 2
        val bOk = b.count { it.ok } >= (b.size + 1) / 2
        val cOk = c.count { it.ok } >= (c.size + 1) / 2
        return when {
            !aOk && !bOk -> Verdict.NO_INTERNET
            aOk && bOk && cOk -> Verdict.VPN_OR_ABROAD // даже заблокированные открылись
            aOk && !bOk -> Verdict.WHITELIST
            aOk && bOk -> Verdict.NORMAL
            else -> Verdict.UNKNOWN
        }
    }
}

// ---------- Фоновая проверка (WorkManager) ----------

// ---------- Виджет-индикатор на рабочем столе ----------

// Обновляет все размещённые виджеты по данным из SharedPreferences
// (last_verdict + last_check_ts). Вызывается после каждой проверки —
// ручной, фоновой и запущенной тапом по виджету — а также по системному
// расписанию и в onResume приложения.
object StatusWidgetUpdater {

    // Иконка-логотип в цвете последнего вердикта.
    private fun iconFor(verdictName: String?): Int = when (verdictName) {
        Verdict.NORMAL.name -> R.drawable.widget_logo_normal
        Verdict.WHITELIST.name -> R.drawable.widget_logo_whitelist
        Verdict.VPN_OR_ABROAD.name -> R.drawable.widget_logo_vpn
        Verdict.NO_INTERNET.name -> R.drawable.widget_logo_nonet
        else -> R.drawable.widget_logo_neutral  // не проверялось / UNKNOWN
    }

    // Тап по виджету НЕ открывает приложение, а запускает проверку в фоне
    // (широковещательное сообщение самому себе → StatusWidget.onReceive).
    // Так виджет становится кнопкой «перепроверить», а не дублёром иконки.
    private fun scanPendingIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, StatusWidget::class.java)
            .setAction(StatusWidget.ACTION_WIDGET_SCAN)
        return PendingIntent.getBroadcast(
            ctx, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun update(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, StatusWidget::class.java))
        if (ids.isEmpty()) return  // виджет не добавлен на стол — нечего обновлять

        val prefs = ctx.getSharedPreferences("netstatus", Context.MODE_PRIVATE)
        val verdictName = prefs.getString("last_verdict", null)
        val ts = prefs.getLong("last_check_ts", 0L)
        val icon = iconFor(verdictName)

        // АБСОЛЮТНОЕ время последней проверки («9:15»), а не «N мин назад».
        // RemoteViews — статический снимок: относительный счётчик сам по себе
        // не «тикает», ему нужно перерисовываться каждую минуту, а MIUI такие
        // фоновые обновления душит (отсюда прежнее вечное «сейчас»).
        // Часы же остаются верны без единого обновления.
        val timeText = when {
            ts == 0L || verdictName == null -> "нажмите"
            else -> DateFormat.format("H:mm", ts).toString()
        }
        // Данные старше часа считаем устаревшими: виджет бледнеет, время
        // подсвечивается янтарным (MIUI мог убить фоновый воркер).
        val ageMin = if (ts == 0L) -1L else (System.currentTimeMillis() - ts) / 60000L
        val stale = ts != 0L && ageMin >= 60

        for (id in ids) {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_status)
            rv.setImageViewResource(R.id.widget_icon, icon)
            rv.setTextViewText(R.id.widget_time, timeText)
            rv.setInt(R.id.widget_icon, "setImageAlpha", if (stale) 130 else 255)
            rv.setTextColor(
                R.id.widget_time,
                if (stale) 0xFFFFD180.toInt() else 0xFFEAD9CF.toInt()
            )
            // Обычное состояние: видно время, спиннер скрыт (на случай, если
            // предыдущий кадр был «идёт проверка»).
            rv.setViewVisibility(R.id.widget_time, View.VISIBLE)
            rv.setViewVisibility(R.id.widget_progress, View.GONE)
            rv.setOnClickPendingIntent(R.id.widget_root, scanPendingIntent(ctx))
            mgr.updateAppWidget(id, rv)
        }
    }

    // Мгновенная обратная связь на тап: вместо подписи времени показываем
    // крутящийся индикатор (штатный ProgressBar анимируется сам). Цвет
    // иконки оставляем прежним (перечитываем вердикт из prefs), чтобы
    // логотип не мигал в нейтральный на время проверки.
    fun showChecking(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, StatusWidget::class.java))
        if (ids.isEmpty()) return

        val prefs = ctx.getSharedPreferences("netstatus", Context.MODE_PRIVATE)
        val icon = iconFor(prefs.getString("last_verdict", null))

        for (id in ids) {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_status)
            rv.setImageViewResource(R.id.widget_icon, icon)
            rv.setInt(R.id.widget_icon, "setImageAlpha", 255)
            rv.setViewVisibility(R.id.widget_time, View.GONE)
            rv.setViewVisibility(R.id.widget_progress, View.VISIBLE)
            rv.setOnClickPendingIntent(R.id.widget_root, scanPendingIntent(ctx))
            mgr.updateAppWidget(id, rv)
        }
    }
}

// Приёмник системных событий виджета: добавление на стол, периодическое
// обновление по updatePeriodMillis из widget_info.xml, и наш тап-по-виджету
// (ACTION_WIDGET_SCAN) — запуск разовой проверки в фоне.
class StatusWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        StatusWidgetUpdater.update(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)  // не ломаем штатную обработку onUpdate/onDeleted
        if (intent.action == ACTION_WIDGET_SCAN) {
            // Сразу показываем крутящийся индикатор, затем запускаем разовый
            // воркер. Без сетевых ограничений (constraint): при отсутствии сети
            // воркер сам быстро вернёт «нет сети», иначе спиннер крутился бы вечно.
            StatusWidgetUpdater.showChecking(context)
            val req = OneTimeWorkRequestBuilder<WidgetScanWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "widgetscan", ExistingWorkPolicy.REPLACE, req
            )
        }
    }

    companion object {
        const val ACTION_WIDGET_SCAN = "ru.netstatus.app.WIDGET_SCAN"
    }
}

// Разовая проверка, запускаемая тапом по виджету. В отличие от фонового
// CheckWorker, НИКОГДА не шлёт уведомление (пользователь и так смотрит
// на виджет) и всегда записывает результат + время и обновляет виджет,
// включая случай «нет сети» (как ручная проверка в приложении).
class WidgetScanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences("netstatus", Context.MODE_PRIVATE)

        if (Scanner.networkType(ctx) == "нет сети") {
            prefs.edit()
                .putString("last_verdict", Verdict.NO_INTERNET.name)
                .putLong("last_check_ts", System.currentTimeMillis())
                .apply()
            StatusWidgetUpdater.update(ctx)
            return Result.success()
        }

        val (la, lb, lc) = ProbeStore.load(ctx)
        val a = Scanner.scanGroup(la)
        val b = Scanner.scanGroup(lb)
        val c = Scanner.scanGroup(lc)
        val verdict = Scanner.verdict(a, b, c)

        prefs.edit()
            .putString("last_verdict", verdict.name)
            .putLong("last_check_ts", System.currentTimeMillis())
            .apply()
        StatusWidgetUpdater.update(ctx)
        return Result.success()
    }
}

class CheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (Scanner.networkType(ctx) == "нет сети") return Result.success()
        // Фоновая проверка использует те же списки, что и ручная,
        // включая пользовательские правки.
        val (la, lb, lc) = ProbeStore.load(ctx)
        val a = Scanner.scanGroup(la)
        val b = Scanner.scanGroup(lb)
        val c = Scanner.scanGroup(lc)
        val verdict = Scanner.verdict(a, b, c)

        val prefs = ctx.getSharedPreferences("netstatus", Context.MODE_PRIVATE)
        val prev = prefs.getString("last_verdict", null)
        if (prev != null && prev != verdict.name) {
            notifyChange(ctx, verdict)
        }
        prefs.edit()
            .putString("last_verdict", verdict.name)
            .putLong("last_check_ts", System.currentTimeMillis())
            .apply()
        StatusWidgetUpdater.update(ctx)
        return Result.success()
    }

    private fun notifyChange(ctx: Context, v: Verdict) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val text = when (v) {
            Verdict.WHITELIST -> "Похоже, включён режим белого списка"
            Verdict.NORMAL -> "Ограничения сняты: интернет работает как обычно"
            Verdict.NO_INTERNET -> "Интернет пропал полностью"
            Verdict.VPN_OR_ABROAD -> "Открывается всё подряд: похоже, включён VPN"
            else -> "Режим сети изменился"
        }
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("netmode", "Режим сети", NotificationManager.IMPORTANCE_DEFAULT)
        )
        // Открываем приложение ТЕМ ЖЕ intent'ом, что и иконка в лаунчере
        // (ACTION_MAIN/LAUNCHER + флаги RESET_TASK_IF_NEEDED): существующая
        // задача поднимается на передний план без пересоздания — результаты
        // прошлой проверки сохраняются. Прямой Intent(MainActivity) этого
        // не гарантировал и открывал экран в сброшенном виде.
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?: Intent(ctx, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            ctx, 0, launch, PendingIntent.FLAG_IMMUTABLE
        )
        val notif = android.app.Notification.Builder(ctx, "netmode")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Белый список?")
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(1, notif)
    }
}

fun scheduleBackground(ctx: Context) {
    val request = PeriodicWorkRequestBuilder<CheckWorker>(15, TimeUnit.MINUTES)
        .setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        )
        .build()
    WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
        "netcheck", ExistingPeriodicWorkPolicy.UPDATE, request
    )
}

fun cancelBackground(ctx: Context) {
    WorkManager.getInstance(ctx).cancelUniqueWork("netcheck")
}

// ---------- Тема оформления (v0.4) ----------
// Палитра выведена из фирменной иконки «чебурнет»: тёплые коричневые тона.
// Шрифт — Golos Text (файлы в res/font, лицензия OFL, FONT_LICENSE.txt в корне).

val Golos = FontFamily(
    Font(R.font.golos_text_regular, FontWeight.Normal),
    Font(R.font.golos_text_medium, FontWeight.Medium),
    Font(R.font.golos_text_semibold, FontWeight.SemiBold),
    Font(R.font.golos_text_bold, FontWeight.Bold)
)

private fun golosTypography(): Typography {
    val b = Typography()
    return Typography(
        displayLarge = b.displayLarge.copy(fontFamily = Golos),
        displayMedium = b.displayMedium.copy(fontFamily = Golos),
        displaySmall = b.displaySmall.copy(fontFamily = Golos),
        headlineLarge = b.headlineLarge.copy(fontFamily = Golos),
        headlineMedium = b.headlineMedium.copy(fontFamily = Golos),
        headlineSmall = b.headlineSmall.copy(fontFamily = Golos),
        titleLarge = b.titleLarge.copy(fontFamily = Golos),
        titleMedium = b.titleMedium.copy(fontFamily = Golos),
        titleSmall = b.titleSmall.copy(fontFamily = Golos),
        bodyLarge = b.bodyLarge.copy(fontFamily = Golos),
        bodyMedium = b.bodyMedium.copy(fontFamily = Golos),
        bodySmall = b.bodySmall.copy(fontFamily = Golos),
        labelLarge = b.labelLarge.copy(fontFamily = Golos),
        labelMedium = b.labelMedium.copy(fontFamily = Golos),
        labelSmall = b.labelSmall.copy(fontFamily = Golos)
    )
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF6D4C41),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBCB),
    onPrimaryContainer = Color(0xFF33150A),
    secondary = Color(0xFF77574A),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFFF8F5),
    onBackground = Color(0xFF221A16),
    surface = Color(0xFFFFF8F5),
    onSurface = Color(0xFF221A16),
    surfaceVariant = Color(0xFFF4E4DC),
    onSurfaceVariant = Color(0xFF52443C),
    outline = Color(0xFF85736B)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE8BBA4),
    onPrimary = Color(0xFF44291B),
    primaryContainer = Color(0xFF5D4037),
    onPrimaryContainer = Color(0xFFFFDBCB),
    secondary = Color(0xFFE7BDAD),
    onSecondary = Color(0xFF44291E),
    background = Color(0xFF1A120E),
    onBackground = Color(0xFFF0DFD7),
    surface = Color(0xFF1A120E),
    onSurface = Color(0xFFF0DFD7),
    surfaceVariant = Color(0xFF2A201B),
    onSurfaceVariant = Color(0xFFD7C2B8),
    outline = Color(0xFFA08D84)
)

// Пары «текст + фон» для статусов; свои для светлой и тёмной темы.
data class StatusColors(val content: Color, val container: Color)

// Единый ответ на вопрос «показывать ли тёмную тему».
// На Android TV тема всегда тёмная: у многих ТВ нет системного тёмного
// режима, а белый экран на большой диагонали некомфортен.
// На телефоне — по системной настройке, как раньше.
@Composable
fun isAppDark(): Boolean {
    val context = LocalContext.current
    val isTv = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }
    return isTv || isSystemInDarkTheme()
}

@Composable
fun verdictColors(v: Verdict?): StatusColors {
    val dark = isAppDark()
    return when (v) {
        Verdict.NORMAL ->
            if (dark) StatusColors(Color(0xFF8BD49C), Color(0xFF1E3B26))
            else StatusColors(Color(0xFF1E6B2A), Color(0xFFDDF3DF))
        Verdict.WHITELIST ->
            if (dark) StatusColors(Color(0xFFF2A099), Color(0xFF4A201C))
            else StatusColors(Color(0xFFB3241E), Color(0xFFFBE2E0))
        Verdict.NO_INTERNET ->
            if (dark) StatusColors(Color(0xFFBDBDBD), Color(0xFF2C2C2C))
            else StatusColors(Color(0xFF5A5A5A), Color(0xFFECECEC))
        Verdict.VPN_OR_ABROAD ->
            if (dark) StatusColors(Color(0xFF9CC3F5), Color(0xFF1D3250))
            else StatusColors(Color(0xFF175CA8), Color(0xFFE0ECFA))
        Verdict.UNKNOWN ->
            if (dark) StatusColors(Color(0xFFF2CE6B), Color(0xFF42371A))
            else StatusColors(Color(0xFF8A6A00), Color(0xFFF6EBCF))
        null -> StatusColors(
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

// Цвета круглого значка статуса сайта (галочка / крестик)
@Composable
fun statusBadgeColors(ok: Boolean): StatusColors {
    val dark = isAppDark()
    return if (ok) {
        if (dark) StatusColors(Color(0xFF8BD49C), Color(0xFF1E3B26))
        else StatusColors(Color(0xFF1E6B2A), Color(0xFFDDF3DF))
    } else {
        if (dark) StatusColors(Color(0xFFF2A099), Color(0xFF4A201C))
        else StatusColors(Color(0xFFB3241E), Color(0xFFFBE2E0))
    }
}

@Composable
fun warnColor(): Color =
    if (isAppDark()) Color(0xFFF2CE6B) else Color(0xFF8A6A00)

@Composable
fun dangerColor(): Color =
    if (isAppDark()) Color(0xFFF2A099) else Color(0xFFB3241E)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isAppDark()
    val colors = if (dark) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = colors.background.toArgb()
                window.navigationBarColor = colors.background.toArgb()
                val insets = WindowCompat.getInsetsController(window, view)
                insets.isAppearanceLightStatusBars = !dark
                insets.isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = colors, typography = golosTypography(), content = content)
}

// ---------- Подсветка фокуса для пульта (Android TV) ----------
// Выбранный пультом элемент получает заметную рамку и лёгкий фон.
// На телефоне не проявляется: там фокус не «ходит» стрелками.
// onFocusChanged должен стоять ДО clickable в цепочке модификаторов.

@Composable
fun Modifier.tvFocusHighlight(shape: Shape = RoundedCornerShape(10.dp)): Modifier {
    var focused by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    return this
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
        .then(
            if (focused)
                Modifier
                    .border(2.dp, accent, shape)
                    .background(accent.copy(alpha = 0.12f), shape)
            else Modifier
        )
}

// ---------- UI ----------

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { App() } }
    }

    // Каждое открытие приложения освежает виджет (третий канал обновления
    // надписи времени — помимо проверок и системного тика раз в 30 мин).
    override fun onResume() {
        super.onResume()
        StatusWidgetUpdater.update(this)
    }
}

@Composable
fun App() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("netstatus", Context.MODE_PRIVATE) }
    var showSettings by remember { mutableStateOf(false) }
    // Экран «Как остаться на связи?» — переоткрывается из подвала (та же
    // инструкция, что и второй шаг онбординга).
    var showHelp by remember { mutableStateOf(false) }
    // Онбординг — только на по-настоящему первой установке и только один раз.
    // Показываем, пока не выставлен флаг onboarded. Флаг живёт в SharedPreferences,
    // а прежняя проверка «свежести» по времени установки (firstInstallTime ==
    // lastUpdateTime) убрана: она была костылём против восстановления prefs из
    // системного бэкапа, но сама ложно срабатывала (на части прошивок времена
    // различаются даже на чистой установке, и очистка данных их не сбрасывает —
    // онбординг не показывался). Теперь бэкап отключён в манифесте
    // (allowBackup="false"), поэтому при удалении/очистке данных prefs реально
    // стираются, флаг onboarded обнуляется и логика работает предсказуемо:
    // чистая установка или очистка данных → онбординг; обновление поверх → нет.
    // На Android TV не показываем вовсе (экран рассчитан на телефон; пультом
    // разрешение уведомлений и настройки батареи вводить неудобно).
    val isTv = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }
    var showOnboarding by remember {
        mutableStateOf(!prefs.getBoolean("onboarded", false) && !isTv)
    }
    // Состояние проверки живёт на уровне App, а НЕ внутри MainScreen:
    // при переходе в «Списки сайтов» MainScreen целиком покидает композицию,
    // и всё его remember-состояние уничтожается. Если бы результаты хранились
    // в MainScreen, они сбрасывались бы при каждом заходе в настройки.
    val scanState = remember { mutableStateOf(ScanState()) }
    // Scope для сканирования — тоже уровня App: rememberCoroutineScope
    // внутри MainScreen отменяется вместе с его уходом из композиции,
    // и запущенная проверка обрывалась бы на полпути, оставив вечное
    // «Проверяю…» (running=true снять было бы уже некому). Со scope уровня
    // App проверка спокойно доработает, пока пользователь в настройках.
    val appScope = rememberCoroutineScope()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            showOnboarding -> OnboardingFlow(
                onFinish = {
                    prefs.edit().putBoolean("onboarded", true).apply()
                    showOnboarding = false
                }
            )
            showSettings -> SettingsScreen(onBack = { showSettings = false })
            showHelp -> ConnectivityHelpScreen(onDone = { showHelp = false }, showBack = true)
            else -> MainScreen(
                scanState = scanState,
                scope = appScope,
                onOpenSettings = { showSettings = true },
                onOpenHelp = { showHelp = true }
            )
        }
    }
}

@Composable
fun MainScreen(
    scanState: MutableState<ScanState>,
    scope: CoroutineScope,
    onOpenSettings: () -> Unit,
    onOpenHelp: () -> Unit
) {
    val context = LocalContext.current
    var state by scanState
    val prefs = remember { context.getSharedPreferences("netstatus", Context.MODE_PRIVATE) }
    var bgEnabled by remember { mutableStateOf(prefs.getBoolean("bg_enabled", false)) }

    // Состояния «развёрнуто» сворачиваемых блоков подняты на уровень экрана,
    // а НЕ хранятся внутри элементов LazyColumn. Уехавший за нижний край
    // элемент LazyColumn уничтожается вместе со своим remember-состоянием, и
    // его rememberSaveable восстанавливался неодинаково: на ТВ блок сворачивался
    // сразу при прокрутке за экран, на телефоне слетал после сворачивания
    // приложения. Чип сети «держался» лишь потому, что он первый элемент списка
    // и почти не уезжает. На уровне экрана состояние живёт стабильно — переживает
    // и прокрутку (в т.ч. фокусом пульта на ТВ), и возврат в приложение.
    // netExpanded ключуется типом сети: при смене сети пояснение сворачивается.
    var netExpanded by rememberSaveable(state.networkType) { mutableStateOf(false) }
    var whyExpanded by rememberSaveable { mutableStateOf(false) }
    var expA by rememberSaveable { mutableStateOf(true) }
    var expB by rememberSaveable { mutableStateOf(true) }
    var expC by rememberSaveable { mutableStateOf(true) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* результат не критичен: без разрешения просто не будет уведомлений */ }

    fun runScan() {
        scope.launch {
            state = state.copy(running = true, verdict = null)
            try {
                var a = ProbeConfig.defaultA
                var b = ProbeConfig.defaultB
                var c = ProbeConfig.defaultC
                var source = "встроенный список"

                if (ProbeStore.isCustom(context)) {
                    // Пользователь редактировал списки — они в приоритете.
                    val (ca, cb, cc) = ProbeStore.load(context)
                    a = ca; b = cb; c = cc
                    source = "пользовательский список"
                } else if (ProbeConfig.REMOTE_CONFIG_URL.isNotBlank()) {
                    withContext(Dispatchers.IO) {
                        try {
                            val json = URL(ProbeConfig.REMOTE_CONFIG_URL).readText()
                            ProbeConfig.parse(json)?.let { (na, nb, nc) ->
                                a = na; b = nb; c = nc
                                source = "обновлён с сервера"
                            }
                        } catch (_: Exception) { }
                    }
                }

                val net = Scanner.networkType(context)

                // Быстрый путь: сети нет — сетевые запросы не запускаем вовсе.
                // Без сети DNS-разрешение может висеть дольше connect-таймаута
                // (особенно на MIUI), что замораживало экран в «Сканирую…».
                if (net == "нет сети") {
                    val mark = { p: Probe -> ProbeResult(p, false, 0L, "нет сети") }
                    val now = System.currentTimeMillis()
                    prefs.edit()
                        .putString("last_verdict", Verdict.NO_INTERNET.name)
                        .putLong("last_check_ts", now)
                        // Счётчик завершённых проверок (для триггера карточки отзыва).
                        .putInt("scan_count", prefs.getInt("scan_count", 0) + 1)
                        .apply()
                    StatusWidgetUpdater.update(context)
                    state = ScanState(
                        running = false,
                        networkType = net,
                        verdict = Verdict.NO_INTERNET,
                        groupA = a.map(mark), groupB = b.map(mark), groupC = c.map(mark),
                        configSource = source,
                        checkedAt = now
                    )
                    return@launch
                }

                // Оператор читаем только для мобильной сети (на Wi-Fi со вставленной
                // SIM метод вернул бы имя, и оно ложно вылезло бы в чипе).
                val operator = if (net == "мобильный интернет") Scanner.operatorName(context) else ""

                val ra = Scanner.scanGroup(a)
                val rb = Scanner.scanGroup(b)
                val rc = Scanner.scanGroup(c)
                val verdict = Scanner.verdict(ra, rb, rc)

                // Запоминаем вердикт (для сравнения фоновой проверкой) и время
                // проверки (для виджета), затем обновляем виджет на рабочем столе.
                val now = System.currentTimeMillis()
                prefs.edit()
                    .putString("last_verdict", verdict.name)
                    .putLong("last_check_ts", now)
                    // Счётчик завершённых проверок (для триггера карточки отзыва).
                    .putInt("scan_count", prefs.getInt("scan_count", 0) + 1)
                    .apply()
                StatusWidgetUpdater.update(context)

                state = ScanState(
                    running = false,
                    networkType = net,
                    operator = operator,
                    verdict = verdict,
                    groupA = ra, groupB = rb, groupC = rc,
                    configSource = source,
                    checkedAt = now
                )
            } finally {
                // Страховка: что бы ни случилось выше (исключение, отмена),
                // флаг «идёт проверка» снимается — экран не может навсегда
                // зависнуть в «Сканирую…».
                if (state.running) state = state.copy(running = false)
            }
        }
    }

    // При возврате в приложение сверяемся с последней проверкой в хранилище.
    // Виджет и фоновый воркер пишут туда свой вердикт, но НЕ трогают то, что
    // показано на экране (детальные карточки живут в памяти). Если снаружи
    // была более свежая проверка с ДРУГИМ вердиктом (например, тап по виджету
    // в авиарежиме дал «нет сети», а на экране висит «всё в норме») — экран
    // врёт. Тогда молча перезапускаем проверку, чтобы карточки и вердикт
    // соответствовали реальности. Совпадающий вердикт не трогаем — лишний
    // скан ни к чему.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val storedTs = prefs.getLong("last_check_ts", 0L)
                val storedV = prefs.getString("last_verdict", null)
                val cur = state
                if (!cur.running && cur.verdict != null &&
                    storedV != null && storedTs > cur.checkedAt &&
                    storedV != cur.verdict.name
                ) {
                    runScan()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Компактная шапка и карточка вердикта закреплены сверху,
    // всё остальное прокручивается единым списком (фикс «трети экрана»).
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(10.dp))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Я в белых списках?",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onOpenSettings,
                // Сдвиг на 12dp вправо компенсирует внутренние поля IconButton
                // (значок 24dp в области 48dp): видимый край шестерёнки встаёт
                // на одну вертикаль с кнопкой «поделиться» и краем карточек.
                modifier = Modifier
                    .offset(x = 12.dp)
                    .tvFocusHighlight(CircleShape)
            ) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = "Настройки списков",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        VerdictCard(state)

        Spacer(Modifier.height(12.dp))

        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            // Чип сети скрыт во время проверки: тип сети в этот момент
            // перепроверяется, показывать старое значение нелогично.
            if (state.networkType.isNotEmpty() && !state.running) {
                item {
                    // Чип сети слева, кнопка «поделиться» справа.
                    // Alignment.Top — чтобы кнопка не уезжала вниз,
                    // когда раскрывается пояснение чипа.
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(Modifier.weight(1f).align(Alignment.CenterVertically)) {
                            NetworkChip(state.networkType, state.operator, netExpanded) { netExpanded = !netExpanded }
                        }
                        if (state.verdict != null) {
                            ShareVerdictButton(state)
                        }
                    }
                }
            }
            item {
                Button(
                    onClick = { runScan() },
                    enabled = !state.running,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .height(52.dp)
                        .tvFocusHighlight(RoundedCornerShape(14.dp))
                ) {
                    Text(
                        if (state.running) "Сканирую…" else "Проверить",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            if (state.verdict != null) {
                item {
                    Text(
                        "Конфиг: ${state.configSource}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    )
                }
            }
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 6.dp)
                ) {
                    Switch(
                        checked = bgEnabled,
                        modifier = Modifier.tvFocusHighlight(CircleShape),
                        onCheckedChange = { on ->
                            bgEnabled = on
                            prefs.edit().putBoolean("bg_enabled", on).apply()
                            if (on) {
                                if (Build.VERSION.SDK_INT >= 33) {
                                    permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                scheduleBackground(context)
                            } else {
                                cancelBackground(context)
                            }
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Фоновая проверка и уведомления",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
            if (state.groupA.isNotEmpty()) {
                item { GroupCard("Белый список (эталон доступности)", state.groupA, expA) { expA = !expA } }
            }
            if (state.groupB.isNotEmpty()) {
                item { GroupCard("Обычный интернет (вне списка)", state.groupB, expB) { expB = !expB } }
            }
            if (state.groupC.isNotEmpty()) {
                item { GroupCard("Заблокированные в РФ (контроль)", state.groupC, expC) { expC = !expC } }
                item { Footnote(whyExpanded) { whyExpanded = !whyExpanded } }
            }
            item { AppFooter(onOpenHelp, state.checkedAt) }
        }
    }
}

// Чип типа сети. Если для сети есть пояснение — рядом значок ⓘ,
// по тапу пояснение разворачивается и сворачивается.
@Composable
fun NetworkChip(networkType: String, operator: String, expanded: Boolean, onToggle: () -> Unit) {

    val detail: String?
    val color: Color
    when (networkType) {
        "нет сети" -> {
            detail = "Проверьте наличие интернета на вашем устройстве."
            color = dangerColor()
        }
        "VPN" -> {
            detail = "Похоже, включён VPN — проверка показывает то, что видно " +
                "через него, а не напрямую через вашего оператора. Чтобы узнать " +
                "реальное состояние сети, отключите VPN и повторите проверку."
            color = warnColor()
        }
        "Wi-Fi" -> {
            detail = "Белые списки обычно действуют на мобильном интернете. " +
                "Но если ваш Wi-Fi раздаёт 3G/4G-роутер, ограничения касаются и его."
            color = warnColor()
        }
        "Ethernet (кабель)" -> {
            detail = "Это домашний интернет, белые списки обычно действуют " +
                "на мобильном. Результат показывает состояние именно кабельного " +
                "подключения."
            color = warnColor()
        }
        else -> {
            detail = null
            color = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }

    // Для мобильной сети дописываем оператора: «мобильный интернет · Tele2».
    val label = if (operator.isNotBlank()) "Сеть: $networkType · $operator"
                else "Сеть: $networkType"

    Column {
        Surface(
            shape = RoundedCornerShape(50),
            color = Color.Transparent,
            border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
            modifier = Modifier
                .tvFocusHighlight(RoundedCornerShape(50))
                .clickable(enabled = detail != null) { onToggle() }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = color
                )
                if (detail != null) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = if (expanded) "Скрыть пояснение" else "Показать пояснение",
                        tint = color,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
        if (expanded && detail != null) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = color,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

// ---------- Кнопка «поделиться вердиктом» ----------

// Собирает человекочитаемый текст результата и открывает системное
// меню «Поделиться» (Telegram, WhatsApp, SMS и т.д.).
fun shareVerdict(context: Context, state: ScanState) {
    val verdictText = when (state.verdict) {
        Verdict.NORMAL -> "Всё в норме: ограничений не видно"
        Verdict.WHITELIST -> "Похоже, включён БЕЛЫЙ СПИСОК"
        Verdict.NO_INTERNET -> "Интернет недоступен вообще"
        Verdict.VPN_OR_ABROAD -> "Открывается всё подряд: похоже, включён VPN или вы вне РФ"
        else -> "Непонятная ситуация"
    }
    val time = java.text.SimpleDateFormat("dd.MM.yyyy 'в' HH:mm", java.util.Locale("ru"))
        .format(java.util.Date())
    val text = "$verdictText. Сеть: ${state.networkType}. " +
        "Проверено $time приложением „Белый список?“: $SITE_URL"
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Поделиться"))
}

// Круглая кнопка в стиле чипа сети: тонкая обводка, значок в цвете primary.
@Composable
fun ShareVerdictButton(state: ScanState) {
    val context = LocalContext.current
    Box(
        Modifier
            .tvFocusHighlight(CircleShape)
            .size(32.dp)
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), CircleShape)
            .clickable { shareVerdict(context, state) },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Outlined.Share,
            contentDescription = "Поделиться вердиктом",
            tint = MaterialTheme.colorScheme.primary,
            // Сдвиг на 1dp влево — оптическая центровка: у глифа «поделиться»
            // справа два узла, слева один, без сдвига он кажется смещённым вправо.
            modifier = Modifier.size(16.dp).offset(x = (-1).dp)
        )
    }
}

// Сворачиваемая карточка группы сайтов со строкой-сводкой («3/4 доступны»).
@Composable
fun GroupCard(title: String, rows: List<ProbeResult>, expanded: Boolean, onToggle: () -> Unit) {
    val ok = rows.count { it.ok }
    Surface(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .tvFocusHighlight()
                    .clickable { onToggle() }
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "$ok/${rows.size} доступны",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp
                    else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Свернуть группу" else "Развернуть группу",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) {
                Spacer(Modifier.height(4.dp))
                rows.forEach { ProbeRow(it) }
            }
        }
    }
}

// ---------- Экран настроек списков ----------

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var lists by remember { mutableStateOf(ProbeStore.load(context)) }

    // Системная кнопка «назад» возвращает на главный экран, а не закрывает приложение
    BackHandler { onBack() }

    fun apply(a: List<Probe>, b: List<Probe>, c: List<Probe>) {
        ProbeStore.save(context, a, b, c)
        lists = Triple(a, b, c)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onBack,
                // Сдвиг на 12dp влево компенсирует внутренние поля IconButton:
                // видимая стрелка встаёт на одну вертикаль с краем контента,
                // как принято для кнопки «назад».
                modifier = Modifier
                    .offset(x = (-12).dp)
                    .tvFocusHighlight(CircleShape)
            ) {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = "Назад",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                "Списки сайтов",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        Text(
            "Изменения сохраняются сразу и действуют для ручной и фоновой проверки. " +
                "Вводите домен латиницей, например pikabu.ru.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        LazyColumn(Modifier.weight(1f)) {
            item {
                EditableGroup(
                    "Белый список (эталон доступности)", lists.first,
                    confirmQuestion = { d ->
                        "Вы уверены, что сайт $d точно входит в белые списки? " +
                            "Если это не так, при ограничениях приложение может ошибочно " +
                            "решить, что интернет пропал целиком."
                    }
                ) { apply(it, lists.second, lists.third) }
            }
            item {
                EditableGroup(
                    "Обычный интернет (вне списка)", lists.second,
                    confirmQuestion = { d ->
                        "Вы уверены, что сайт $d обычно НЕ открывается при включённом " +
                            "белом списке в вашем регионе? Если он есть в списках, " +
                            "приложение может не заметить ограничения."
                    }
                ) { apply(lists.first, it, lists.third) }
            }
            item {
                EditableGroup(
                    "Заблокированные в РФ (контроль)", lists.third,
                    confirmQuestion = { d ->
                        "Вы уверены, что сайт $d заблокирован в РФ и не открывается " +
                            "в обычном интернете без VPN? Иначе приложение может " +
                            "ошибочно сообщать о включённом VPN."
                    }
                ) { apply(lists.first, lists.second, it) }
            }
            item {
                OutlinedButton(
                    onClick = {
                        ProbeStore.reset(context)
                        lists = ProbeStore.load(context)
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 24.dp)
                        .tvFocusHighlight(RoundedCornerShape(14.dp))
                ) {
                    Text("Сбросить к стандартным спискам")
                }
            }
        }
    }
}

@Composable
fun EditableGroup(
    title: String,
    probes: List<Probe>,
    confirmQuestion: (String) -> String,
    onChange: (List<Probe>) -> Unit
) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Probe?>(null) }

    Surface(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            probes.forEach { p ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        p.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(
                        modifier = Modifier.tvFocusHighlight(CircleShape),
                        onClick = {
                            if (probes.size <= 1) {
                                error = "В группе должен остаться хотя бы один сайт"
                            } else {
                                error = null
                                onChange(probes - p)
                            }
                        }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Удалить ${p.name}",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; error = null },
                    placeholder = {
                        Text("домен, например pikabu.ru", fontSize = 14.sp)
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.tvFocusHighlight(RoundedCornerShape(12.dp)),
                    onClick = {
                        val p = probeFromDomain(input)
                        when {
                            p == null -> error = "Похоже, это не домен. Пример: pikabu.ru"
                            probes.any { it.url == p.url } -> error = "Такой сайт уже есть в группе"
                            else -> {
                                error = null
                                pending = p
                            }
                        }
                    }) { Text("Добавить") }
            }
            error?.let {
                Text(
                    it,
                    color = dangerColor(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }

    // Подтверждение: неверно размещённый сайт ломает логику вердиктов,
    // поэтому перед добавлением переспрашиваем.
    pending?.let { p ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Добавить ${p.name}?") },
            text = { Text(confirmQuestion(p.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onChange(probes + p)
                    pending = null
                    input = ""
                }) { Text("Да, добавить") }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("Отмена") }
            }
        )
    }
}

@Composable
fun VerdictCard(state: ScanState) {
    // Короткие вердикты разбиты на две строки вручную — так карточка
    // читается как заголовок. Длинные переносятся сами.
    val text = when (state.verdict) {
        Verdict.NORMAL -> "Всё в норме:\nограничений не видно"
        Verdict.WHITELIST -> "Похоже, включён\nБЕЛЫЙ СПИСОК"
        Verdict.NO_INTERNET -> "Интернет недоступен вообще"
        Verdict.VPN_OR_ABROAD -> "Открывается всё подряд: похоже, включён VPN или вы вне РФ"
        Verdict.UNKNOWN -> "Непонятная ситуация, попробуйте ещё раз"
        null -> if (state.running) "Проверяю…" else "Нажмите «Проверить»"
    }
    val colors = verdictColors(state.verdict)
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = colors.container
    ) {
        Box(
            Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text,
                color = colors.content,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                lineHeight = 26.sp,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// Круглый значок статуса сайта: галочка (доступен) или крестик (недоступен).
// Кружок с контурной обводкой и мягкой заливкой — как в согласованном макете.
@Composable
fun StatusBadge(ok: Boolean) {
    val c = statusBadgeColors(ok)
    Box(
        Modifier
            .size(22.dp)
            .background(c.container, CircleShape)
            .border(1.5.dp, c.content.copy(alpha = 0.85f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (ok) Icons.Filled.Check else Icons.Filled.Close,
            contentDescription = if (ok) "Доступен" else "Недоступен",
            tint = c.content,
            modifier = Modifier.size(13.dp)
        )
    }
}

@Composable
fun ProbeRow(r: ProbeResult) {
    val uriHandler = LocalUriHandler.current
    val site = "https://" + URL(r.probe.url).host
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusBadge(r.ok)
        Spacer(Modifier.width(8.dp))
        Text(
            r.probe.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .tvFocusHighlight()
                .padding(horizontal = 4.dp, vertical = 2.dp)
                .clickable { uriHandler.openUri(site) }
        )
        // weight(1f) отдаёт тексту всё оставшееся место, textAlign = End
        // прижимает к правому краю обе строки, если текст ошибки перенёсся.
        Text(
            if (r.ok) "${r.ms} мс" else r.note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(start = 8.dp)
        )
    }
}

// Расшифровка ошибок спрятана за значок ⓘ (по образцу чипа сети).
// Пометка про Instagram видна всегда — прятать её нельзя.
@Composable
fun Footnote(expanded: Boolean, onToggle: () -> Unit) {
    Column(Modifier.padding(top = 16.dp, bottom = 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                // Сдвиг на 4dp влево компенсирует внутренний отступ (padding ниже),
                // который нужен для рамки ТВ-фокуса: видимый текст встаёт
                // на одну вертикаль с краем контента и абзацами расшифровки.
                .offset(x = (-4).dp)
                .tvFocusHighlight()
                .clickable { onToggle() }
                .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Text(
                "Почему ошибки разные",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Filled.Info,
                contentDescription = if (expanded) "Скрыть расшифровку ошибок"
                    else "Показать расшифровку ошибок",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
        if (expanded) {
            // Иерархия отступов: абзацы одной темы (расшифровка ошибок)
            // ближе друг к другу (6dp), чем к соседней теме про Instagram (14dp).
            Text(
                "«Адрес не найден (DNS)» — оператор не сообщил адрес сайта, будто " +
                    "его не существует; «нет ответа (таймаут)» — запрос ушёл, но ответ " +
                    "так и не вернулся; «соединение сброшено» — подключение разорвано " +
                    "оборудованием оператора. Это три разных механизма блокировки.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                "«Ошибка шифрования (TLS)» — защищённое соединение не установилось. " +
                    "На старых устройствах это обычно означает устаревшие системные " +
                    "сертификаты, а не блокировку — такой сайт не учитывайте в оценке.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "* Instagram принадлежит компании Meta, признанной экстремистской " +
                "организацией и запрещённой на территории РФ.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// Векторный знак приложения («уши + глобус») без плитки-фона. Рисуется
// в Canvas: круги ушей и глобуса заливаются цветом ФОНА экрана, поэтому
// нижние дуги ушей корректно перекрываются глобусом (как в иконке), а
// видны только линии. Цвет линий берётся из темы (коричневый на светлой,
// светло-бежевый на тёмной) — знак сам подстраивается под тему.
@Composable
fun AppLogoMark(modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.primary
    val fill = MaterialTheme.colorScheme.background
    Canvas(modifier) {
        // Габарит знака в координатах иконки (viewport 220): x[18..202], y[44..180]
        val vbW = 184f; val vbH = 136f
        val scale = minOf(size.width / vbW, size.height / vbH)
        val ox = (size.width - vbW * scale) / 2f
        val oy = (size.height - vbH * scale) / 2f
        fun tx(x: Float) = ox + (x - 18f) * scale
        fun ty(y: Float) = oy + (y - 44f) * scale
        val sw6 = 6f * scale
        val sw4 = 4f * scale
        // уши (заливка фоном + контур)
        for (cx in listOf(52f, 168f)) {
            val c = Offset(tx(cx), ty(78f)); val r = 34f * scale
            drawCircle(fill, r, c)
            drawCircle(ink, r, c, style = Stroke(sw6))
        }
        // глобус поверх ушей
        val gc = Offset(tx(110f), ty(122f)); val gr = 58f * scale
        drawCircle(fill, gr, gc)
        drawCircle(ink, gr, gc, style = Stroke(sw6))
        // меридиан (эллипс), экватор, две широты — только линии
        val mrx = 26f * scale; val mry = 58f * scale
        drawOval(ink, topLeft = Offset(gc.x - mrx, gc.y - mry),
            size = Size(mrx * 2, mry * 2), style = Stroke(sw4))
        drawLine(ink, Offset(tx(52f), ty(122f)), Offset(tx(168f), ty(122f)), strokeWidth = sw4)
        drawPath(Path().apply {
            moveTo(tx(60f), ty(92f)); quadraticBezierTo(tx(110f), ty(72f), tx(160f), ty(92f))
        }, ink, style = Stroke(sw4))
        drawPath(Path().apply {
            moveTo(tx(60f), ty(152f)); quadraticBezierTo(tx(110f), ty(172f), tx(160f), ty(152f))
        }, ink, style = Stroke(sw4))
    }
}

@Composable
fun AppFooter(onOpenHelp: () -> Unit, scanTick: Long) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val version = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) { "?" }
    }
    // Просьба об отзыве — ПО ТРИГГЕРУ и в ОКНЕ: показываем на 3-й, 4-й и 5-й
    // завершённой проверке (человек уже распробовал приложение). Если к 6-й
    // так и не кликнул — прячем сами, чтобы не надоедать. Клик по RuStore/GitHub
    // ставит review_dismissed и закрывает сразу. На ТВ не показываем вовсе —
    // оставить отзыв там неудобно (нет нормального браузера/клавиатуры).
    // scanTick = state.checkedAt: меняется после каждой проверки и заставляет
    // футер перечитать scan_count (иначе Compose мог бы пропустить рекомпозицию
    // и не показать/не скрыть карточку в нужный момент).
    val prefs = remember { context.getSharedPreferences("netstatus", Context.MODE_PRIVATE) }
    val isTv = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }
    var reviewDismissed by remember { mutableStateOf(prefs.getBoolean("review_dismissed", false)) }
    val scanCount = remember(scanTick) { prefs.getInt("scan_count", 0) }
    val showReview = scanCount in 3..5 && !reviewDismissed && !isTv
    Column(
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Карточка «нет рекламы» + просьба об отзыве. Показываем по триггеру
        // (см. showReview выше). Нажатие на любую кнопку прячет карточку навсегда.
        if (showReview) {
            ReviewCard(onEngaged = {
                prefs.edit().putBoolean("review_dismissed", true).apply()
                reviewDismissed = true
            })
            // Отступ до логотипа — только когда карточка показана.
            Spacer(Modifier.height(22.dp))
        }
        // Небольшой логотип приложения — ненавязчивая подпись бренда.
        // Векторный знак без плитки, подстраивается под тему. Тап —
        // открывает сайт приложения.
        AppLogoMark(
            modifier = Modifier
                .size(52.dp)
                .tvFocusHighlight()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClickLabel = "Открыть сайт приложения") {
                    uriHandler.openUri(SITE_URL)
                }
                .padding(6.dp)
        )
        Spacer(Modifier.height(22.dp))
        // Копирайт стоит ВЫШЕ ссылок намеренно: на Android TV прокрутка
        // следует за фокусом пульта и доезжает до последнего фокусируемого
        // элемента. Нефокусируемый копирайт ниже ссылок остался бы за краем.
        Text(
            "© 2026 · Dmitry Starosta",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "Версия $version · проверить обновления",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .tvFocusHighlight()
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .clickable { uriHandler.openUri(REPO_RELEASES) }
        )
        Spacer(Modifier.height(2.dp))
        // Значок ⓘ вместо «?» — единообразно с чипом сети и «Почему ошибки».
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .tvFocusHighlight()
                .clickable { onOpenHelp() }
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                "Как остаться на связи",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// Карточка «в приложении нет рекламы» с двумя одинаковыми по форме
// кнопками-ссылками: RuStore (отзыв и оценка) и GitHub (звезда репозиторию).
// Логотипы в родных цветах: иконка RuStore — как есть (Image, без тонировки),
// марка GitHub — тонируется темой (Icon + tint), чтобы быть видимой и на
// светлой, и на тёмной теме.
@Composable
fun ReviewCard(onEngaged: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "В приложении нет рекламы. Новые функции появляются благодаря " +
                    "вашим отзывам и письмам. Будем рады оценке.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row {
                OutlinedButton(
                    onClick = { onEngaged(); uriHandler.openUri(RUSTORE_URL) },
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f).tvFocusHighlight(RoundedCornerShape(12.dp))
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_rustore),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("RuStore")
                }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(
                    onClick = { onEngaged(); uriHandler.openUri(REPO_URL) },
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f).tvFocusHighlight(RoundedCornerShape(12.dp))
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_github),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("GitHub")
                }
            }
        }
    }
}

// Открывает системный экран настроек ЭТОГО приложения (там на большинстве
// прошивок, включая MIUI/HyperOS, доступны «Автозапуск» и «Батарея»).
// Никаких данных не читаем и не меняем — только навигация; тумблеры жмёт
// сам пользователь. Разрешений не требует.
private fun openAppSettings(ctx: Context) {
    try {
        ctx.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", ctx.packageName, null)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) { }
    }
}

// ---------- Онбординг первого запуска (v0.5.3) ----------

// Два шага: приветствие с ненавязчивым предложением фоновой проверки, затем
// «Как остаться на связи» (те же настройки телефона, что переоткрываются из
// подвала). «Позже» на первом шаге завершает онбординг без второго экрана.
@Composable
fun OnboardingFlow(onFinish: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("netstatus", Context.MODE_PRIVATE) }
    var step by remember { mutableStateOf(1) }

    if (step == 1) {
        // Назад на первом экране = пропустить онбординг.
        BackHandler { onFinish() }
        OnboardingWelcome(
            onEnable = {
                // Включаем фоновую проверку и ведём на второй экран, где
                // пользователь сам разрешит уведомления (кнопка «Разрешить»)
                // и настроит батарею/автозапуск. Разрешение запрашиваем ТОЛЬКО
                // там — иначе статус «Разрешено» на втором экране не совпадал бы
                // с реальностью (запрос шёл из первого экрана мимо него).
                prefs.edit().putBoolean("bg_enabled", true).apply()
                scheduleBackground(context)
                step = 2
            },
            onLater = onFinish
        )
    } else {
        BackHandler { step = 1 }
        ConnectivityHelpScreen(onDone = onFinish, showBack = false)
    }
}

@Composable
fun OnboardingWelcome(onEnable: () -> Unit, onLater: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))
        AppLogoMark(Modifier.size(96.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            "Белый список?",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Покажет, что сейчас с интернетом: всё работает, включён белый " +
                "список или пропал сигнал.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Фоновая проверка и уведомления",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Будем сами проверять состояние сети и предупредим, когда начнётся " +
                        "ограничение — даже если приложение закрыто. Можно включить " +
                        "сейчас или позже.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onEnable,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .tvFocusHighlight(RoundedCornerShape(14.dp))
        ) {
            Text(
                "Включить",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        TextButton(
            onClick = onLater,
            modifier = Modifier.padding(top = 2.dp, bottom = 20.dp).tvFocusHighlight()
        ) {
            Text("Позже")
        }
    }
}

// Экран «Как остаться на связи»: разрешение на уведомления + переход в
// системные настройки для автозапуска/батареи. Используется и как второй
// шаг онбординга (showBack=false, снизу кнопка «Готово»), и как
// переоткрываемая из подвала инструкция (showBack=true, сверху стрелка).
@Composable
fun ConnectivityHelpScreen(onDone: () -> Unit, showBack: Boolean) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("netstatus", Context.MODE_PRIVATE) }
    val isTv = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }
    val initiallyGranted = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(initiallyGranted) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok -> if (ok) granted = true }

    BackHandler { onDone() }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(10.dp))
        // Заголовок закреплён СВЕРХУ (не скроллится) — только когда экран открыт
        // из подвала. Стиль один в один со «Списками сайтов»: стрелка со сдвигом
        // -12dp + название titleLarge Bold. Общий вид у всех экранов с «назад».
        if (showBack) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onDone,
                    modifier = Modifier.offset(x = (-12).dp).tvFocusHighlight(CircleShape)
                ) {
                    Icon(
                        Icons.Filled.ArrowBack,
                        contentDescription = "Назад",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Text(
                    "Как остаться на связи?",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        // Прокручиваемая область: весь длинный контент едет здесь, поэтому текст
        // больше не уезжает под кнопку, а блок отзыва достаётся скроллом. weight(1f)
        // отдаёт ей всё место между закреплённым заголовком и кнопкой «Готово».
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            // На онбординге (без стрелки «назад») крупный заголовок едет вместе
            // с контентом — это приветственный экран, прибивать нечего.
            if (!showBack) {
                Spacer(Modifier.height(20.dp))
                Text(
                    "Чтобы проверка не выключалась",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                "Телефон может сам останавливать приложения, которые работают в " +
                "фоне. Пара настроек — и «Белый список?» продолжит проверять " +
                "состояние сети в фоне.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Surface(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "Уведомления",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Разрешите, чтобы получать предупреждение о начале ограничения.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                if (granted) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = verdictColors(Verdict.NORMAL).content,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Разрешено",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = verdictColors(Verdict.NORMAL).content
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= 33)
                                permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else granted = true
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.tvFocusHighlight(RoundedCornerShape(12.dp))
                    ) { Text("Разрешить") }
                }
            }
        }

        Surface(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "Работа в фоне",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Откройте настройки приложения и разрешите автозапуск и работу " +
                        "без ограничений батареи.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { openAppSettings(context) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.tvFocusHighlight(RoundedCornerShape(12.dp))
                ) { Text("Открыть настройки") }
            }
        }

        Text(
            "На разных телефонах этот экран выглядит по-разному — это нормально. " +
                "Если что-то не открылось, найдите приложение в настройках вручную.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 14.dp)
        )

        // Блок «нет рекламы» + отзыв — постоянно, но ТОЛЬКО когда экран открыт
        // из подвала (showBack), не на онбординге: на первом запуске просить
        // оценку ещё не о чем. На ТВ не показываем (оставить отзыв там неудобно).
        // Увеличенный отступ сверху визуально отделяет блок от инструкций.
        // Клик по кнопке ставит review_dismissed — тогда и карточка на главной
        // больше не появится.
        if (showBack && !isTv) {
            Spacer(Modifier.height(28.dp))
            ReviewCard(onEngaged = {
                prefs.edit().putBoolean("review_dismissed", true).apply()
            })
        }

            // Нижний отступ внутри прокрутки, чтобы последний блок не прилипал
            // к краю (или к кнопке «Готово») при докрутке донизу.
            Spacer(Modifier.height(20.dp))
        }

        // Кнопка «Готово» ЗАКРЕПЛЕНА снизу (только на онбординге): всегда видна
        // и не наезжает на текст — текст теперь скроллится над ней.
        if (!showBack) {
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onDone,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .tvFocusHighlight(RoundedCornerShape(14.dp))
            ) {
                Text(
                    "Готово",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
