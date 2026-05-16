package com.radminvpn.android.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.radminvpn.android.R
import com.radminvpn.android.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "vpn_settings"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_IP_PREFIX = "ip_prefix"
        const val KEY_MTU = "mtu"
        const val KEY_ENABLE_TURN = "enable_turn"
        const val KEY_CUSTOM_STUN = "custom_stun"
        const val KEY_CUSTOM_TURN = "custom_turn"
        const val KEY_THEME = "theme"
        const val KEY_SHOW_LOGS = "show_logs"

        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"
        const val THEME_SYSTEM = "system"

        const val DEFAULT_IP_PREFIX = "10.0.0"
        const val DEFAULT_MTU = 1400
        const val MTU_MIN = 1200
        const val MTU_MAX = 1500

        fun getPrefs(context: Context): SharedPreferences {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        loadSettings()
        setupListeners()
    }

    private fun loadSettings() {
        binding.etDisplayName.setText(prefs.getString(KEY_DISPLAY_NAME, ""))
        binding.etIpPrefix.setText(prefs.getString(KEY_IP_PREFIX, DEFAULT_IP_PREFIX))
        binding.etMtu.setText(prefs.getInt(KEY_MTU, DEFAULT_MTU).toString())
        binding.switchTurn.isChecked = prefs.getBoolean(KEY_ENABLE_TURN, true)
        binding.etCustomStun.setText(prefs.getString(KEY_CUSTOM_STUN, ""))
        binding.etCustomTurn.setText(prefs.getString(KEY_CUSTOM_TURN, ""))
        binding.switchLogs.isChecked = prefs.getBoolean(KEY_SHOW_LOGS, true)

        // Set theme chip
        when (prefs.getString(KEY_THEME, THEME_DARK)) {
            THEME_DARK -> binding.chipDark.isChecked = true
            THEME_LIGHT -> binding.chipLight.isChecked = true
            THEME_SYSTEM -> binding.chipSystem.isChecked = true
            else -> binding.chipDark.isChecked = true
        }

        // Version
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            binding.tvVersion.text = pInfo.versionName ?: "1.0"
        } catch (_: Exception) {
            binding.tvVersion.text = "1.0"
        }
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnSave.setOnClickListener {
            saveSettings()
        }

        binding.layoutGithub.setOnClickListener {
            try {
                val url = getString(R.string.settings_github_url)
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Exception) {}
        }
    }

    private fun saveSettings() {
        val mtuStr = binding.etMtu.text.toString().trim()
        val mtuValue = mtuStr.toIntOrNull() ?: DEFAULT_MTU
        val mtuClamped = mtuValue.coerceIn(MTU_MIN, MTU_MAX)

        val selectedTheme = when {
            binding.chipDark.isChecked -> THEME_DARK
            binding.chipLight.isChecked -> THEME_LIGHT
            binding.chipSystem.isChecked -> THEME_SYSTEM
            else -> THEME_DARK
        }

        prefs.edit().apply {
            putString(KEY_DISPLAY_NAME, binding.etDisplayName.text.toString().trim())
            putString(KEY_IP_PREFIX, binding.etIpPrefix.text.toString().trim().ifEmpty { DEFAULT_IP_PREFIX })
            putInt(KEY_MTU, mtuClamped)
            putBoolean(KEY_ENABLE_TURN, binding.switchTurn.isChecked)
            putString(KEY_CUSTOM_STUN, binding.etCustomStun.text.toString().trim())
            putString(KEY_CUSTOM_TURN, binding.etCustomTurn.text.toString().trim())
            putString(KEY_THEME, selectedTheme)
            putBoolean(KEY_SHOW_LOGS, binding.switchLogs.isChecked)
            apply()
        }

        applyTheme(selectedTheme)

        Toast.makeText(this, R.string.settings_saved_toast, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun applyTheme(theme: String) {
        when (theme) {
            THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            THEME_SYSTEM -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }
}
