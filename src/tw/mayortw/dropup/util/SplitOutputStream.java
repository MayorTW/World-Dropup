package tw.mayortw.dropup.util;
/*
 * Written by R26
 */

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/*
 * An OutputStream that splits the data to multiple underlying OutputStream
 * It gets a new OutputStream from callback when it has written a
 * certain amount of data to the previous one
 */
public class SplitOutputStream extends FilterOutputStream {

    private int written;
    private int chunkSize;
    private Callback chunkCb;

    // chunkSize is in kb
    public SplitOutputStream(OutputStream out, int chunkSize, Callback chunkCb) {
        super(out);
        this.chunkSize = chunkSize * 1024;
        this.chunkCb = chunkCb;
    }

    public int getWrittenBytes() {
        return written;
    }

    @Override
    public void write(int b) throws IOException {
        if(written >= chunkSize) {
            out.close();
            out = chunkCb.next(written);
            written = 0;
        }

        super.write(b);
        written++;
    }

    public static interface Callback {
        public OutputStream next(int offset);
    }
}
