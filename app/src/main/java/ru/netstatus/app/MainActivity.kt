package ru.netstatus.app

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
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

// ---------- Модель данных ----------

data class Probe(val name: String, val url: String)
data class ProbeResult(val probe: Probe, val ok: Boolean, val ms: Long, val note: String)

enum class Verdict { NO_INTERNET, WHITELIST, NORMAL, VPN_OR_ABROAD, UNKNOWN }

data class ScanState(
    val running: Boolean = false,
    val networkType: String = "",
    val verdict: Verdict? = null,
    val groupA: List<ProbeResult> = emptyList(), // белый список / всегда доступные
    val groupB: List<ProbeResult> = emptyList(), // обычный интернет вне списка
    val groupC: List<ProbeResult> = emptyList(), // заблокированные (контроль)
    val configSource: String = "встроенный список"
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
        prefs.edit().putString("last_verdict", verdict.name).apply()
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
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
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
}

@Composable
fun App() {
    var showSettings by remember { mutableStateOf(false) }
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
        if (showSettings) {
            SettingsScreen(onBack = { showSettings = false })
        } else {
            MainScreen(
                scanState = scanState,
                scope = appScope,
                onOpenSettings = { showSettings = true }
            )
        }
    }
}

@Composable
fun MainScreen(
    scanState: MutableState<ScanState>,
    scope: CoroutineScope,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    var state by scanState
    val prefs = remember { context.getSharedPreferences("netstatus", Context.MODE_PRIVATE) }
    var bgEnabled by remember { mutableStateOf(prefs.getBoolean("bg_enabled", false)) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* результат не критичен: без разрешения просто не будет уведомлений */ }

    fun runScan() {
        scope.launch {
            state = state.copy(running = true, verdict = null)

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
            val ra = Scanner.scanGroup(a)
            val rb = Scanner.scanGroup(b)
            val rc = Scanner.scanGroup(c)
            val verdict = Scanner.verdict(ra, rb, rc)

            // Запоминаем вердикт, чтобы фоновая проверка сравнивала с актуальным
            prefs.edit().putString("last_verdict", verdict.name).apply()

            state = ScanState(
                running = false,
                networkType = net,
                verdict = verdict,
                groupA = ra, groupB = rb, groupC = rc,
                configSource = source
            )
        }
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

        Spacer(Modifier.height(10.dp))

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
                        Box(Modifier.weight(1f)) { NetworkChip(state.networkType) }
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
                item { GroupCard("Белый список (эталон доступности)", state.groupA) }
            }
            if (state.groupB.isNotEmpty()) {
                item { GroupCard("Обычный интернет (вне списка)", state.groupB) }
            }
            if (state.groupC.isNotEmpty()) {
                item { GroupCard("Заблокированные в РФ (контроль)", state.groupC) }
                item { Footnote() }
            }
            item { AppFooter() }
        }
    }
}

// Чип типа сети. Если для сети есть пояснение — рядом значок ⓘ,
// по тапу пояснение разворачивается и сворачивается.
@Composable
fun NetworkChip(networkType: String) {
    var expanded by rememberSaveable(networkType) { mutableStateOf(false) }

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

    Column {
        Surface(
            shape = RoundedCornerShape(50),
            color = Color.Transparent,
            border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
            modifier = Modifier
                .tvFocusHighlight(RoundedCornerShape(50))
                .clickable(enabled = detail != null) { expanded = !expanded }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    "Сеть: $networkType",
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
        "Проверено $time приложением „Белый список?“: $RUSTORE_URL"
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
fun GroupCard(title: String, rows: List<ProbeResult>) {
    var expanded by rememberSaveable { mutableStateOf(true) }
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
                    .clickable { expanded = !expanded }
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
fun Footnote() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(top = 16.dp, bottom = 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                // Сдвиг на 4dp влево компенсирует внутренний отступ (padding ниже),
                // который нужен для рамки ТВ-фокуса: видимый текст встаёт
                // на одну вертикаль с краем контента и абзацами расшифровки.
                .offset(x = (-4).dp)
                .tvFocusHighlight()
                .clickable { expanded = !expanded }
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

@Composable
fun AppFooter() {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val version = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) { "?" }
    }
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Копирайт стоит ВЫШЕ ссылки на версию намеренно: на Android TV
        // прокрутка следует за фокусом пульта и доезжает до последнего
        // фокусируемого элемента. Если нефокусируемый копирайт стоит ниже
        // ссылки, он остаётся за нижним краем экрана.
        Text(
            "© 2026, Dmitry Starosta",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Версия $version · проверить обновления",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .tvFocusHighlight()
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .clickable { uriHandler.openUri(REPO_RELEASES) }
        )
    }
}
