package jp.nagu.continuousplayer;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.Bundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.FileNotFoundException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Locale;

/** Runs in the test APK's own process using only framework classes. */
public class ScrollFixtureProvider extends DocumentsProvider {
    public static final Uri tree = DocumentsContract.buildTreeDocumentUri("jp.nagu.continuousplayer.test.scroll", "root");
    private static final String[] COLUMNS = {"document_id", "_display_name", "mime_type", "_size", "last_modified"};

    private final AtomicInteger d24Opens = new AtomicInteger();
    private volatile String blockedFolder;
    private volatile boolean failAfterRelease;
    private volatile boolean queryStarted;
    private volatile CountDownLatch queryRelease = new CountDownLatch(0);

    @Override public Bundle call(String method, String arg, Bundle extras) {
        Bundle result = new Bundle();
        if (method.equals("d24-count")) {
            result.putInt("opens", d24Opens.get());
        } else if (method.equals("d24-reset")) {
            d24Opens.set(0);
        } else if (method.equals("d24-clean")) {
            new File(getContext().getFilesDir(), "d24-media.wav").delete();
            d24Opens.set(0);
        } else if (method.equals("d22-prepare")) {
            queryRelease.countDown();
            queryStarted = false;
            failAfterRelease = extras != null && extras.getBoolean("fail");
            queryRelease = new CountDownLatch(1);
            blockedFolder = arg;
        } else if (method.equals("d22-release")) {
            blockedFolder = null;
            queryRelease.countDown();
        } else if (method.equals("d22-state")) {
            result.putBoolean("started", queryStarted);
        } else return super.call(method, arg, extras);
        return result;
    }

    @Override public boolean onCreate() { return true; }

    @Override public boolean isChildDocument(String parent, String child) {
        return parent.equals("root") || child.startsWith(parent + "/") ||
            ((child.equals("media") || child.equals("media-two")) && parent.contains("/collection-"));
    }

    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(new String[]{"root_id", "document_id", "title"});
        cursor.addRow(new Object[]{"root", "root", "Scroll fixture"});
        return cursor;
    }

    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        if (id.equals("d19-offline")) throw new FileNotFoundException("D19 offline test storage");
        MatrixCursor cursor = new MatrixCursor(COLUMNS);
        cursor.addRow(row(id));
        return cursor;
    }

    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(COLUMNS);
        if (parent.equals(blockedFolder)) {
            CountDownLatch release = queryRelease;
            boolean fail = failAfterRelease;
            queryStarted = true;
            try {
                if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("D22 query gate timed out");
            } catch (InterruptedException error) { Thread.currentThread().interrupt();throw new IllegalStateException(error); }
            if (fail) throw new IllegalStateException("D22 late provider error");
        }
        if (parent.startsWith("d22-source")) {
            if (parent.equals("d22-source")) for (int i = 0; i < 40; i++) {
                cursor.addRow(new Object[]{parent + String.format(Locale.ROOT, "/child-%02d", i),
                    String.format(Locale.ROOT, "Child %02d", i), DocumentsContract.Document.MIME_TYPE_DIR, 0L, 0L});
            }
            cursor.addRow(new Object[]{parent + "/media", "Sample OP.mp4", "video/mp4", 1L, 0L});
        } else if (parent.equals("d21-format")) {
            cursor.addRow(new Object[]{"d21-format/mp4", "Episode.part.1.mp4", "video/mp4", 1L, 0L});
            cursor.addRow(new Object[]{"d21-format/m4v", "Episode.part.1.m4v", "video/mp4", 1L, 0L});
        } else if (parent.equals("root")) {
            for (int i = 0; i < 40; i++) cursor.addRow(row(String.format(Locale.ROOT, "season-%02d", i)));
        } else if (parent.equals("focus-fixture")) {
            for (int i = 0; i < 40; i++) cursor.addRow(row(String.format(Locale.ROOT, "focus-fixture/folder-%02d", i)));
            cursor.addRow(row("focus-fixture/media-one"));
            cursor.addRow(row("focus-fixture/media-two"));
        } else if (parent.contains("/collection-")) {
            if (parent.endsWith("/nested")) return cursor;
            cursor.addRow(row(parent + "/nested"));
            cursor.addRow(row("media"));
            cursor.addRow(row("media-two"));
        } else if (parent.startsWith("season-")) {
            for (int i = 0; i < 40; i++) cursor.addRow(row(parent + String.format(Locale.ROOT, "/collection-%02d", i)));
        }
        return cursor;
    }

    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (id.equals("d24-audio/media")) {
            d24Opens.incrementAndGet();
            File file = new File(getContext().getFilesDir(), "d24-media.wav");
            if (!file.exists()) {
                byte[] data = new byte[8000 * 2 * 90];
                ByteBuffer wave = ByteBuffer.allocate(data.length + 44).order(ByteOrder.LITTLE_ENDIAN);
                wave.put("RIFF".getBytes()).putInt(data.length + 36).put("WAVEfmt ".getBytes()).putInt(16)
                    .putShort((short)1).putShort((short)1).putInt(8000).putInt(16000).putShort((short)2).putShort((short)16)
                    .put("data".getBytes()).putInt(data.length).put(data);
                try (FileOutputStream output = new FileOutputStream(file)) { output.write(wave.array()); }
                catch (IOException error) { throw new FileNotFoundException(error.toString()); }
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        if (id.equals("d25-denied")) throw new SecurityException("D25 provider denied read access");
        throw new FileNotFoundException("The test selects a file without decoding it");
    }

    private Object[] row(String id) {
        if (id.equals("d24-audio/media")) return new Object[]{id, "D24 provider.wav", "audio/wav", 1440044L, 0L};
        if (id.equals("d24-audio")) return new Object[]{id, "D24 SAF", DocumentsContract.Document.MIME_TYPE_DIR, 0L, 0L};
        boolean focusMedia = id.equals("focus-fixture/media-one") || id.equals("focus-fixture/media-two");
        String title = focusMedia ? "Duplicate.mp4" : id.equals("root") ? "Scroll fixture" : id.equals("media") ? "Sample.mp4" :
            id.equals("media-two") ? "Other Sample.mp4" : id.endsWith("/nested") ? "Nested folder" :
            (id.contains("/collection-") ? "Collection " : "Season ") + id.substring(id.lastIndexOf('-') + 1);
        return new Object[]{id, title, (focusMedia || id.equals("media") || id.equals("media-two")) ?
            "video/mp4" : DocumentsContract.Document.MIME_TYPE_DIR, 1L, 0L};
    }
}
