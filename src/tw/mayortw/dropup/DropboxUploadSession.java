package tw.mayortw.dropup;
/*
 * Written by R26
 */

import java.io.OutputStream;

import org.bukkit.Bukkit;

import com.dropbox.core.DbxException;
import com.dropbox.core.DbxUploader;
import com.dropbox.core.RetryException;
import com.dropbox.core.v2.DbxClientV2;
import com.dropbox.core.v2.files.CommitInfo;
import com.dropbox.core.v2.files.FileMetadata;
import com.dropbox.core.v2.files.UploadSessionCursor;
import com.dropbox.core.v2.files.UploadSessionStartUploader;

public class DropboxUploadSession {

    public OutputStream out;
    public String sessionId;

    private DbxClientV2 dbxClient;
    private DbxUploader uploader;

    public DropboxUploadSession(DbxClientV2 dbxClient) throws DbxException {
        this.dbxClient = dbxClient;

        doRetry(() -> {
            UploadSessionStartUploader uploader = dbxClient.files().uploadSessionStart();
            sessionId = uploader.finish().getSessionId();
        });

        nextSession(0);
    }

    public void nextSession(long offset) throws DbxException {
        if(uploader != null)
            uploader.finish();

        doRetry(() -> {
            uploader = dbxClient.files().uploadSessionAppendV2(new UploadSessionCursor(sessionId, offset));
            out = uploader.getOutputStream();
        });
    }

    public FileMetadata finishSession(String path, long totalSize) throws DbxException {
        if(uploader != null)
            uploader.finish();

        doRetry(() -> {
            uploader = dbxClient.files().uploadSessionFinish(new UploadSessionCursor(sessionId, totalSize), new CommitInfo(path));
            out = null;
        });

        return (FileMetadata) uploader.finish();
    }

    private void doRetry(DbxRunnable cb) throws DbxException {
        final int MAX_RETRIES = 3;

        int retryCount = 0;
        while(true) {
            try {
                cb.run();
                break;
            } catch(RetryException e) {
                if(retryCount < MAX_RETRIES) {
                    Bukkit.getLogger().warning("Dropbox error. Retrying: " + e.getMessage());
                    retryCount++;
                    long wait = e.getBackoffMillis();
                    if(wait > 0) {
                        try {
                            Thread.sleep(e.getBackoffMillis());
                        } catch(InterruptedException exc) {}
                    }
                } else {
                    throw e;
                }
            }
        }
    }

    /*
     * Just a runnable that throws DbxException
     */
    private static interface DbxRunnable {
        public void run() throws DbxException;
    }
}
