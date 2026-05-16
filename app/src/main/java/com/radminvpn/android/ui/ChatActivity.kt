package com.radminvpn.android.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.radminvpn.android.databinding.ActivityChatBinding
import com.radminvpn.android.util.VpnLog
import com.radminvpn.android.vpn.P2PVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class ChatActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Chat"
        const val CHAT_PREFIX = "CHAT:"

        // Shared message callback - set by this activity so other components can deliver chat msgs
        var onChatMessageReceived: ((String, String) -> Unit)? = null
    }

    private lateinit var binding: ActivityChatBinding
    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: ChatAdapter
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupRecyclerView()
        setupInput()
        updateConnectionStatus()
        registerMessageCallback()

        VpnLog.i(TAG, "Chat activity started")
    }

    private fun setupRecyclerView() {
        adapter = ChatAdapter(messages)
        binding.rvMessages.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.rvMessages.adapter = adapter
    }

    private fun setupInput() {
        binding.fabSend.setOnClickListener {
            val text = binding.etMessage.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener

            val vpnService = P2PVpnService.instance
            if (vpnService == null) {
                Toast.makeText(this, "Not connected - start VPN first", Toast.LENGTH_SHORT).show()
                VpnLog.w(TAG, "Cannot send message: VPN service not running")
                return@setOnClickListener
            }

            sendMessage(text)
            binding.etMessage.text?.clear()
        }
    }

    private fun sendMessage(text: String) {
        val chatData = "$CHAT_PREFIX$text".toByteArray()

        // Send through WebRTC DataChannel by finding the active connection
        val sent = sendChatViaWebRtc(chatData)

        if (sent) {
            val message = ChatMessage(
                text = text,
                isMine = true,
                timestamp = System.currentTimeMillis()
            )
            addMessage(message)
            VpnLog.d(TAG, "Message sent: ${text.take(50)}")
        } else {
            Toast.makeText(this, "Failed to send - no active peer connection", Toast.LENGTH_SHORT).show()
            VpnLog.w(TAG, "Failed to send chat message: no active connection")
        }
    }

    private fun sendChatViaWebRtc(data: ByteArray): Boolean {
        // Access the VpnOrchestrator's WebRtcManager indirectly through the service
        // The P2PVpnService.onPacketReceived callback sends data through WebRTC
        // For chat, we directly use the service's packet mechanism
        val vpnService = P2PVpnService.instance ?: return false
        vpnService.onPacketReceived?.invoke(data)
        return true
    }

    private fun addMessage(message: ChatMessage) {
        lifecycleScope.launch(Dispatchers.Main) {
            messages.add(message)
            adapter.notifyItemInserted(messages.size - 1)
            binding.rvMessages.scrollToPosition(messages.size - 1)
        }
    }

    private fun updateConnectionStatus() {
        val isConnected = P2PVpnService.instance != null
        val dot = binding.viewConnectionDot.background
        if (dot is GradientDrawable) {
            dot.setColor(if (isConnected) Color.parseColor("#4CAF50") else Color.parseColor("#9E9E9E"))
        }
        binding.tvConnectionStatus.text = if (isConnected) "Connected - P2P tunnel active" else "Not connected"
    }

    private fun registerMessageCallback() {
        onChatMessageReceived = { peerId, text ->
            lifecycleScope.launch(Dispatchers.Main) {
                val message = ChatMessage(
                    text = text,
                    isMine = false,
                    timestamp = System.currentTimeMillis(),
                    senderId = peerId
                )
                addMessage(message)
                VpnLog.d(TAG, "Message received from $peerId: ${text.take(50)}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateConnectionStatus()
    }

    override fun onDestroy() {
        onChatMessageReceived = null
        super.onDestroy()
    }

    // --- Data Classes ---

    data class ChatMessage(
        val text: String,
        val isMine: Boolean,
        val timestamp: Long,
        val senderId: String = "me"
    )

    // --- RecyclerView Adapter ---

    inner class ChatAdapter(
        private val items: List<ChatMessage>
    ) : RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {

        inner class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val container: LinearLayout = view as LinearLayout
            val tvMessage: TextView = view.findViewWithTag("message")
            val tvTime: TextView = view.findViewWithTag("time")
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
            val container = LinearLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 8
                }
                orientation = LinearLayout.VERTICAL
                setPadding(8, 4, 8, 4)
            }

            val bubble = TextView(parent.context).apply {
                tag = "message"
                textSize = 15f
                setPadding(32, 16, 32, 16)
                maxWidth = (parent.width * 0.75).toInt()
            }
            container.addView(bubble)

            val timeText = TextView(parent.context).apply {
                tag = "time"
                textSize = 11f
                setTextColor(Color.parseColor("#9E9E9E"))
                setPadding(32, 2, 32, 0)
            }
            container.addView(timeText)

            return MessageViewHolder(container)
        }

        override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
            val msg = items[position]

            holder.tvMessage.text = msg.text
            holder.tvTime.text = timeFormat.format(Date(msg.timestamp))

            if (msg.isMine) {
                holder.container.gravity = Gravity.END
                holder.tvMessage.gravity = Gravity.END
                holder.tvTime.gravity = Gravity.END

                val bg = GradientDrawable().apply {
                    cornerRadius = 36f
                    setColor(Color.parseColor("#1565C0"))
                }
                holder.tvMessage.background = bg
                holder.tvMessage.setTextColor(Color.WHITE)
            } else {
                holder.container.gravity = Gravity.START
                holder.tvMessage.gravity = Gravity.START
                holder.tvTime.gravity = Gravity.START

                val bg = GradientDrawable().apply {
                    cornerRadius = 36f
                    setColor(Color.parseColor("#E0E0E0"))
                }
                holder.tvMessage.background = bg
                holder.tvMessage.setTextColor(Color.parseColor("#212121"))
                holder.tvTime.text = "${msg.senderId} - ${timeFormat.format(Date(msg.timestamp))}"
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
