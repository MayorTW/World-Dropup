package tw.mayortw.dropup.util;
/*
 * Written by R26
 */

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/*
 * OutputStream with limited transfer limit
 */
public class LimitedOutputStream extends FilterOutputStream {

    private int limit;
    private long interval = 1000; // in millis

    private double available = 0;
    private long lastSend = 0;

    /*
     * limit here is in kb/s
     */
    public LimitedOutputStream(OutputStream out, int limit) {
        super(out);
        this.limit = limit;
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
    public synchronized void write(int b) throws IOException {
        while(!tryWrite(b)) {
            try {
                Thread.sleep(10);
            } catch(InterruptedException e) {}
        }
    }

    /*
     * returns true when the byte is written
     */
    private boolean tryWrite(int b) throws IOException {
        if(limit < 0) {
            super.write(b); // negative limit = no limit
            return true;
        }

        long now = System.currentTimeMillis();

        available += (double) (now - lastSend) / interval * limit;
        if(available > limit) available = limit;

        if(available >= 1) {
            super.write(b);
            available--;
            lastSend = now;
            return true;
        }

        return false;
    }
}
