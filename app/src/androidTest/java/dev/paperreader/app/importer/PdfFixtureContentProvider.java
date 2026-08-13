package dev.paperreader.app.importer;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class PdfFixtureContentProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "application/pdf";
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder
    ) {
        byte[] bytes = bytesFor(uri);
        String[] columns = projection != null
                ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        Object[] values = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[index])) {
                values[index] = fixtureName(uri) + ".pdf";
            } else if (OpenableColumns.SIZE.equals(columns[index])) {
                values[index] = (long) bytes.length;
            }
        }
        MatrixCursor cursor = new MatrixCursor(columns);
        cursor.addRow(values);
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new IllegalArgumentException("Read-only fixture");
        }
        if ("runtime-open".equals(fixtureName(uri))) {
            throw new IllegalArgumentException("Hostile provider runtime failure");
        }
        File fixture = new File(requireContextCacheDir(), fixtureName(uri) + ".pdf");
        try (FileOutputStream output = new FileOutputStream(fixture, false)) {
            output.write(bytesFor(uri));
            output.getFD().sync();
        } catch (IOException error) {
            FileNotFoundException failure = new FileNotFoundException("Fixture could not be written");
            failure.initCause(error);
            throw failure;
        }
        return ParcelFileDescriptor.open(fixture, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    private byte[] bytesFor(Uri uri) {
        return ("%PDF-1.7\n" + fixtureName(uri) + " durable fixture")
                .getBytes(StandardCharsets.US_ASCII);
    }

    private String fixtureName(Uri uri) {
        String value = uri.getLastPathSegment();
        if (value == null || !value.matches("[a-z0-9-]{1,40}")) {
            throw new IllegalArgumentException("Unknown fixture");
        }
        return value;
    }

    private File requireContextCacheDir() {
        if (getContext() == null) {
            throw new IllegalStateException("Provider context unavailable");
        }
        return getContext().getCacheDir();
    }
}
