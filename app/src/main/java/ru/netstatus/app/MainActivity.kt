package ru.netstatus.app

import android.Manifest
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "мобильный интернет"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet (кабель)"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
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

// ---------- Подсветка фокуса для пульта (Android TV) ----------

// Выбранный пультом элемент получает заметную рамку и лёгкий фон.
// На телефоне не проявляется: там фокус не «ходит» стрелками.
@Composable
fun Modifier.tvFocusHighlight(shape: Shape = RoundedCornerShape(8.dp)): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
        .then(
            if (focused)
                Modifier
                    .border(2.dp, Color(0xFF3F51B5), shape)
                    .background(Color(0x1A3F51B5), shape)
            else Modifier
        )
}

// ---------- UI ----------

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
    }
}

@Composable
fun App() {
    var showSettings by remember { mutableStateOf(false) }
    if (showSettings) {
        SettingsScreen(onBack = { showSettings = false })
    } else {
        MainScreen(onOpenSettings = { showSettings = true })
    }
}

@Composable
fun MainScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(ScanState()) }
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

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth()) {
            Text(
                "Я в белых списках?",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center)
            )
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .tvFocusHighlight(CircleShape)
            ) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = "Настройки списков",
                    tint = Color.Gray
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        if (state.networkType.isNotEmpty()) {
            val (netMsg, netColor) = when (state.networkType) {
                "нет сети" -> "Сеть: нет сети. Проверьте наличие интернета на вашем устройстве" to
                    Color(0xFFC62828)
                "Wi-Fi" -> ("Сеть: Wi-Fi — белые списки обычно действуют на мобильном интернете. " +
                    "Но если ваш Wi-Fi раздаёт 3G/4G-роутер, ограничения касаются и его") to
                    Color(0xFF996600)
                "Ethernet (кабель)" -> ("Сеть: Ethernet (кабель) — это домашний интернет, белые " +
                    "списки обычно действуют на мобильном. Результат показывает состояние " +
                    "именно кабельного подключения") to
                    Color(0xFF996600)
                else -> "Сеть: ${state.networkType}" to Color.Gray
            }
            Text(netMsg, fontSize = 13.sp, color = netColor)
        }
        Spacer(Modifier.height(16.dp))

        VerdictCard(state)

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { runScan() },
            enabled = !state.running,
            modifier = Modifier.tvFocusHighlight(CircleShape)
        ) {
            Text(if (state.running) "Сканирую…" else "Проверить")
        }
        Spacer(Modifier.height(8.dp))
        if (state.verdict != null) {
            Text("Конфиг: ${state.configSource}", fontSize = 11.sp, color = Color.Gray)
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp)
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
            Text("Фоновая проверка и уведомления", fontSize = 14.sp)
        }
        Spacer(Modifier.height(4.dp))

        LazyColumn(Modifier.fillMaxWidth()) {
            if (state.groupA.isNotEmpty()) {
                item { GroupHeader("Белый список (эталон доступности)") }
                items(state.groupA) { ProbeRow(it) }
            }
            if (state.groupB.isNotEmpty()) {
                item { GroupHeader("Обычный интернет (вне списка)") }
                items(state.groupB) { ProbeRow(it) }
            }
            if (state.groupC.isNotEmpty()) {
                item { GroupHeader("Заблокированные в РФ (контроль)") }
                items(state.groupC) { ProbeRow(it) }
                item { Footnote() }
            }
            item { AppFooter() }
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

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.tvFocusHighlight(CircleShape)
            ) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
            }
            Text("Списки сайтов", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "Изменения сохраняются сразу и действуют для ручной и фоновой проверки. " +
            "Вводите домен латиницей, например pikabu.ru.",
            fontSize = 12.sp, color = Color.Gray,
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 24.dp)
                        .tvFocusHighlight(CircleShape)
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

    Column {
        GroupHeader(title)
        probes.forEach { p ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(p.name, fontSize = 15.sp)
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
                        tint = Color.Gray
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it; error = null },
                placeholder = { Text("домен, например pikabu.ru", fontSize = 14.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(
                modifier = Modifier.tvFocusHighlight(CircleShape),
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
            Text(it, color = Color(0xFFC62828), fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp))
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
    val (text, color) = when (state.verdict) {
        Verdict.NORMAL -> "Всё в норме: ограничений не видно" to Color(0xFF2E7D32)
        Verdict.WHITELIST -> "Похоже, включён БЕЛЫЙ СПИСОК" to Color(0xFFC62828)
        Verdict.NO_INTERNET -> "Интернет недоступен вообще" to Color(0xFF616161)
        Verdict.VPN_OR_ABROAD -> "Открывается всё подряд: похоже, включён VPN или вы вне РФ" to Color(0xFF1565C0)
        Verdict.UNKNOWN -> "Непонятная ситуация, попробуйте ещё раз" to Color(0xFF996600)
        null -> if (state.running) "Проверяю…" to Color.Gray
                else "Нажмите «Проверить»" to Color.Gray
    }
    Box(
        Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = color, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun GroupHeader(title: String) {
    Text(
        title,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = Color.Gray,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
    )
}

@Composable
fun ProbeRow(r: ProbeResult) {
    val uriHandler = LocalUriHandler.current
    val site = "https://" + URL(r.probe.url).host
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            (if (r.ok) "🟢 " else "🔴 ") + r.probe.name,
            fontSize = 15.sp,
            color = Color(0xFF3F51B5),
            modifier = Modifier
                .tvFocusHighlight()
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .clickable { uriHandler.openUri(site) }
        )
        Text(
            if (r.ok) "${r.ms} мс" else r.note,
            fontSize = 13.sp, color = Color.Gray
        )
    }
}

@Composable
fun Footnote() {
    Text(
        "Почему ошибки разные: «адрес не найден (DNS)» — оператор не сообщил " +
        "адрес сайта, будто его не существует; «нет ответа (таймаут)» — запрос " +
        "ушёл, но ответ так и не вернулся; «соединение сброшено» — подключение " +
        "разорвано оборудованием оператора. Это три разных механизма блокировки.\n\n" +
        "* Instagram принадлежит компании Meta, признанной экстремистской " +
        "организацией и запрещённой на территории РФ.",
        fontSize = 12.sp,
        color = Color.Gray,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
    )
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
        Text(
            "Версия $version · проверить обновления",
            fontSize = 12.sp,
            color = Color(0xFF3F51B5),
            modifier = Modifier
                .tvFocusHighlight()
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .clickable { uriHandler.openUri(REPO_RELEASES) }
        )
        Spacer(Modifier.height(2.dp))
        Text("© 2026, Dmitry Starosta", fontSize = 12.sp, color = Color.Gray)
    }
}
