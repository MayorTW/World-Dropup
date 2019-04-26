package tw.mayortw.dropup.util;
/*
 * Written by R26
 */

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/*
 * InputStream with limited transfer limit
 */
public class LimitedInputStream extends FilterInputStream {

    private int limit;
    private long interval = 1000; // in millis

    private double available = 0;
    private long lastRead = 0;

    /*
     * limit here is in kb/s
     */
    public LimitedInputStream(InputStream out, int limit) {
        super(out);
        this.limit = limit * 1024; // turn kb into bytes
    }

    /*
     * if the limit is negative then it means no limit
     */
    public synchronized void setRate(int rate) {
        this.limit = rate;
    }

    public int getRate() {
        return limit;
    }

    @Override
    public synchronized int read() throws IOException {
        while(true) {
            int b = tryRead();
            if(b >= -1) return b;
            try {
                Thread.sleep(10);
            } catch(InterruptedException e) {}
        }
    }

    /*
     * returns -2 if read should be waiting
     */
    private int tryRead() throws IOException {
        if(limit < 0) {
            return super.read(); // negative limit = no limit
        }

        long now = System.currentTimeMillis();

        available += (double) (now - lastRead) / interval * limit;
        if(available > limit) available = limit;

        if(available >= 1) {
            int b = super.read();
            available--;
            lastRead = now;
            return b;
        }

        return -2;
    }
}
