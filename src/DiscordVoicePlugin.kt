package org.extera.discord

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ChatActivity
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

data class DiscordMember(
    val username: String,
    val isMuted: Boolean,
    val isDeaf: Boolean
)

data class DiscordVoiceChannel(
    val name: String,
    val position: Int,
    val members: MutableList<DiscordMember>
)

data class DiscordGuildInfo(
    val id: String,
    val name: String,
    val instantInvite: String?,
    val presenceCount: Int,
    val channels: List<DiscordVoiceChannel>
)

class DiscordApi {
    fun fetchWidget(guildId: String): DiscordGuildInfo? {
        return try {
            val url = URL("https://discord.com/api/guilds/$guildId/widget.json")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
            }
            if (conn.responseCode != 200) return null

            val text = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            val json = JSONObject(text)

            val name = json.optString("name", "Discord Server")
            val invite = json.optString("instant_invite", null)
            val presence = json.optInt("presence_count", 0)

            val channelMap = mutableMapOf<String, DiscordVoiceChannel>()
            val channelsArr = json.optJSONArray("channels")
            if (channelsArr != null) {
                for (i in 0 until channelsArr.length()) {
                    val c = channelsArr.getJSONObject(i)
                    channelMap[c.getString("id")] = DiscordVoiceChannel(
                        c.getString("name"),
                        c.optInt("position", i),
                        mutableListOf()
                    )
                }
            }

            val membersArr = json.optJSONArray("members")
            if (membersArr != null) {
                for (i in 0 until membersArr.length()) {
                    val m = membersArr.getJSONObject(i)
                    val chId = m.optString("channel_id", null) ?: continue
                    val member = DiscordMember(
                        m.getString("username"),
                        m.optBoolean("mute", false) || m.optBoolean("self_mute", false),
                        m.optBoolean("deaf", false) || m.optBoolean("self_deaf", false)
                    )
                    val ch = channelMap[chId]
                    if (ch != null) {
                        ch.members.add(member)
                    } else {
                        channelMap[chId] = DiscordVoiceChannel("Голосовой канал", 999, mutableListOf(member))
                    }
                }
            }

            DiscordGuildInfo(
                id = guildId,
                name = name,
                instantInvite = invite,
                presenceCount = presence,
                channels = channelMap.values.filter { it.members.isNotEmpty() }.sortedBy { it.position }
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

class VoiceBottomSheet(
    context: Context,
    private val info: DiscordGuildInfo,
    private val onShare: (String) -> Unit
) : BottomSheet(context, false) {

    init {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 36, 48, 48)
        }

        val title = TextView(context).apply {
            text = "⚡ ${info.name}"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(title)

        val totalVoice = info.channels.sumOf { it.members.size }
        val subtitle = TextView(context).apply {
            text = "Онлайн: ${info.presenceCount}  •  В войсах: $totalVoice"
            textSize = 13f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 8, 0, 20)
        }
        root.addView(subtitle)

        val scrollContent = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        if (info.channels.isEmpty()) {
            val empty = TextView(context).apply {
                text = "В голосовых каналах сейчас пусто 😴"
                textSize = 14f
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, 24, 0, 24)
            }
            scrollContent.addView(empty)
        } else {
            for (ch in info.channels) {
                val header = TextView(context).apply {
                    text = "🔊 ${ch.name} (${ch.members.size})"
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, 12, 0, 6)
                }
                scrollContent.addView(header)

                for (m in ch.members) {
                    val status = if (m.isMuted) " 🔇" else ""
                    val row = TextView(context).apply {
                        text = "   • ${m.username}$status"
                        textSize = 14f
                        setPadding(0, 2, 0, 2)
                    }
                    scrollContent.addView(row)
                }
            }
        }

        val scroll = ScrollView(context).apply {
            addView(scrollContent)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f)
        }
        root.addView(scroll)

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 0)
        }

        val shareBtn = Button(context).apply {
            text = "Скинуть в чат"
            setOnClickListener {
                onShare(buildShareText())
                dismiss()
            }
        }
        btnRow.addView(shareBtn)

        if (!info.instantInvite.isNullOrEmpty()) {
            val joinBtn = Button(context).apply {
                text = "Перейти"
                setOnClickListener {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.instantInvite)))
                }
            }
            btnRow.addView(joinBtn)
        }

        root.addView(btnRow)
        setCustomView(root)
    }

    private fun buildShareText(): String {
        val sb = StringBuilder("⚡ **Discord: ${info.name}**\n")
        if (info.channels.isEmpty()) {
            sb.append("В войсах сейчас никого нет.\n")
        } else {
            for (ch in info.channels) {
                sb.append("🔊 *${ch.name}:* ").append(ch.members.joinToString(", ") { it.username }).append("\n")
            }
        }
        if (!info.instantInvite.isNullOrEmpty()) {
            sb.append("\n🔗 Залетайте: ${info.instantInvite}")
        }
        return sb.toString()
    }
}

class DiscordVoicePlugin {
    companion object {
        private const val PREFS = "discord_pulse_prefs"
        private const val KEY_ID = "server_id"
    }

    private val api = DiscordApi()

    fun open(fragment: BaseFragment) {
        val context = fragment.context ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val guildId = prefs.getString(KEY_ID, "") ?: ""

        if (guildId.isEmpty()) {
            showSettings(context) { open(fragment) }
            return
        }

        val dialog = AlertDialog(context, 3).apply {
            setMessage("Сканирование Discord...")
            show()
        }

        thread {
            val res = api.fetchWidget(guildId)
            Handler(Looper.getMainLooper()).post {
                dialog.dismiss()
                if (res == null) {
                    Toast.makeText(context, "Не удалось загрузить. Проверьте ID или Виджет сервера.", Toast.LENGTH_LONG).show()
                    showSettings(context)
                    return@post
                }
                VoiceBottomSheet(context, res) { text ->
                    if (fragment is ChatActivity) {
                        fragment.chatActivityEnterView?.field?.setText(text)
                    } else {
                        AndroidUtilities.addToClipboard(text)
                        Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                    }
                }.show()
            }
        }
    }

    fun showSettings(context: Context, onSaved: (() -> Unit)? = null) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val input = EditText(context).apply {
            hint = "ID Сервера Discord"
            setText(prefs.getString(KEY_ID, ""))
        }

        AlertDialog.Builder(context)
            .setTitle("Discord Pulse — Настройки")
            .setMessage("Введите ID сервера (виджет сервера должен быть включен в Discord):")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                prefs.edit().putString(KEY_ID, input.text.toString().trim()).apply()
                Toast.makeText(context, "Сохранено", Toast.LENGTH_SHORT).show()
                onSaved?.invoke()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
