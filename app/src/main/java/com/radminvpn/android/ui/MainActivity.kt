package com.radminvpn.android.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.radminvpn.android.databinding.ActivityMainBinding
import com.radminvpn.android.model.ConnectionState
import com.radminvpn.android.vpn.VpnOrchestrator
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var orchestrator: VpnOrchestrator

    private var pendingAction: (() -> Unit)? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingAction?.invoke()
            pendingAction = null
        } else {
            Toast.makeText(this, "VPN разрешение отклонено", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        orchestrator = VpnOrchestrator(this)

        setupButtons()
        observeState()
    }

    private fun setupButtons() {
        binding.btnCreateNetwork.setOnClickListener {
            requestVpnPermission { orchestrator.createNetwork() }
        }

        binding.btnJoinNetwork.setOnClickListener {
            val networkId = binding.etNetworkId.text.toString().trim().uppercase()
            if (networkId.length != 6) {
                Toast.makeText(this, "Введите 6-символьный ID сети", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            requestVpnPermission { orchestrator.joinNetwork(networkId) }
        }

        binding.btnDisconnect.setOnClickListener {
            orchestrator.disconnect()
        }

        // Копировать ID сети при клике
        binding.tvNetworkId.setOnClickListener {
            val id = orchestrator.networkId.value
            if (id.isNotEmpty()) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Network ID", id))
                Toast.makeText(this, "ID скопирован: $id", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            orchestrator.connectionState.collect { state ->
                updateUI(state)
            }
        }

        lifecycleScope.launch {
            orchestrator.virtualIp.collect { ip ->
                if (ip.isNotEmpty()) {
                    binding.tvVirtualIp.text = "Ваш IP: $ip"
                    binding.tvVirtualIp.visibility = View.VISIBLE
                } else {
                    binding.tvVirtualIp.visibility = View.GONE
                }
            }
        }

        lifecycleScope.launch {
            orchestrator.networkId.collect { id ->
                if (id.isNotEmpty()) {
                    binding.tvNetworkId.text = "Сеть: $id (нажмите чтобы скопировать)"
                    binding.tvNetworkId.visibility = View.VISIBLE
                } else {
                    binding.tvNetworkId.visibility = View.GONE
                }
            }
        }

        lifecycleScope.launch {
            orchestrator.statusMessage.collect { msg ->
                binding.tvStatusMessage.text = msg
            }
        }

        lifecycleScope.launch {
            orchestrator.peers.collect { peers ->
                if (peers.isNotEmpty()) {
                    binding.tvPeersTitle.visibility = View.VISIBLE
                    binding.tvPeersList.visibility = View.VISIBLE
                    binding.tvPeersList.text = peers.joinToString("\n") {
                        "• ${it.virtualIp} (${it.peerId})"
                    }
                } else {
                    binding.tvPeersTitle.visibility = View.GONE
                    binding.tvPeersList.visibility = View.GONE
                }
            }
        }
    }

    private fun updateUI(state: ConnectionState) {
        when (state) {
            ConnectionState.DISCONNECTED -> {
                binding.tvStatus.text = "⚫ Отключено"
                binding.btnCreateNetwork.isEnabled = true
                binding.btnJoinNetwork.isEnabled = true
                binding.etNetworkId.isEnabled = true
                binding.btnCreateNetwork.visibility = View.VISIBLE
                binding.btnJoinNetwork.visibility = View.VISIBLE
                binding.btnDisconnect.visibility = View.GONE
            }
            ConnectionState.CONNECTING -> {
                binding.tvStatus.text = "🟡 Подключение..."
                binding.btnCreateNetwork.isEnabled = false
                binding.btnJoinNetwork.isEnabled = false
                binding.etNetworkId.isEnabled = false
            }
            ConnectionState.CONNECTED -> {
                binding.tvStatus.text = "🟢 Подключено"
                binding.btnCreateNetwork.visibility = View.GONE
                binding.btnJoinNetwork.visibility = View.GONE
                binding.btnDisconnect.visibility = View.VISIBLE
            }
        }
    }

    private fun requestVpnPermission(action: () -> Unit) {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            pendingAction = action
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            action()
        }
    }

    override fun onDestroy() {
        orchestrator.destroy()
        super.onDestroy()
    }
}
