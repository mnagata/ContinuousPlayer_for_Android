package jp.nagu.continuousplayer;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.FileNotFoundException;
import java.util.Locale;

/** Runs in the test APK's own process using only framework classes. */
public class ScrollFixtureProvider extends DocumentsProvider {
    public static final Uri tree = DocumentsContract.buildTreeDocumentUri("jp.nagu.continuousplayer.test.scroll", "root");
    private static final String[] COLUMNS = {"document_id", "_display_name", "mime_type", "_size", "last_modified"};

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

    @Override public Cursor queryDocument(String id, String[] projection) {
        MatrixCursor cursor = new MatrixCursor(COLUMNS);
        cursor.addRow(row(id));
        return cursor;
    }

    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(COLUMNS);
        if (parent.equals("root")) {
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
        if (id.equals("d25-denied")) throw new SecurityException("D25 provider denied read access");
        throw new FileNotFoundException("The test selects a file without decoding it");
    }

    private Object[] row(String id) {
        boolean focusMedia = id.equals("focus-fixture/media-one") || id.equals("focus-fixture/media-two");
        String title = focusMedia ? "Duplicate.mp4" : id.equals("root") ? "Scroll fixture" : id.equals("media") ? "Sample.mp4" :
            id.equals("media-two") ? "Other Sample.mp4" : id.endsWith("/nested") ? "Nested folder" :
            (id.contains("/collection-") ? "Collection " : "Season ") + id.substring(id.lastIndexOf('-') + 1);
        return new Object[]{id, title, (focusMedia || id.equals("media") || id.equals("media-two")) ?
            "video/mp4" : DocumentsContract.Document.MIME_TYPE_DIR, 1L, 0L};
    }
}
