package br.com.falai;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;
import androidx.work.*;
import java.util.UUID;

/** Receives text from Android/Wear OS, then persists delivery before leaving the receiver. */
public final class NotificationReplyReceiver extends BroadcastReceiver {
    private static final String KEY = "reply_text";
    static NotificationCompat.Action action(Context context, String owner, String chat, String tag) {
        Intent reply = new Intent(context, NotificationReplyReceiver.class)
                .setData(new Uri.Builder().scheme("falai").authority("reply").appendPath(owner).appendPath(chat).appendPath(tag).build())
                .putExtra("owner", owner).putExtra("chat", chat).putExtra("tag", tag);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pending = PendingIntent.getBroadcast(context, 0, reply, flags);
        return new NotificationCompat.Action.Builder(R.drawable.ic_send, context.getString(R.string.reply_message), pending)
                .addRemoteInput(new RemoteInput.Builder(KEY).setLabel(context.getString(R.string.reply_message)).build())
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).setAllowGeneratedReplies(true)
                .setShowsUserInterface(false).build();
    }
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !Backend.ready()) return;
        String owner = intent.getStringExtra("owner"), chat = intent.getStringExtra("chat"), tag = intent.getStringExtra("tag");
        android.os.Bundle results = RemoteInput.getResultsFromIntent(intent);
        CharSequence value = results == null ? null : results.getCharSequence(KEY);
        if (!Backend.uid().equals(owner) || owner == null || owner.isEmpty() || chat == null || !chat.matches("[a-f0-9]{64}") || tag == null || value == null) return;
        String text = value.toString().trim();
        if (text.isEmpty()) return;
        if (text.length() > 4000 || text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 7000) {
            NotificationReplyWorker.show(context, chat, tag, R.string.message_too_long); return;
        }
        String id = UUID.randomUUID().toString();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(NotificationReplyWorker.class)
                .setInputData(new Data.Builder().putString("owner", owner).putString("chat", chat).putString("tag", tag)
                        .putString("text", text).putString("id", id).build())
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        PendingResult result = goAsync();
        NotificationReplyWorker.show(context, chat, tag, R.string.notification_reply_queued);
        androidx.work.Operation operation = WorkManager.getInstance(context).enqueueUniqueWork("notification-reply-" + id, ExistingWorkPolicy.KEEP, request);
        operation.getResult().addListener(() -> {
            try { operation.getResult().get(); }
            catch (Exception error) { NotificationReplyWorker.show(context, chat, tag, R.string.notification_reply_failed); }
            finally { result.finish(); }
        }, ContextCompatExecutor.INSTANCE);
    }
    private static final class ContextCompatExecutor {
        static final java.util.concurrent.Executor INSTANCE = java.util.concurrent.Executors.newSingleThreadExecutor();
    }
}
