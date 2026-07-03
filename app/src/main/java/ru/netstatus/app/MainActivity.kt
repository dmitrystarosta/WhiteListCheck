package ru.netstatus.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// ---------- Модель данных ----------

data class Probe(val name: String, val url: String)

data class ProbeResult(val probe: Probe, val ok: Boolean, val ms: Long, val note: String)

enum class Verdict { NO_INTERNET, WHITELIST, NORMAL, VPN_OR_ABROAD, UNKNOWN }

data class ScanState(
    val running: Boolean = false,
    val networkType: String = "",
    val verdict: Verdict? = null,
    val groupA: List<ProbeResult> = emptyList(), // белый список / всегда доступные
    val groupB: List<ProbeResult> = emptyList(), // обычный рунет вне списка
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
        Probe("Drive2", "https://www.drive2.ru/favicon.ico"),
        Probe("Banki.ru", "https://www.banki.ru/favicon.ico"),
        Probe("МосКостюмер", "https://moskostumer.ru/favicon.ico")
    )
    val defaultC = listOf(
        Probe("Instagram*", "https://www.instagram.com/favicon.ico"),
        Probe("X (Twitter)", "https://x.com/favicon.ico"),
        Probe("Rutracker", "https://rutracker.org/favicon.ico")
    )

    // URL обновляемого конфига. Разместите JSON на хостинге, который сам входит
    // в белый список (например, объектное хранилище VK Cloud / Яндекса),
    // иначе в момент ограничений обновление не скачается.
    const val REMOTE_CONFIG_URL = "" // например: "https://storage.yandexcloud.net/yourbucket/probes.json"

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

// ---------- Сетевые проверки ----------

object Scanner {

    // Лёгкий HTTPS-запрос. Считаем сайт доступным, если получили любой HTTP-ответ:
    // важно именно установление TLS-соединения и ответ сервера, а не код 200.
    // DNS-заглушки и сброс соединения дадут исключение -> "недоступен".
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
            ProbeResult(probe, false, System.currentTimeMillis() - start,
                e.javaClass.simpleName)
        }
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
            else -> Verdict.UNKNOWN // A лежит, B работает — странная ситуация
        }
    }
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(ScanState()) }

    fun runScan() {
        scope.launch {
            state = state.copy(running = true, verdict = null)

            var a = ProbeConfig.defaultA
            var b = ProbeConfig.defaultB
            var c = ProbeConfig.defaultC
            var source = "встроенный список"

            // Пробуем подтянуть свежий конфиг (не критично, если не выйдет)
            if (ProbeConfig.REMOTE_CONFIG_URL.isNotBlank()) {
                withContext(Dispatchers.IO) {
                    try {
                        val json = URL(ProbeConfig.REMOTE_CONFIG_URL).readText()
                        ProbeConfig.parse(json)?.let { (na, nb, nc) ->
                            a = na; b = nb; c = nc
                            source = "обновлён с сервера"
                        }
                    } catch (_: Exception) { /* остаёмся на встроенном */ }
                }
            }

            val net = Scanner.networkType(context)
            val ra = Scanner.scanGroup(a)
            val rb = Scanner.scanGroup(b)
            val rc = Scanner.scanGroup(c)

            state = ScanState(
                running = false,
                networkType = net,
                verdict = Scanner.verdict(ra, rb, rc),
                groupA = ra, groupB = rb, groupC = rc,
                configSource = source
            )
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Я в белых списках?", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        if (state.networkType.isNotEmpty()) {
            val warn = state.networkType != "мобильный интернет"
            Text(
                "Сеть: ${state.networkType}" +
                    if (warn) " — белые списки обычно действуют на мобильном интернете. Но если ваш Wi-Fi раздаёт 3G/4G-роутер, ограничения касаются и его" else "",
                fontSize = 13.sp,
                color = if (warn) Color(0xFF996600) else Color.Gray
            )
        }
        Spacer(Modifier.height(16.dp))

        VerdictCard(state)

        Spacer(Modifier.height(16.dp))
        Button(onClick = { runScan() }, enabled = !state.running) {
            Text(if (state.running) "Сканирую…" else "Проверить")
        }
        Spacer(Modifier.height(8.dp))
        if (state.verdict != null) {
            Text("Конфиг: ${state.configSource}", fontSize = 11.sp, color = Color.Gray)
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(Modifier.fillMaxWidth()) {
            if (state.groupA.isNotEmpty()) {
                item { GroupHeader("Белый список (эталон доступности)") }
                items(state.groupA) { ProbeRow(it) }
            }
            if (state.groupB.isNotEmpty()) {
                item { GroupHeader("Обычный рунет (вне списка)") }
                items(state.groupB) { ProbeRow(it) }
            }
            if (state.groupC.isNotEmpty()) {
                item { GroupHeader("Заблокированные (контроль)") }
                items(state.groupC) { ProbeRow(it) }
            }
        }
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
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text((if (r.ok) "🟢 " else "🔴 ") + r.probe.name, fontSize = 15.sp)
        Text(
            if (r.ok) "${r.ms} мс" else r.note,
            fontSize = 13.sp, color = Color.Gray
        )
    }
}
