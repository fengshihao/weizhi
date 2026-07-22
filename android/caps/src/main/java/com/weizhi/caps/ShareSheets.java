package com.weizhi.caps;

import android.content.Context;
import android.content.Intent;

final class ShareSheets {
    private ShareSheets() {
    }

    static void launch(Context context, String title, String text) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, title == null ? "" : title);
        send.putExtra(Intent.EXTRA_TEXT, text == null ? "" : text);
        send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(Intent.createChooser(send, title == null || title.isEmpty() ? "分享" : title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
