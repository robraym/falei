package br.com.falai;

import android.content.Context;
import android.content.Intent;
import android.app.PendingIntent;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.*;
import com.google.android.gms.tasks.Tasks;
import java.util.concurrent.TimeUnit;

/** Same UUID on retry prevents duplicate messages after an uncertain network response. */
public final class NotificationReplyWorker extends Worker {
    public NotificationReplyWorker(@NonNull Context context, @NonNull WorkerParameters parameters) { super(context, parameters); }
    @NonNull @Override public Result doWork() {
        String owner = getInputData().getString("owner"), chat = getInputData().getString("chat"), tag = getInputData().getString("tag");
        if (!Backend.ready() || !Backend.uid().equals(owner)) return Result.success();
        try {
            Tasks.await(Backend.call("sendMessage", Backend.data("chatId", chat, "messageId", getInputData().getString("id"),
                    "text", getInputData().getString("text"))), 60, TimeUnit.SECONDS);
            if (Backend.uid().equals(owner)) show(getApplicationContext(), chat, tag, R.string.notification_reply_sent);
            return Result.success();
        } catch (Exception error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            if (cause instanceof Backend.ApiException) {
                String code = ((Backend.ApiException) cause).code;
                if ("permission-denied".equals(code) || "invalid-argument".equals(code) || "not-found".equals(code) || "already-exists".equals(code)) {
                    show(getApplicationContext(), chat, tag, R.string.notification_reply_failed); return Result.failure();
                }
            }
            return Result.retry();
        }
    }
    static void show(Context context, String chat, String tag, int message) {
        Intent intent = new Intent(context, MainActivity.class).setAction("br.com.falai.OPEN." + chat)
                .putExtra("chatId", chat).putExtra("recipientId", Backend.uid());
        PendingIntent open = PendingIntent.getActivity(context, chat.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder notice = new NotificationCompat.Builder(context, FalaiMessagingService.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_chat).setContentTitle(context.getString(R.string.app_name)).setContentText(context.getString(message))
                .setContentIntent(open).setOnlyAlertOnce(true).setSilent(true).setAutoCancel(true);
        android.os.Bundle metadata = new android.os.Bundle(); metadata.putBoolean("falai_reply_status", true); notice.addExtras(metadata);
        try { NotificationManagerCompat.from(context).notify(tag, 0, notice.build()); } catch (SecurityException ignored) { }
    }
}
