package org.extera.discord;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DiscordVoicePlugin {
    private static final String PREFS_NAME = "discord_pulse_prefs";
    private static final String KEY_GUILD_ID = "guild_id";

    public void init(Context context) {
    }

    public void onLoad(Context context) {
    }

    public void open(Context context) {
        showMainDialog(context);
    }

    public void showSettings(Context context) {
        showSettingsDialog(context, null);
    }

    public void showMainDialog(final Context context) {
        final SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        final String guildId = prefs.getString(KEY_GUILD_ID, "");

        if (guildId.isEmpty()) {
            showSettingsDialog(context, new Runnable() {
                @Override
                public void run() {
                    showMainDialog(context);
                }
            });
            return;
        }

        final AlertDialog loading = new AlertDialog.Builder(context)
                .setTitle("Discord Pulse")
                .setMessage("⚡ Сканирование Discord серверов...")
                .setCancelable(false)
                .show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                final GuildInfo info = fetchGuildData(guildId);
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        loading.dismiss();
                        if (info == null) {
                            Toast.makeText(context, "Не удалось загрузить данные. Проверьте ID или виджет сервера.", Toast.LENGTH_LONG).show();
                            showSettingsDialog(context, null);
                            return;
                        }
                        displayVoiceDialog(context, info);
                    }
                });
            }
        }).start();
    }

    private void displayVoiceDialog(final Context context, final GuildInfo info) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 30, 48, 30);

        TextView statsView = new TextView(context);
        int totalInVoice = 0;
        for (Channel c : info.channels) {
            totalInVoice += c.members.size();
        }
        statsView.setText("👥 Онлайн: " + info.presenceCount + "  •  🔊 В войсах: " + totalInVoice);
        statsView.setTextSize(14f);
        statsView.setPadding(0, 0, 0, 20);
        statsView.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(statsView);

        ScrollView scrollView = new ScrollView(context);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);

        if (info.channels.isEmpty()) {
            TextView emptyView = new TextView(context);
            emptyView.setText("В голосовых каналах сейчас никого нет 😴");
            emptyView.setTextSize(14f);
            emptyView.setPadding(0, 30, 0, 30);
            emptyView.setGravity(Gravity.CENTER_HORIZONTAL);
            content.addView(emptyView);
        } else {
            for (Channel ch : info.channels) {
                TextView chHeader = new TextView(context);
                chHeader.setText("🔊 " + ch.name + " (" + ch.members.size() + ")");
                chHeader.setTextSize(15f);
                chHeader.setTypeface(null, Typeface.BOLD);
                chHeader.setPadding(0, 14, 0, 4);
                content.addView(chHeader);

                for (Member m : ch.members) {
                    TextView memberView = new TextView(context);
                    String status = m.isMuted ? " 🔇" : "";
                    memberView.setText("   • " + m.username + status);
                    memberView.setTextSize(14f);
                    memberView.setPadding(0, 3, 0, 3);
                    content.addView(memberView);
                }
            }
        }

        scrollView.addView(content);
        root.addView(scrollView);

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle("⚡ " + info.name)
                .setView(root)
                .setPositiveButton("Скинуть в чат", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String text = buildShareText(info);
                        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("Discord Pulse", text));
                            Toast.makeText(context, "Скопировано в буфер! Вставьте в чат.", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNeutralButton("Настройки", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        showSettingsDialog(context, new Runnable() {
                            @Override
                            public void run() {
                                showMainDialog(context);
                            }
                        });
                    }
                })
                .setNegativeButton("Закрыть", null);

        builder.show();
    }

    private String buildShareText(GuildInfo info) {
        StringBuilder sb = new StringBuilder();
        sb.append("⚡ **Discord: ").append(info.name).append("**\n");
        if (info.channels.isEmpty()) {
            sb.append("В войсах сейчас никого нет.\n");
        } else {
            for (Channel ch : info.channels) {
                sb.append("🔊 *").append(ch.name).append(":* ");
                List<String> names = new ArrayList<String>();
                for (Member m : ch.members) names.add(m.username);
                sb.append(join(", ", names)).append("\n");
            }
        }
        if (info.instantInvite != null && !info.instantInvite.isEmpty()) {
            sb.append("\n🔗 Присоединиться: ").append(info.instantInvite);
        }
        return sb.toString();
    }

    private String join(String delimiter, List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(delimiter);
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private void showSettingsDialog(final Context context, final Runnable onSaved) {
        final SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 20, 50, 20);

        final EditText idInput = new EditText(context);
        idInput.setHint("ID Сервера Discord (Guild ID)");
        idInput.setText(prefs.getString(KEY_GUILD_ID, ""));
        layout.addView(idInput);

        new AlertDialog.Builder(context)
                .setTitle("Discord Pulse — Настройки")
                .setMessage("Введите ID сервера (виджет сервера должен быть включен в настройках Discord):")
                .setView(layout)
                .setPositiveButton("Сохранить", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String id = idInput.getText().toString().trim();
                        prefs.edit().putString(KEY_GUILD_ID, id).apply();
                        Toast.makeText(context, "Сохранено!", Toast.LENGTH_SHORT).show();
                        if (onSaved != null) onSaved.run();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private GuildInfo fetchGuildData(String guildId) {
        try {
            URL url = new URL("https://discord.com/api/guilds/" + guildId + "/widget.json");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);

            if (conn.getResponseCode() != 200) return null;

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            JSONObject json = new JSONObject(sb.toString());
            GuildInfo info = new GuildInfo();
            info.id = guildId;
            info.name = json.optString("name", "Discord Server");
            info.instantInvite = json.optString("instant_invite", null);
            info.presenceCount = json.optInt("presence_count", 0);

            Map<String, Channel> channelMap = new HashMap<String, Channel>();
            JSONArray channelsArr = json.optJSONArray("channels");
            if (channelsArr != null) {
                for (int i = 0; i < channelsArr.length(); i++) {
                    JSONObject c = channelsArr.getJSONObject(i);
                    Channel ch = new Channel();
                    ch.id = c.getString("id");
                    ch.name = c.getString("name");
                    ch.position = c.optInt("position", i);
                    channelMap.put(ch.id, ch);
                }
            }

            JSONArray membersArr = json.optJSONArray("members");
            if (membersArr != null) {
                for (int i = 0; i < membersArr.length(); i++) {
                    JSONObject m = membersArr.getJSONObject(i);
                    String chId = m.optString("channel_id", null);
                    if (chId == null || chId.equals("null")) continue;

                    Member member = new Member();
                    member.username = m.getString("username");
                    member.isMuted = m.optBoolean("mute", false) || m.optBoolean("self_mute", false);

                    Channel ch = channelMap.get(chId);
                    if (ch != null) {
                        ch.members.add(member);
                    } else {
                        Channel newCh = new Channel();
                        newCh.id = chId;
                        newCh.name = "Голосовой канал";
                        newCh.position = 999;
                        newCh.members.add(member);
                        channelMap.put(chId, newCh);
                    }
                }
            }

            for (Channel c : channelMap.values()) {
                if (!c.members.isEmpty()) {
                    info.channels.add(c);
                }
            }

            Collections.sort(info.channels, new Comparator<Channel>() {
                @Override
                public int compare(Channel o1, Channel o2) {
                    return Integer.compare(o1.position, o2.position);
                }
            });

            return info;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static class GuildInfo {
        String id;
        String name;
        String instantInvite;
        int presenceCount;
        List<Channel> channels = new ArrayList<Channel>();
    }

    private static class Channel {
        String id;
        String name;
        int position;
        List<Member> members = new ArrayList<Member>();
    }

    private static class Member {
        String username;
        boolean isMuted;
    }
}
