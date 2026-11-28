package br.com.falai;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.Person;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.IconCompat;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public final class FalaiMessagingService extends FirebaseMessagingService {
    static final String CHANNEL_ID = "messages_alerts_v2";
    private static final String NUDGE_CHANNEL_ID = "attention_alerts_v1";
    private static final String LEGACY_CHANNEL_ID = "messages_icq_v1";
    private static final long[] VIBRATION = {0, 180, 100, 180};
    static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            NotificationChannel previous = manager.getNotificationChannel(LEGACY_CHANNEL_ID);
            int importance = previous == null ? NotificationManager.IMPORTANCE_HIGH : previous.getImportance();
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, context.getString(R.string.message_channel), importance);
            // Keep custom audio separate, but publish an actual haptic alert for wearable bridging.
            // A new channel is necessary: Android does not update an existing channel's behavior.
            channel.setSound(null, null);
            channel.enableVibration(true); channel.setVibrationPattern(VIBRATION);
            manager.createNotificationChannel(channel);
        }
    }
    @androidx.annotation.RequiresApi(26)
    private NotificationChannel nudgeChannel(NotificationManager manager, NotificationChannel messages) {
        NotificationChannel existing = manager.getNotificationChannel(NUDGE_CHANNEL_ID);
        if (existing != null) return existing;
        NotificationChannel channel = new NotificationChannel(NUDGE_CHANNEL_ID,
                getString(R.string.nudge_action), messages.getImportance());
        channel.setSound(null, null);
        if (messages.shouldVibrate()) {
            if (Build.VERSION.SDK_INT >= 35) channel.setVibrationEffect(NudgeFeedback.vibrationEffect());
            else channel.setVibrationPattern(NudgeFeedback.VIBRATION);
        }
        manager.createNotificationChannel(channel);
        return channel;
    }
    @Override public void onNewToken(@NonNull String token) {
        DeviceRegistrationWorker.schedule();
    }
    @Override public void onMessageReceived(@NonNull RemoteMessage message) {
        if (!Backend.ready() || Backend.uid().isEmpty()) return;
        if ("app-update".equals(message.getData().get("event"))) { UpdateNotice.accept(this, message.getData()); return; }
        String chatId = message.getData().get("chatId"), recipient = message.getData().get("recipientId");
        String messageId = message.getData().get("messageId");
        if (chatId == null || !chatId.matches("[a-f0-9]{64}") || !Backend.uid().equals(recipient)) return;
        if ("deleted".equals(message.getData().get("event"))) {
            if (messageId != null && messageId.matches("[a-f0-9-]{36}")) {
                android.content.SharedPreferences deleted = getSharedPreferences("deleted_notices", MODE_PRIVATE);
                android.content.SharedPreferences.Editor editor = deleted.edit(); if (deleted.getAll().size() >= 200) editor.clear();
                editor.putBoolean(messageId, true).apply(); NotificationManagerCompat.from(this).cancel(messageId, 0);
            }
            return;
        }
        if (messageId != null && getSharedPreferences("deleted_notices", MODE_PRIVATE).getBoolean(messageId, false)) return;
        if (messageId != null && messageId.matches("[a-f0-9-]{36}"))
            ReceiptWorker.schedule(chatId, java.util.Collections.singletonList(messageId), false);
        boolean directReply = recipient.equals(message.getData().get("replySenderId"));
        if ("true".equals(message.getData().get("silent")) || (ArchivedChats.contains(this, recipient, chatId) && !directReply)) return;
        long createdAt = message.getSentTime();
        try { createdAt = Long.parseLong(message.getData().get("createdAt")); } catch (RuntimeException ignored) { }
        long historyCutoff = HistoryRetention.cutoff(this);
        if (createdAt > 0 && createdAt <= historyCutoff) return;
        long messageExpiresAt = 0;
        try { messageExpiresAt = Long.parseLong(message.getData().get("messageExpiresAt")); } catch (RuntimeException ignored) { }
        if (messageExpiresAt > 0 && messageExpiresAt <= System.currentTimeMillis()) return;
        boolean nudge = "nudge".equals(message.getData().get("event"));
        if (chatId.equals(ChatActivity.visibleChat) && !nudge) return;
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        createChannel(this);
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return;
        boolean nudgeVibrates = true;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            NotificationChannel messages = manager.getNotificationChannel(CHANNEL_ID);
            if (messages.getImportance() == NotificationManager.IMPORTANCE_NONE) return;
            if (nudge) {
                NotificationChannel attention = nudgeChannel(manager, messages);
                if (attention.getImportance() == NotificationManager.IMPORTANCE_NONE) return;
                nudgeVibrates = attention.shouldVibrate();
            }
        }
        if (nudge && !NudgeFeedback.accept(this, messageId, message.getData().get("expiresAt"))) return;
        Intent intent = new Intent(this, MainActivity.class).setAction("br.com.falai.OPEN." + chatId).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("chatId", chatId).putExtra("recipientId", recipient);
        PendingIntent pending = PendingIntent.getActivity(this, chatId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String sender = message.getData().get("senderName"), text = message.getData().get("text");
        String senderId = message.getData().get("senderId");
        if (sender == null || sender.trim().isEmpty()) sender = getString(R.string.app_name);
        if (text == null || text.isEmpty()) text = getString(R.string.new_message);
        text = GifCatalog.preview(this, text);
        if (nudge) {
            text = getString(R.string.nudge_received); NudgeFeedback.shake(sender);
            if (chatId.equals(ChatActivity.visibleChat)) {
                if (nudgeVibrates) NudgeFeedback.vibrate(this);
                MessageSound.playNudge(this, recipient + ":" + messageId);
                return;
            }
        }
        String me = Backend.auth().getCurrentUser().getDisplayName();
        if (me == null || me.trim().isEmpty()) me = getString(R.string.app_name);
        Person recipientPerson = new Person.Builder().setName(me).setKey(recipient).build();
        android.graphics.Bitmap avatar = NotificationAvatar.bitmap(this, senderId, sender);
        Person senderPerson = new Person.Builder().setName(sender).setKey(NotificationAvatar.valid(senderId) ? senderId : chatId).setIcon(IconCompat.createWithBitmap(avatar)).build();
        long timestamp = createdAt > 0 ? createdAt : System.currentTimeMillis();
        android.os.Bundle historyMetadata = new android.os.Bundle(); historyMetadata.putLong("falai_createdAt", timestamp);
        historyMetadata.putLong("falai_expiresAt", messageExpiresAt);
        historyMetadata.putString("falai_chatId", chatId);
        NotificationCompat.MessagingStyle style = new NotificationCompat.MessagingStyle(recipientPerson)
                .setGroupConversation(false).addMessage(text, timestamp, senderPerson);
        NotificationCompat.Builder notification = new NotificationCompat.Builder(this, nudge ? NUDGE_CHANNEL_ID : CHANNEL_ID).setSmallIcon(R.drawable.ic_chat).setContentTitle(sender).setContentText(text)
                .setStyle(style).setLargeIcon(avatar).setWhen(timestamp).setCategory(NotificationCompat.CATEGORY_MESSAGE).addPerson(senderPerson)
                .addExtras(historyMetadata)
                .setTimeoutAfter(messageExpiresAt > 0 ? Math.max(1, messageExpiresAt - System.currentTimeMillis()) : 0)
                .setVibrate(nudge ? NudgeFeedback.VIBRATION : VIBRATION)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE);
        String tag = messageId != null && messageId.matches("[a-f0-9-]{36}") ? messageId : chatId;
        notification.addAction(NotificationReplyReceiver.action(this, recipient, chatId, tag));
        NotificationManagerCompat.from(this).notify(tag, 0, notification.build());
        if (nudge) MessageSound.playNudge(this, recipient + ":" + tag); else MessageSound.play(this, recipient + ":" + tag);
        if (NotificationAvatar.refresh(senderId) && recipient.equals(Backend.uid())
                && !getSharedPreferences("deleted_notices", MODE_PRIVATE).getBoolean(tag, false)
                && !chatId.equals(ChatActivity.visibleChat) && notificationStillVisible(tag)) {
            android.graphics.Bitmap updatedAvatar = NotificationAvatar.bitmap(this, senderId, sender);
            Person updatedSender = new Person.Builder().setName(sender).setKey(senderId).setIcon(IconCompat.createWithBitmap(updatedAvatar)).build();
            NotificationCompat.MessagingStyle updatedStyle = new NotificationCompat.MessagingStyle(recipientPerson)
                    .setGroupConversation(false).addMessage(text, timestamp, updatedSender);
            // Update only the existing card; do not replay audio or resurrect a dismissed notice.
            notification.setStyle(updatedStyle).setLargeIcon(updatedAvatar);
            NotificationManagerCompat.from(this).notify(tag, 0, notification.build());
        }
    }
    private boolean notificationStillVisible(String tag) {
        for (android.service.notification.StatusBarNotification notice : getSystemService(NotificationManager.class).getActiveNotifications())
            if (notice.getId() == 0 && tag.equals(notice.getTag()) && !notice.getNotification().extras.getBoolean("falai_reply_status")) return true;
        return false;
    }
}
