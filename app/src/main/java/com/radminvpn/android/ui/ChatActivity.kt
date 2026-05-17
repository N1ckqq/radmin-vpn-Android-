package com.radminvpn.android.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.radminvpn.android.R
import com.radminvpn.android.databinding.ActivityChatBinding
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var messageAdapter: MessageAdapter

    // File attachment
    private var attachedFileUri: Uri? = null
    private var attachedFileName = ""
    private var attachedFileSize = 0L

    // Typing indicator
    private val typingHandler = Handler(Looper.getMainLooper())
    private var isTyping = false

    companion object {
        private const val PICK_FILE_REQUEST = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupClickListeners()
        setupTextWatcher()
        animateEntrance()
    }

    // ===== RECYCLERVIEW =====

    private fun setupRecyclerView() {
        messageAdapter = MessageAdapter(messages)
        binding.rvMessages.apply {
            layoutManager = LinearLayoutManager(this@ChatActivity).apply {
                stackFromEnd = true
            }
            adapter = messageAdapter
        }
    }

    // ===== CLICK LISTENERS =====

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            animateButtonPress(it)
            finish()
            overridePendingTransition(R.anim.fade_in_up, R.anim.fade_out_down)
        }

        binding.btnSend.setOnClickListener {
            animateButtonPress(it)
            sendMessage()
        }

        binding.btnAttachFile.setOnClickListener {
            animateButtonPress(it)
            pickFile()
        }

        binding.btnRemoveAttachment.setOnClickListener {
            animateButtonPress(it)
            removeAttachment()
        }
    }

    // ===== TEXT WATCHER =====

    private fun setupTextWatcher() {
        binding.etMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Show send button active state
                val hasText = !s.isNullOrBlank() || attachedFileUri != null
                binding.btnSend.alpha = if (hasText) 1f else 0.5f
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    // ===== ANIMATIONS =====

    private fun animateEntrance() {
        binding.layoutEmptyState.alpha = 0f
        binding.layoutEmptyState.translationY = 30f
        binding.layoutEmptyState.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(500)
            .setStartDelay(200)
            .start()
    }

    private fun animateButtonPress(view: View) {
        val scaleDown = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, "scaleX", 1f, 0.9f),
                ObjectAnimator.ofFloat(view, "scaleY", 1f, 0.9f)
            )
            duration = 80
        }
        val scaleUp = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, "scaleX", 0.9f, 1f),
                ObjectAnimator.ofFloat(view, "scaleY", 0.9f, 1f)
            )
            duration = 120
            interpolator = OvershootInterpolator()
        }
        AnimatorSet().apply {
            playSequentially(scaleDown, scaleUp)
            start()
        }
    }

    private fun animateNewMessage(position: Int) {
        binding.rvMessages.postDelayed({
            val viewHolder = binding.rvMessages.findViewHolderForAdapterPosition(position)
            viewHolder?.itemView?.let { view ->
                view.alpha = 0f
                view.translationY = 30f
                view.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(300)
                    .setInterpolator(OvershootInterpolator(0.5f))
                    .start()
            }
        }, 50)
    }

    // ===== SEND MESSAGE =====

    private fun sendMessage() {
        val text = binding.etMessage.text.toString().trim()

        if (text.isEmpty() && attachedFileUri == null) return

        // Create message
        val message = if (attachedFileUri != null) {
            ChatMessage(
                text = if (text.isNotEmpty()) text else null,
                isMine = true,
                timestamp = System.currentTimeMillis(),
                type = MessageType.FILE,
                fileName = attachedFileName,
                fileSize = attachedFileSize,
                fileUri = attachedFileUri.toString()
            )
        } else {
            ChatMessage(
                text = text,
                isMine = true,
                timestamp = System.currentTimeMillis(),
                type = MessageType.TEXT
            )
        }

        messages.add(message)
        messageAdapter.notifyItemInserted(messages.size - 1)
        binding.rvMessages.scrollToPosition(messages.size - 1)
        animateNewMessage(messages.size - 1)

        // Clear input
        binding.etMessage.text?.clear()
        removeAttachment()

        // Hide empty state
        binding.layoutEmptyState.isVisible = false

        // Simulate peer response
        simulatePeerResponse()
    }

    // ===== FILE HANDLING =====

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(intent, getString(R.string.select_file)), PICK_FILE_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_FILE_REQUEST && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                attachedFileUri = uri
                getFileInfo(uri)
                showAttachmentPreview()
            }
        }
    }

    private fun getFileInfo(uri: Uri) {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) attachedFileName = cursor.getString(nameIndex)
                if (sizeIndex >= 0) attachedFileSize = cursor.getLong(sizeIndex)
            }
        }
    }

    private fun showAttachmentPreview() {
        binding.layoutAttachmentPreview.isVisible = true
        binding.layoutAttachmentPreview.alpha = 0f
        binding.layoutAttachmentPreview.animate().alpha(1f).setDuration(200).start()
        binding.tvAttachmentName.text = attachedFileName
        binding.tvAttachmentSize.text = formatFileSize(attachedFileSize)
    }

    private fun removeAttachment() {
        attachedFileUri = null
        attachedFileName = ""
        attachedFileSize = 0L
        binding.layoutAttachmentPreview.animate().alpha(0f).setDuration(150).withEndAction {
            binding.layoutAttachmentPreview.isVisible = false
        }.start()
    }

    // ===== SIMULATE PEER =====

    private fun simulatePeerResponse() {
        // Show typing indicator
        showTypingIndicator()

        typingHandler.postDelayed({
            hideTypingIndicator()

            val responses = listOf(
                "Got it!",
                "Thanks for sharing",
                "Connected successfully",
                "File received, downloading...",
                "Nice, connection is stable!",
                "Ping is great from here"
            )

            val response = ChatMessage(
                text = responses.random(),
                isMine = false,
                timestamp = System.currentTimeMillis(),
                type = MessageType.TEXT
            )

            messages.add(response)
            messageAdapter.notifyItemInserted(messages.size - 1)
            binding.rvMessages.scrollToPosition(messages.size - 1)
            animateNewMessage(messages.size - 1)
        }, (1500 + Math.random() * 2000).toLong())
    }

    private fun showTypingIndicator() {
        binding.layoutTypingIndicator.isVisible = true
        binding.layoutTypingIndicator.alpha = 0f
        binding.layoutTypingIndicator.animate().alpha(1f).setDuration(200).start()
    }

    private fun hideTypingIndicator() {
        binding.layoutTypingIndicator.animate().alpha(0f).setDuration(150).withEndAction {
            binding.layoutTypingIndicator.isVisible = false
        }.start()
    }

    // ===== MESSAGE ADAPTER =====

    inner class MessageAdapter(private val items: List<ChatMessage>) :
        RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

        inner class MessageViewHolder(val view: View) : RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
            val layout = LinearLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 8.dp()
                }
                orientation = LinearLayout.VERTICAL
            }
            return MessageViewHolder(layout)
        }

        override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
            val message = items[position]
            val layout = holder.view as LinearLayout
            layout.removeAllViews()

            layout.gravity = if (message.isMine) Gravity.END else Gravity.START

            // Message bubble
            val bubble = LinearLayout(this@ChatActivity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(
                    if (message.isMine) R.drawable.bg_chat_bubble_mine
                    else R.drawable.bg_chat_bubble_peer
                )
                setPadding(14.dp(), 10.dp(), 14.dp(), 10.dp())
                layoutParams = FrameLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // File indicator
            if (message.type == MessageType.FILE) {
                val fileLayout = LinearLayout(this@ChatActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 0, 0, 6.dp())
                }

                val fileIcon = ImageView(this@ChatActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(20.dp(), 20.dp()).apply {
                        marginEnd = 8.dp()
                    }
                    setImageResource(android.R.drawable.ic_menu_recent_history)
                    setColorFilter(if (message.isMine) 0xFFFFFFFF.toInt() else 0xFF2979FF.toInt())
                }
                fileLayout.addView(fileIcon)

                val fileInfo = LinearLayout(this@ChatActivity).apply {
                    orientation = LinearLayout.VERTICAL
                }

                val fileName = TextView(this@ChatActivity).apply {
                    text = message.fileName ?: "File"
                    textSize = 12f
                    setTextColor(if (message.isMine) 0xFFFFFFFF.toInt() else 0xFFE0E0E0.toInt())
                    maxLines = 1
                }
                fileInfo.addView(fileName)

                val fileSize = TextView(this@ChatActivity).apply {
                    text = formatFileSize(message.fileSize)
                    textSize = 10f
                    setTextColor(if (message.isMine) 0xCCFFFFFF.toInt() else 0xFF6E6E7E.toInt())
                }
                fileInfo.addView(fileSize)

                fileLayout.addView(fileInfo)
                bubble.addView(fileLayout)
            }

            // Text content
            if (!message.text.isNullOrEmpty()) {
                val textView = TextView(this@ChatActivity).apply {
                    text = message.text
                    textSize = 14f
                    setTextColor(if (message.isMine) 0xFFFFFFFF.toInt() else 0xFFE0E0E0.toInt())
                }
                bubble.addView(textView)
            }

            // Timestamp
            val timeView = TextView(this@ChatActivity).apply {
                text = formatTime(message.timestamp)
                textSize = 10f
                setTextColor(if (message.isMine) 0x99FFFFFF.toInt() else 0xFF4E4E5E.toInt())
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 4.dp() }
            }
            bubble.addView(timeView)

            layout.addView(bubble)
        }

        override fun getItemCount() = items.size
    }

    // ===== DATA CLASSES =====

    data class ChatMessage(
        val text: String? = null,
        val isMine: Boolean,
        val timestamp: Long,
        val type: MessageType = MessageType.TEXT,
        val fileName: String? = null,
        val fileSize: Long = 0L,
        val fileUri: String? = null
    )

    enum class MessageType { TEXT, FILE }

    // ===== UTILS =====

    private fun formatTime(timestamp: Long): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1048576 -> "${bytes / 1024} KB"
            bytes < 1073741824 -> String.format("%.1f MB", bytes / 1048576.0)
            else -> String.format("%.2f GB", bytes / 1073741824.0)
        }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.fade_in_up, R.anim.fade_out_down)
    }
}
