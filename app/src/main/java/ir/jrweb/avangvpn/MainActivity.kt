package ir.jrweb.avangvpn

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.zxing.integration.android.IntentIntegrator
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var btnAddMenu: ImageButton; lateinit var btnConnect: Button; lateinit var tvStatus: TextView
    private lateinit var tvSubTitle: TextView; lateinit var tvSubDays: TextView; lateinit var tvSubTraffic: TextView
    private lateinit var tvSubPercent: TextView; lateinit var timelineProgress: ProgressBar; lateinit var rvConfigs: RecyclerView

    private val configList = mutableListOf<VpnConfig>(); lateinit var adapter: ConfigAdapter
    private var currentSub = SubscriptionInfo(); private var isConnected = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            configList.firstOrNull { it.isSelected }?.let { launchVpnService(it) }
        } else {
            tvStatus.text = "Ø¯Ø³ØªØ±Ø³ÛŒ VPN ØªÙˆØ³Ø· Ú©Ø§Ø±Ø¨Ø± Ù„ØºÙˆ Ø´Ø¯"
        }
    }

    private val qrLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        IntentIntegrator.parseActivityResult(result.resultCode, result.data)?.contents?.trim()?.takeIf { it.isNotEmpty() }?.let { handleImportedContent(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initViews(); setupRecyclerView(); loadSavedData(); setupListeners()
    }

    private fun initViews() {
        btnAddMenu = findViewById(R.id.btnAddMenu); btnConnect = findViewById(R.id.btnConnect); tvStatus = findViewById(R.id.tvStatus)
        tvSubTitle = findViewById(R.id.tvSubTitle); tvSubDays = findViewById(R.id.tvSubDays); tvSubTraffic = findViewById(R.id.tvSubTraffic)
        tvSubPercent = findViewById(R.id.tvSubPercent); timelineProgress = findViewById(R.id.timelineProgress); rvConfigs = findViewById(R.id.rvConfigs)
    }

    private fun setupRecyclerView() {
        adapter = ConfigAdapter(configList) { selectedConfig ->
            configList.forEach { it.isSelected = (it.id == selectedConfig.id) }; adapter.notifyDataSetChanged(); prepareAndStartVpn(selectedConfig)
        }
        rvConfigs.layoutManager = LinearLayoutManager(this); rvConfigs.adapter = adapter
    }

    private fun setupListeners() {
        btnAddMenu.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.add(0, 1, 0, "ðŸ“‹ Ú©Ù¾ÛŒ Ø§Ø² Ú©Ù„ÛŒÙ¾â€ŒØ¨ÙˆØ±Ø¯")
            popup.menu.add(0, 2, 1, "ðŸ“· Ø§Ø³Ú©Ù† Ú©ÛŒÙˆâ€ŒØ¢Ø± Ú©Ø¯ (QR)")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) { 1 -> importFromClipboard(); 2 -> startQrScanner() }
                true
            }
            popup.show()
        }
        btnConnect.setOnClickListener { if (isConnected) disconnectVpn() else smartConnectFlow() }
    }

    private fun importFromClipboard() {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()?.trim()?.run {
            if (isNotEmpty()) handleImportedContent(this) else Toast.makeText(this@MainActivity, "Ú©Ù„ÛŒÙ¾â€ŒØ¨ÙˆØ±Ø¯ Ø®Ø§Ù„ÛŒ Ø§Ø³Øª!", Toast.LENGTH_SHORT).show()
        } ?: Toast.makeText(this, "Ú©Ù„ÛŒÙ¾â€ŒØ¨ÙˆØ±Ø¯ Ø®Ø§Ù„ÛŒ Ø§Ø³Øª!", Toast.LENGTH_SHORT).show()
    }

    private fun startQrScanner() {
        val integrator = IntentIntegrator(this).apply {
            setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            setPrompt("Ú©Ø¯ QR Ø§Ø´ØªØ±Ø§Ú© ÛŒØ§ Ú©Ø§Ù†ÙÛŒÚ¯ Ø±Ø§ Ø¯Ø§Ø®Ù„ Ù…Ø±Ø¨Ø¹ Ù‚Ø±Ø§Ø± Ø¯Ù‡ÛŒØ¯")
            setBeepEnabled(true)
            setOrientationLocked(true)
        }
        qrLauncher.launch(integrator.createScanIntent())
    }

    private fun handleImportedContent(rawText: String) {
        if (rawText.startsWith("http://") || rawText.startsWith("https://")) {
            currentSub.url = rawText; saveSubUrl(rawText)
            scope.launch { tvStatus.text = "Ø¯Ø± Ø­Ø§Ù„ Ø¯Ø±ÛŒØ§ÙØª Ø§Ø·Ù„Ø§Ø¹Ø§Øª Ú¯Ø±ÙˆÙ‡ Ø§Ø´ØªØ±Ø§Ú©..."; updateSubscription(rawText); pingAllConfigs() }
        } else {
            val parsed = parseMultiLineConfigs(rawText)
            if (parsed.isNotEmpty()) {
                configList.clear(); configList.addAll(parsed); saveConfigsLocal(); adapter.notifyDataSetChanged()
                scope.launch { pingAllConfigs() }
            } else Toast.makeText(this, "ÙØ±Ù…Øª Ù„ÛŒÙ†Ú© Ù…Ø¹ØªØ¨Ø± Ù†ÛŒØ³Øª", Toast.LENGTH_SHORT).show()
        }
    }

    private fun smartConnectFlow() {
        scope.launch {
            btnConnect.isEnabled = false
            if (currentSub.url.isNotEmpty()) { tvStatus.text = "Û±/Û³ Ø¯Ø± Ø­Ø§Ù„ Ø¨Ù‡â€ŒØ±ÙˆØ²Ø±Ø³Ø§Ù†ÛŒ Ù„ÛŒÙ†Ú©â€ŒÙ‡Ø§ÛŒ Ø§Ø´ØªØ±Ø§Ú©..."; updateSubscription(currentSub.url) }
            if (configList.isEmpty()) { tvStatus.text = "Ù‡ÛŒÚ† Ú©Ø§Ù†ÙÛŒÚ¯ÛŒ ÛŒØ§ÙØª Ù†Ø´Ø¯. Ø§Ø² Ù…Ù†ÙˆÛŒ + Ø§Ø¶Ø§ÙÙ‡ Ú©Ù†ÛŒØ¯."; btnConnect.isEnabled = true; return@launch }
            tvStatus.text = "Û²/Û³ Ø¯Ø± Ø­Ø§Ù„ Ú¯Ø±ÙØªÙ† Ù¾ÛŒÙ†Ú¯ Ø³Ø±ÙˆØ±Ù‡Ø§..."; pingAllConfigs()
            val bestConfig = configList.filter { it.pingMs in 1..4999 }.minByOrNull { it.pingMs }
            if (bestConfig != null) {
                configList.forEach { it.isSelected = (it.id == bestConfig.id) }; adapter.notifyDataSetChanged()
                tvStatus.text = "Û³/Û³ Ø¯Ø± Ø­Ø§Ù„ Ø§ØªØµØ§Ù„ Ø¨Ù‡ Ø³Ø±ÛŒØ¹â€ŒØªØ±ÛŒÙ† Ø³Ø±ÙˆØ± (${bestConfig.pingMs}ms)..."; prepareAndStartVpn(bestConfig)
            } else tvStatus.text = "Ù¾Ø§Ø³Ø®ÛŒ Ø§Ø² Ø³Ø±ÙˆØ±Ù‡Ø§ Ø¯Ø±ÛŒØ§ÙØª Ù†Ø´Ø¯!"
            btnConnect.isEnabled = true
        }
    }

    private suspend fun updateSubscription(subUrl: String) = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(subUrl).header("User-Agent", "AvangVPN/1.0").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use
                parseSubscriptionUserInfo(response.header("Subscription-Userinfo") ?: "")
                val decoded = response.body?.string()?.trim()?.let { body ->
                    try { String(Base64.decode(body, Base64.DEFAULT)) } catch (_: Exception) { body }
                } ?: ""
                val parsedConfigs = parseMultiLineConfigs(decoded)
                withContext(Dispatchers.Main) {
                    if (parsedConfigs.isNotEmpty()) { configList.clear(); configList.addAll(parsedConfigs); saveConfigsLocal(); adapter.notifyDataSetChanged() }
                    updateTimelineUI()
                }
            }
        } catch (e: Exception) { withContext(Dispatchers.Main) { tvStatus.text = "Ø®Ø·Ø§ Ø¯Ø± Ø¢Ù¾Ø¯ÛŒØª Ø§Ø´ØªØ±Ø§Ú©: ${e.localizedMessage}" } }
    }

    private fun parseSubscriptionUserInfo(header: String) {
        var upload = 0L; var download = 0L; var total = 0L; var expire = 0L
        header.split(";").forEach { p ->
            val kv = p.trim().split("=")
            if (kv.size == 2) {
                when (kv[0].lowercase()) {
                    "upload" -> upload = kv[1].toLongOrNull() ?: 0L
                    "download" -> download = kv[1].toLongOrNull() ?: 0L
                    "total" -> total = kv[1].toLongOrNull() ?: 0L
                    "expire" -> expire = kv[1].toLongOrNull() ?: 0L
                }
            }
        }
        currentSub.usedBytes = upload + download; currentSub.totalBytes = total; currentSub.expireTimestamp = expire
    }

    private fun updateTimelineUI() {
        val totalGb = currentSub.totalBytes / (1024.0 * 1024.0 * 1024.0); val usedGb = currentSub.usedBytes / (1024.0 * 1024.0 * 1024.0)
        val remainGb = (totalGb - usedGb).coerceAtLeast(0.0)
        val percent = if (totalGb > 0) ((remainGb / totalGb) * 100).toInt().coerceIn(0, 100) else 100
        timelineProgress.progress = percent
        tvSubTraffic.text = if (totalGb > 0) String.format("Ø­Ø¬Ù… Ø¨Ø§Ù‚ÛŒâ€ŒÙ…Ø§Ù†Ø¯Ù‡: %.1f Ø§Ø² %.1f Ú¯ÛŒÚ¯", remainGb, totalGb) else "Ø­Ø¬Ù… Ø¨Ø§Ù‚ÛŒâ€ŒÙ…Ø§Ù†Ø¯Ù‡: Ù†Ø§Ù…Ø­Ø¯ÙˆØ¯"
        if (currentSub.expireTimestamp > 0) {
            val remainDays = ((currentSub.expireTimestamp - (System.currentTimeMillis() / 1000L)) / 86400L).coerceAtLeast(0L)
            tvSubDays.text = "Ù…Ø¯Øª Ø¨Ø§Ù‚ÛŒâ€ŒÙ…Ø§Ù†Ø¯Ù‡: $remainDays Ø±ÙˆØ²"
        } else tvSubDays.text = "Ù…Ø¯Øª Ø¨Ø§Ù‚ÛŒâ€ŒÙ…Ø§Ù†Ø¯Ù‡: Ù†Ø§Ù…Ø­Ø¯ÙˆØ¯"
        tvSubPercent.text = "$percent% Ø¨Ø§Ù‚ÛŒâ€ŒÙ…Ø§Ù†Ø¯Ù‡"
    }

    private fun parseMultiLineConfigs(raw: String): List<VpnConfig> {
        val list = mutableListOf<VpnConfig>()
        if (raw.contains("[Interface]") && raw.contains("[Peer]")) {
            val endpointLine = raw.lines().firstOrNull { it.trim().startsWith("Endpoint", true) }
            val ep = endpointLine?.substringAfter("=")?.trim() ?: "1.1.1.1:51820"
            list.add(VpnConfig(UUID.randomUUID().toString(), "WireGuard Tunnel", "WIREGUARD", ep.substringBefore(":"), ep.substringAfter(":", "51820").toIntOrNull() ?: 51820, raw))
            return list
        }
        raw.lines().forEach { line -> line.trim().takeIf { it.isNotEmpty() }?.let { parseSingleUri(it)?.let { cfg -> list.add(cfg) } } }
        return list
    }

    private fun parseSingleUri(uriStr: String): VpnConfig? {
        return try {
            when {
                uriStr.startsWith("vless://") || uriStr.startsWith("trojan://") -> {
                    val proto = uriStr.substringBefore("://").uppercase(); val uri = URI(uriStr)
                    val name = uri.fragment?.let { URLDecoder.decode(it, "UTF-8") } ?: "$proto Server"
                    VpnConfig(UUID.randomUUID().toString(), name, proto, uri.host ?: "", if (uri.port > 0) uri.port else 443, uriStr)
                }
                uriStr.startsWith("vmess://") -> {
                    val json = JSONObject(String(Base64.decode(uriStr.removePrefix("vmess://"), Base64.DEFAULT)))
                    VpnConfig(UUID.randomUUID().toString(), json.optString("ps", "VMess Server"), "VMESS", json.optString("add"), json.optInt("port", 443), uriStr)
                }
                uriStr.startsWith("wireguard://") || uriStr.startsWith("wg://") -> {
                    val uri = URI(uriStr.replace("wg://", "wireguard://"))
                    val name = uri.fragment?.let { URLDecoder.decode(it, "UTF-8") } ?: "WireGuard Server"
                    VpnConfig(UUID.randomUUID().toString(), name, "WIREGUARD", uri.host ?: "", if (uri.port > 0) uri.port else 51820, uriStr)
                }
                uriStr.startsWith("ss://") -> {
                    val uri = URI(uriStr)
                    val name = uri.fragment?.let { URLDecoder.decode(it, "UTF-8") } ?: "Shadowsocks"
                    VpnConfig(UUID.randomUUID().toString(), name, "SS", uri.host ?: "", if (uri.port > 0) uri.port else 8388, uriStr)
                }
                else -> null
            }
        } catch (_: Exception) { null }
    }

    private suspend fun pingAllConfigs() = withContext(Dispatchers.IO) {
        val jobs = configList.map { cfg -> async { cfg.pingMs = tcpPing(cfg.host, cfg.port) } }
        jobs.awaitAll()
        configList.sortBy { if (it.pingMs > 0) it.pingMs else Int.MAX_VALUE }
        withContext(Dispatchers.Main) { adapter.notifyDataSetChanged() }
    }

    private fun tcpPing(host: String, port: Int): Int {
        if (host.isBlank() || port <= 0) return -1
        return try {
            val start = System.currentTimeMillis()
            Socket().use { it.connect(InetSocketAddress(host, port), 2500) }
            (System.currentTimeMillis() - start).toInt()
        } catch (_: Exception) { -1 }
    }

    private fun prepareAndStartVpn(config: VpnConfig) {
        VpnService.prepare(this)?.let { vpnPermissionLauncher.launch(it) } ?: launchVpnService(config)
    }

    private fun launchVpnService(config: VpnConfig) {
        try {
            val jsonConfig = ConfigConverter.buildXrayConfig(config)
            ContextCompat.startForegroundService(this, Intent(this, AvangVpnService::class.java).apply {
                putExtra("CONFIG_NAME", config.name); putExtra("JSON_CONFIG", jsonConfig)
            })
            isConnected = true; btnConnect.text = "Ù‚Ø·Ø¹ Ø§ØªØµØ§Ù„ (${config.name})"; tvStatus.text = "Ù…ØªØµÙ„ Ø¨Ù‡ ${config.name} | Ù¾ÛŒÙ†Ú¯: ${config.pingMs} ms"
        } catch (e: Exception) { tvStatus.text = "Ø®Ø·Ø§ Ø¯Ø± Ø§Ø¬Ø±Ø§ÛŒ Ù‡Ø³ØªÙ‡: ${e.localizedMessage}" }
    }

    private fun disconnectVpn() {
        startService(Intent(this, AvangVpnService::class.java).apply { action = "STOP_VPN" })
        isConnected = false; btnConnect.text = "Ø§ØªØµØ§Ù„ Ù‡ÙˆØ´Ù…Ù†Ø¯ (Ú©Ù…ØªØ±ÛŒÙ† Ù¾ÛŒÙ†Ú¯)"; tvStatus.text = "Ø§ØªØµØ§Ù„ Ù‚Ø·Ø¹ Ø´Ø¯"
    }

    private fun saveSubUrl(url: String) { getSharedPreferences("avang_prefs", Context.MODE_PRIVATE).edit().putString("sub_url", url).apply() }
    private fun saveConfigsLocal() { getSharedPreferences("avang_prefs", Context.MODE_PRIVATE).edit().putString("saved_configs", configList.joinToString("\n") { it.rawUri }).apply() }
    private fun loadSavedData() {
        val prefs = getSharedPreferences("avang_prefs", Context.MODE_PRIVATE)
        currentSub.url = prefs.getString("sub_url", "") ?: ""
        prefs.getString("saved_configs", "")?.takeIf { it.isNotEmpty() }?.let { configList.addAll(parseMultiLineConfigs(it)); adapter.notifyDataSetChanged() }
    }

    inner class ConfigAdapter(private val items: List<VpnConfig>, private val onClick: (VpnConfig) -> Unit) : RecyclerView.Adapter<ConfigAdapter.Holder>() {
        inner class Holder(val root: LinearLayout, val tvName: TextView, val tvProto: TextView, val tvPing: TextView) : RecyclerView.ViewHolder(root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(36, 28, 36, 28)
                background = ContextCompat.getDrawable(context, R.drawable.bg_card_pastel)
                layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 18 }
            }
            val tvPing = TextView(parent.context).apply { textSize = 13f; setTextColor(ContextCompat.getColor(context, R.color.ping_green)); setTypeface(null, android.graphics.Typeface.BOLD) }
            val infoBox = LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
            val tvName = TextView(parent.context).apply { textSize = 14f; setTextColor(ContextCompat.getColor(context, R.color.pastel_white)); setTypeface(null, android.graphics.Typeface.BOLD) }
            val tvProto = TextView(parent.context).apply { textSize = 11f; setTextColor(ContextCompat.getColor(context, R.color.accent_pastel_orange)) }
            infoBox.addView(tvName); infoBox.addView(tvProto); row.addView(tvPing); row.addView(infoBox)
            return Holder(row, tvName, tvProto, tvPing)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.tvName.text = item.name; holder.tvProto.text = "${item.protocol} â€¢ ${item.host}"
            holder.tvPing.text = when { item.pingMs > 0 -> "${item.pingMs} ms"; item.pingMs == -1 -> "ØªØ³Øª Ù†Ø´Ø¯Ù‡"; else -> "ØªØ§ÛŒÙ…â€ŒØ§ÙˆØª" }
            holder.tvPing.setTextColor(if (item.pingMs > 0) ContextCompat.getColor(this@MainActivity, R.color.ping_green) else Color.parseColor("#F87171"))
            holder.root.alpha = if (item.isSelected) 1.0f else 0.88f
            holder.root.setOnClickListener { onClick(item) }
        }
        override fun getItemCount(): Int = items.size
    }
}