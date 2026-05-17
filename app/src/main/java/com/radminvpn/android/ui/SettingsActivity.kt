package com.radminvpn.android.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.button.MaterialButton
import com.radminvpn.android.R
import com.radminvpn.android.databinding.ActivitySettingsBinding
import com.radminvpn.android.vpn.VpnGateRepository

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: SharedPreferences

    // Settings values
    private var currentTheme = 0  // 0=Dark, 1=Light, 2=AMOLED
    private var currentAccentColor = 0  // 0=Blue, 1=Green, 2=Purple, 3=Red
    private var currentFontSize = 14
    private var dnsPrimary = "8.8.8.8"
    private var dnsSecondary = "8.8.4.4"
    private var mtuSize = 1400
    private var currentProtocol = 0  // 0=OpenVPN, 1=WireGuard, 2=Auto
    private var autoConnect = false
    private var preferredCountry = ""
    private val mirrorUrls = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)

        loadSettings()
        setupToolbar()
        setupThemePicker()
        setupAccentColors()
        setupFontSize()
        setupDns()
        setupMtu()
        setupProtocol()
        setupMirrors()
        setupAutoConnect()
        setupCountryPicker()
        setupAboutSection()
        setupSaveButton()
        animateEntrance()
    }

    // ===== LOAD SETTINGS =====

    private fun loadSettings() {
        currentTheme = prefs.getInt("theme", 0)
        currentAccentColor = prefs.getInt("accent_color", 0)
        currentFontSize = prefs.getInt("font_size", 14)
        dnsPrimary = prefs.getString("dns_primary", "8.8.8.8") ?: "8.8.8.8"
        dnsSecondary = prefs.getString("dns_secondary", "8.8.4.4") ?: "8.8.4.4"
        mtuSize = prefs.getInt("mtu_size", 1400)
        currentProtocol = prefs.getInt("protocol", 0)
        autoConnect = prefs.getBoolean("auto_connect", false)
        preferredCountry = prefs.getString("preferred_country", "") ?: ""

        val savedMirrors = prefs.getStringSet("custom_mirrors", emptySet()) ?: emptySet()
        mirrorUrls.clear()
        mirrorUrls.addAll(savedMirrors)
    }

    // ===== TOOLBAR =====

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener {
            animateButtonPress(it)
            finish()
            overridePendingTransition(R.anim.fade_in_up, R.anim.fade_out_down)
        }
    }

    // ===== THEME PICKER =====

    private fun setupThemePicker() {
        val themes = arrayOf(
            getString(R.string.settings_theme_dark),
            getString(R.string.settings_theme_light),
            getString(R.string.settings_theme_amoled)
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, themes)
        binding.spinnerTheme.adapter = adapter
        binding.spinnerTheme.setSelection(currentTheme)

        binding.spinnerTheme.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                currentTheme = pos
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ===== ACCENT COLORS =====

    private fun setupAccentColors() {
        val colorViews = listOf(
            binding.colorBlue,
            binding.colorGreen,
            binding.colorPurple,
            binding.colorRed
        )

        colorViews.forEachIndexed { index, view ->
            view.setOnClickListener {
                animateButtonPress(it)
                currentAccentColor = index
                updateAccentColorSelection(colorViews, index)
            }
        }

        updateAccentColorSelection(colorViews, currentAccentColor)
    }

    private fun updateAccentColorSelection(views: List<View>, selected: Int) {
        views.forEachIndexed { index, view ->
            if (index == selected) {
                view.scaleX = 1.3f
                view.scaleY = 1.3f
                view.alpha = 1f
            } else {
                view.scaleX = 1f
                view.scaleY = 1f
                view.alpha = 0.5f
            }
        }
    }

    // ===== FONT SIZE =====

    private fun setupFontSize() {
        binding.seekFontSize.progress = currentFontSize
        binding.tvFontSizeValue.text = "${currentFontSize}sp"

        binding.seekFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentFontSize = progress
                binding.tvFontSizeValue.text = "${progress}sp"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    // ===== DNS =====

    private fun setupDns() {
        binding.etDnsPrimary.setText(dnsPrimary)
        binding.etDnsSecondary.setText(dnsSecondary)
    }

    private fun validateDns(dns: String): Boolean {
        val parts = dns.split(".")
        if (parts.size != 4) return false
        return parts.all { part ->
            val num = part.toIntOrNull()
            num != null && num in 0..255
        }
    }

    // ===== MTU =====

    private fun setupMtu() {
        binding.seekMtu.progress = mtuSize
        binding.tvMtuValue.text = mtuSize.toString()

        binding.seekMtu.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                mtuSize = progress
                binding.tvMtuValue.text = progress.toString()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    // ===== PROTOCOL =====

    private fun setupProtocol() {
        val protocols = arrayOf("OpenVPN (UDP)", "OpenVPN (TCP)", "Auto")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, protocols)
        binding.spinnerProtocol.adapter = adapter
        binding.spinnerProtocol.setSelection(currentProtocol)

        binding.spinnerProtocol.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                currentProtocol = pos
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ===== MIRRORS =====

    private fun setupMirrors() {
        refreshMirrorList()

        binding.btnAddMirror.setOnClickListener {
            animateButtonPress(it)
            val url = binding.etNewMirror.text.toString().trim()
            if (url.isNotEmpty() && (url.startsWith("http://") || url.startsWith("https://"))) {
                mirrorUrls.add(url)
                VpnGateRepository.addCustomMirror(url)
                binding.etNewMirror.text?.clear()
                refreshMirrorList()
                Toast.makeText(this, getString(R.string.mirror_added), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.invalid_url), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshMirrorList() {
        binding.layoutMirrorList.removeAllViews()

        mirrorUrls.forEachIndexed { index, url ->
            val mirrorView = createMirrorItem(url, index)
            binding.layoutMirrorList.addView(mirrorView)
        }
    }

    private fun createMirrorItem(url: String, index: Int): LinearLayout {
        return LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 6.dp() }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_input_field)
            setPadding(12.dp(), 8.dp(), 8.dp(), 8.dp())

            val urlText = TextView(this@SettingsActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = url
                textSize = 11f
                setTextColor(Color.parseColor("#E0E0E0"))
                maxLines = 1
            }
            addView(urlText)

            val removeBtn = ImageView(this@SettingsActivity).apply {
                layoutParams = LinearLayout.LayoutParams(28.dp(), 28.dp())
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                setColorFilter(Color.parseColor("#FF5252"))
                setPadding(4.dp(), 4.dp(), 4.dp(), 4.dp())
                setOnClickListener {
                    animateButtonPress(it)
                    mirrorUrls.removeAt(index)
                    VpnGateRepository.removeCustomMirror(url)
                    refreshMirrorList()
                }
            }
            addView(removeBtn)
        }
    }

    // ===== AUTO CONNECT =====

    private fun setupAutoConnect() {
        binding.switchAutoConnect.isChecked = autoConnect
        binding.switchAutoConnect.setOnCheckedChangeListener { _, isChecked ->
            autoConnect = isChecked
        }
    }

    // ===== COUNTRY PICKER =====

    private fun setupCountryPicker() {
        val countries = arrayOf("Any", "Japan", "Korea", "USA", "Germany", "Netherlands", "Russia")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, countries)
        binding.spinnerCountry.adapter = adapter

        val selectedIndex = when (preferredCountry) {
            "JP" -> 1
            "KR" -> 2
            "US" -> 3
            "DE" -> 4
            "NL" -> 5
            "RU" -> 6
            else -> 0
        }
        binding.spinnerCountry.setSelection(selectedIndex)

        binding.spinnerCountry.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                preferredCountry = when (pos) {
                    1 -> "JP"
                    2 -> "KR"
                    3 -> "US"
                    4 -> "DE"
                    5 -> "NL"
                    6 -> "RU"
                    else -> ""
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ===== ABOUT SECTION =====

    private fun setupAboutSection() {
        binding.tvVersion.text = "1.0.0"

        binding.btnGithub.setOnClickListener {
            animateButtonPress(it)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com"))
            startActivity(intent)
        }

        binding.btnClearCache.setOnClickListener {
            animateButtonPress(it)
            showClearCacheDialog()
        }
    }

    private fun showClearCacheDialog() {
        AlertDialog.Builder(this, R.style.Theme_RadminVPN_Dialog)
            .setTitle(R.string.settings_clear_cache)
            .setMessage(R.string.clear_cache_confirm)
            .setPositiveButton(R.string.clear) { _, _ ->
                clearCache()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun clearCache() {
        cacheDir.deleteRecursively()
        Toast.makeText(this, getString(R.string.cache_cleared), Toast.LENGTH_SHORT).show()
    }

    // ===== SAVE =====

    private fun setupSaveButton() {
        binding.btnSaveSettings.setOnClickListener {
            animateButtonPress(it)
            saveSettings()
        }
    }

    private fun saveSettings() {
        // Validate DNS
        val primaryDns = binding.etDnsPrimary.text.toString().trim()
        val secondaryDns = binding.etDnsSecondary.text.toString().trim()

        if (primaryDns.isNotEmpty() && !validateDns(primaryDns)) {
            Toast.makeText(this, getString(R.string.invalid_dns_primary), Toast.LENGTH_SHORT).show()
            return
        }
        if (secondaryDns.isNotEmpty() && !validateDns(secondaryDns)) {
            Toast.makeText(this, getString(R.string.invalid_dns_secondary), Toast.LENGTH_SHORT).show()
            return
        }

        dnsPrimary = primaryDns.ifEmpty { "8.8.8.8" }
        dnsSecondary = secondaryDns.ifEmpty { "8.8.4.4" }

        // Save to SharedPreferences
        prefs.edit().apply {
            putInt("theme", currentTheme)
            putInt("accent_color", currentAccentColor)
            putInt("font_size", currentFontSize)
            putString("dns_primary", dnsPrimary)
            putString("dns_secondary", dnsSecondary)
            putInt("mtu_size", mtuSize)
            putInt("protocol", currentProtocol)
            putBoolean("auto_connect", autoConnect)
            putString("preferred_country", preferredCountry)
            putStringSet("custom_mirrors", mirrorUrls.toSet())
            apply()
        }

        // Update VpnGateRepository mirrors
        VpnGateRepository.setCustomMirrors(mirrorUrls)

        // Apply theme if changed
        applyTheme()

        // Visual feedback
        binding.btnSaveSettings.animate()
            .scaleX(0.95f).scaleY(0.95f)
            .setDuration(100)
            .withEndAction {
                binding.btnSaveSettings.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(200)
                    .setInterpolator(OvershootInterpolator())
                    .start()
            }.start()

        Toast.makeText(this, getString(R.string.settings_saved_toast), Toast.LENGTH_SHORT).show()
    }

    private fun applyTheme() {
        when (currentTheme) {
            0 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            1 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            2 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }
    }

    // ===== ANIMATIONS =====

    private fun animateEntrance() {
        val content = binding.root.getChildAt(1) // NestedScrollView
        content?.alpha = 0f
        content?.translationY = 30f
        content?.animate()
            ?.alpha(1f)
            ?.translationY(0f)
            ?.setDuration(400)
            ?.setStartDelay(100)
            ?.start()
    }

    private fun animateButtonPress(view: View) {
        val scaleDown = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, "scaleX", 1f, 0.93f),
                ObjectAnimator.ofFloat(view, "scaleY", 1f, 0.93f)
            )
            duration = 80
        }
        val scaleUp = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, "scaleX", 0.93f, 1f),
                ObjectAnimator.ofFloat(view, "scaleY", 0.93f, 1f)
            )
            duration = 120
            interpolator = OvershootInterpolator()
        }
        AnimatorSet().apply {
            playSequentially(scaleDown, scaleUp)
            start()
        }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.fade_in_up, R.anim.fade_out_down)
    }
}
