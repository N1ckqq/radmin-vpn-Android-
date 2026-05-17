package com.radminvpn.android.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AnimationUtils
import android.view.animation.OvershootInterpolator
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.radminvpn.android.R
import com.radminvpn.android.databinding.ActivityServerListBinding
import com.radminvpn.android.model.VpnGateServer
import com.radminvpn.android.vpn.VpnGateRepository
import kotlinx.coroutines.launch

class ServerListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityServerListBinding

    private var allServers = listOf<VpnGateServer>()
    private var filteredServers = listOf<VpnGateServer>()
    private var currentCountryFilter = ""
    private var currentSortMode = SortMode.SCORE
    private var isPinging = false

    enum class SortMode { SCORE, SPEED, PING }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityServerListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupSearch()
        setupCountryChips()
        setupSortSpinner()
        setupRefresh()
        setupPingAll()
        loadServers()
    }

    // ===== TOOLBAR =====

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener {
            animateButtonPress(it)
            finish()
            overridePendingTransition(R.anim.fade_in_up, R.anim.fade_out_down)
        }
    }

    // ===== SEARCH =====

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                binding.btnClearSearch.isVisible = query.isNotEmpty()
                filterServers(query)
            }
        })

        binding.btnClearSearch.setOnClickListener {
            binding.etSearch.text?.clear()
        }
    }

    // ===== COUNTRY CHIPS =====

    private fun setupCountryChips() {
        val chips = mapOf(
            binding.chipAll to "",
            binding.chipJapan to "JP",
            binding.chipKorea to "KR",
            binding.chipUS to "US",
            binding.chipEurope to "EU",
            binding.chipRussia to "RU"
        )

        chips.forEach { (chip, country) ->
            chip.setOnClickListener {
                animateButtonPress(it)
                currentCountryFilter = country
                applyFiltersAndSort()
                updateChipStates(chip.id)
            }
        }
    }

    private fun updateChipStates(activeChipId: Int) {
        val allChips = listOf(
            binding.chipAll, binding.chipJapan, binding.chipKorea,
            binding.chipUS, binding.chipEurope, binding.chipRussia
        )
        allChips.forEach { chip ->
            if (chip.id == activeChipId) {
                chip.setChipBackgroundColorResource(R.color.accent_blue)
                chip.setTextColor(Color.WHITE)
            } else {
                chip.setChipBackgroundColorResource(R.color.card_bg)
                chip.setTextColor(getColor(R.color.text_secondary))
            }
        }
    }

    // ===== SORT =====

    private fun setupSortSpinner() {
        val sortOptions = arrayOf(
            getString(R.string.sort_score),
            getString(R.string.sort_speed),
            getString(R.string.sort_ping)
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sortOptions)
        binding.spinnerSort.adapter = adapter
        binding.spinnerSort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                currentSortMode = when (pos) {
                    0 -> SortMode.SCORE
                    1 -> SortMode.SPEED
                    2 -> SortMode.PING
                    else -> SortMode.SCORE
                }
                applyFiltersAndSort()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ===== REFRESH =====

    private fun setupRefresh() {
        binding.swipeRefresh.setColorSchemeColors(getColor(R.color.accent_blue))
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(getColor(R.color.card_bg))
        binding.swipeRefresh.setOnRefreshListener {
            loadServers()
        }
    }

    // ===== PING ALL =====

    private fun setupPingAll() {
        binding.btnPingAll.setOnClickListener {
            animateButtonPress(it)
            if (!isPinging && allServers.isNotEmpty()) {
                pingAllServers()
            }
        }
    }

    private fun pingAllServers() {
        isPinging = true
        binding.btnPingAll.text = getString(R.string.pinging)
        binding.btnPingAll.isEnabled = false

        lifecycleScope.launch {
            try {
                val results = VpnGateRepository.pingAllServers(filteredServers.take(20))
                runOnUiThread {
                    // Update displayed ping values
                    results.forEach { (ip, latency) ->
                        updateServerPingInList(ip, latency)
                    }
                    isPinging = false
                    binding.btnPingAll.text = getString(R.string.ping_all)
                    binding.btnPingAll.isEnabled = true
                    Toast.makeText(this@ServerListActivity, getString(R.string.ping_complete), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    isPinging = false
                    binding.btnPingAll.text = getString(R.string.ping_all)
                    binding.btnPingAll.isEnabled = true
                }
            }
        }
    }

    private fun updateServerPingInList(ip: String, latency: Int) {
        val count = binding.layoutServerList.childCount
        for (i in 0 until count) {
            val card = binding.layoutServerList.getChildAt(i)
            if (card.tag == ip) {
                val pingText = card.findViewById<TextView>(R.id.tvServerPing)
                if (latency > 0) {
                    pingText?.text = "${latency}ms"
                    pingText?.setTextColor(getPingColor(latency))
                } else {
                    pingText?.text = "Timeout"
                    pingText?.setTextColor(Color.RED)
                }
                // Animate the update
                pingText?.animate()?.scaleX(1.3f)?.scaleY(1.3f)?.setDuration(150)?.withEndAction {
                    pingText.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                }?.start()
            }
        }
    }

    // ===== LOAD SERVERS =====

    private fun loadServers() {
        binding.layoutLoading.isVisible = true
        binding.swipeRefresh.isVisible = false

        lifecycleScope.launch {
            try {
                val result = VpnGateRepository.fetchServers()
                result.onSuccess { servers ->
                    allServers = servers
                    runOnUiThread {
                        binding.layoutLoading.isVisible = false
                        binding.swipeRefresh.isVisible = true
                        binding.swipeRefresh.isRefreshing = false
                        binding.tvServerCount.text = getString(R.string.server_count, servers.size)
                        applyFiltersAndSort()
                    }
                }.onFailure { error ->
                    runOnUiThread {
                        binding.layoutLoading.isVisible = false
                        binding.swipeRefresh.isVisible = true
                        binding.swipeRefresh.isRefreshing = false
                        Toast.makeText(this@ServerListActivity, 
                            getString(R.string.error_loading, error.message), 
                            Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.layoutLoading.isVisible = false
                    binding.swipeRefresh.isVisible = true
                    binding.swipeRefresh.isRefreshing = false
                }
            }
        }
    }

    // ===== FILTER & SORT =====

    private fun filterServers(query: String) {
        applyFiltersAndSort(query)
    }

    private fun applyFiltersAndSort(searchQuery: String = binding.etSearch.text?.toString()?.trim() ?: "") {
        filteredServers = allServers

        // Apply country filter
        if (currentCountryFilter.isNotEmpty()) {
            filteredServers = if (currentCountryFilter == "EU") {
                val euCountries = listOf("DE", "FR", "NL", "GB", "SE", "NO", "DK", "FI", "AT", "CH", "IT", "ES", "PL", "CZ")
                filteredServers.filter { it.countryShort in euCountries }
            } else {
                filteredServers.filter { it.countryShort.equals(currentCountryFilter, ignoreCase = true) }
            }
        }

        // Apply search filter
        if (searchQuery.isNotEmpty()) {
            filteredServers = filteredServers.filter {
                it.hostName.contains(searchQuery, ignoreCase = true) ||
                it.countryLong.contains(searchQuery, ignoreCase = true) ||
                it.ip.contains(searchQuery)
            }
        }

        // Apply sort
        filteredServers = when (currentSortMode) {
            SortMode.SCORE -> filteredServers.sortedByDescending { it.score }
            SortMode.SPEED -> filteredServers.sortedByDescending { it.speed }
            SortMode.PING -> filteredServers.sortedBy { if (it.ping > 0) it.ping else Int.MAX_VALUE }
        }

        displayServers()
    }

    // ===== DISPLAY SERVERS =====

    private fun displayServers() {
        binding.layoutServerList.removeAllViews()

        filteredServers.forEachIndexed { index, server ->
            val card = createServerCard(server)
            binding.layoutServerList.addView(card)

            // Staggered animation
            card.alpha = 0f
            card.translationY = 40f
            card.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(300)
                .setStartDelay((index * 50).toLong())
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }
    }

    private fun createServerCard(server: VpnGateServer): MaterialCardView {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 8.dp()
            }
            radius = 14.dpf()
            cardElevation = 0f
            setCardBackgroundColor(getColor(R.color.card_bg))
            strokeColor = getColor(R.color.card_stroke)
            strokeWidth = 1.dp()
            tag = server.ip
            isClickable = true
            isFocusable = true
            foreground = getDrawable(R.drawable.bg_nav_item)
        }

        val content = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp(), 14.dp(), 14.dp(), 14.dp())
        }

        // Country flag
        val flagFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(40.dp(), 40.dp())
            setBackgroundResource(R.drawable.bg_card_dark)
        }
        val flagText = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            text = getCountryFlag(server.countryShort)
            textSize = 18f
        }
        flagFrame.addView(flagText)
        content.addView(flagFrame)

        // Server info
        val infoLayout = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 12.dp()
            }
            orientation = LinearLayout.VERTICAL
        }

        val nameText = TextView(this).apply {
            text = server.hostName
            textSize = 13f
            setTextColor(getColor(R.color.text_primary))
            maxLines = 1
        }
        infoLayout.addView(nameText)

        val countryText = TextView(this).apply {
            text = "${server.countryLong} • ${server.numVpnSessions} users"
            textSize = 11f
            setTextColor(getColor(R.color.text_secondary))
        }
        infoLayout.addView(countryText)

        // Speed bar
        val speedBarLayout = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 6.dp() }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val speedBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(0, 6.dp(), 1f)
            max = 100
            progress = minOf(100, (server.speed / 1_000_000).toInt())
            progressDrawable = getDrawable(R.drawable.bg_speed_bar)
        }
        speedBarLayout.addView(speedBar)

        val speedText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 8.dp() }
            text = formatSpeedMbps(server.speed)
            textSize = 10f
            setTextColor(getColor(R.color.text_secondary))
        }
        speedBarLayout.addView(speedText)
        infoLayout.addView(speedBarLayout)

        content.addView(infoLayout)

        // Ping & connect
        val rightLayout = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.END
        }

        val pingTextView = TextView(this).apply {
            id = R.id.tvServerPing
            text = if (server.ping > 0) "${server.ping}ms" else "—"
            textSize = 12f
            setTextColor(getPingColor(server.ping))
            gravity = Gravity.END
        }
        rightLayout.addView(pingTextView)

        val scoreText = TextView(this).apply {
            text = "★ ${server.score / 1000}k"
            textSize = 10f
            setTextColor(Color.parseColor("#FF9800"))
            gravity = Gravity.END
        }
        rightLayout.addView(scoreText)

        content.addView(rightLayout)
        card.addView(content)

        // Click handler
        card.setOnClickListener {
            animateButtonPress(it)
            connectToServer(server)
        }

        return card
    }

    // ===== CONNECT =====

    private fun connectToServer(server: VpnGateServer) {
        val intent = Intent(this, ConnectedActivity::class.java).apply {
            putExtra("server_name", server.hostName)
            putExtra("server_ip", server.ip)
            putExtra("server_country", server.countryLong)
            putExtra("server_country_short", server.countryShort)
            putExtra("server_speed", server.speed)
            putExtra("server_ping", server.ping)
            putExtra("server_sessions", server.numVpnSessions)
            putExtra("server_uptime", server.uptime)
            putExtra("server_score", server.score)
            putExtra("connection_start", System.currentTimeMillis())
        }
        startActivity(intent)
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }

    // ===== UTILS =====

    private fun animateButtonPress(view: View) {
        val scaleDown = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, "scaleX", 1f, 0.95f),
                ObjectAnimator.ofFloat(view, "scaleY", 1f, 0.95f)
            )
            duration = 80
        }
        val scaleUp = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, "scaleX", 0.95f, 1f),
                ObjectAnimator.ofFloat(view, "scaleY", 0.95f, 1f)
            )
            duration = 80
            interpolator = OvershootInterpolator()
        }
        AnimatorSet().apply {
            playSequentially(scaleDown, scaleUp)
            start()
        }
    }

    private fun getPingColor(ping: Int): Int {
        return when {
            ping <= 0 -> Color.GRAY
            ping < 50 -> Color.parseColor("#00E676")
            ping < 100 -> Color.parseColor("#FFEB3B")
            ping < 200 -> Color.parseColor("#FF9800")
            else -> Color.parseColor("#FF5252")
        }
    }

    private fun getCountryFlag(countryCode: String): String {
        return try {
            val firstLetter = Character.codePointAt(countryCode.uppercase(), 0) - 0x41 + 0x1F1E6
            val secondLetter = Character.codePointAt(countryCode.uppercase(), 1) - 0x41 + 0x1F1E6
            String(Character.toChars(firstLetter)) + String(Character.toChars(secondLetter))
        } catch (e: Exception) {
            "🌐"
        }
    }

    private fun formatSpeedMbps(bitsPerSecond: Long): String {
        val mbps = bitsPerSecond / 1_000_000.0
        return String.format("%.0f Mbps", mbps)
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
    private fun Int.dpf(): Float = this * resources.displayMetrics.density

    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.fade_in_up, R.anim.fade_out_down)
    }
}
