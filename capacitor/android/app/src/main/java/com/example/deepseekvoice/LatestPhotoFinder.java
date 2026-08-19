package com.example.deepseekvoice;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

/**
 * 智能眼镜图片输入：从系统相册取"最新一张照片"作为输入。
 * 优先匹配智能眼镜/相机相关相册（bucket 名含 眼镜/glass/meta 等），
 * 匹配不到则退化为全相册最新一张（眼镜拍照通常同步到相册后即为最新）。
 */
public class LatestPhotoFinder {

    private static final String[] PROJECTION = {
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
    };

    // 常见智能眼镜拍照相册关键字（不分大小写）
    private static final String[] GLASS_BUCKET_KEYWORDS = {
            "眼镜", "glass", "meta", "smart glasses", "camera"
    };

    /** 返回最新图片 Uri；无图片返回 null。 */
    public static Uri findLatest(Context context) {
        // 1) 先尝试智能眼镜相册
        Uri uri = queryFirst(context, buildGlassSelection(), buildGlassArgs());
        if (uri != null) return uri;
        // 2) 退化为全相册最新一张
        return queryFirst(context, null, null);
    }

    private static String buildGlassSelection() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < GLASS_BUCKET_KEYWORDS.length; i++) {
            if (i > 0) sb.append(" OR ");
            sb.append(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
              .append(" LIKE ? COLLATE NOCASE");
        }
        return sb.toString();
    }

    private static String[] buildGlassArgs() {
        String[] args = new String[GLASS_BUCKET_KEYWORDS.length];
        for (int i = 0; i < GLASS_BUCKET_KEYWORDS.length; i++) {
            args[i] = "%" + GLASS_BUCKET_KEYWORDS[i] + "%";
        }
        return args;
    }

    /** 按条件查最新一张图片（DATE_ADDED 降序）。 */
    private static Uri queryFirst(Context context, String selection, String[] args) {
        try (Cursor c = context.getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                PROJECTION, selection, args,
                MediaStore.Images.Media.DATE_ADDED + " DESC LIMIT 1")) {
            if (c != null && c.moveToFirst()) {
                long id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media._ID));
                return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // 私有工具类不实例化
    private LatestPhotoFinder() {
    }
}
