package tw.mayortw.dropup;

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
        this.limit = limit * 1024; // turn kb into bytes
    }

    public void setRate(int limit) {
        this.limit = limit;
    }

    public int getRate(int limit) {
        return limit;
    }

    @Override
    public void write(int b) throws IOException {
        while(!tryWrite(b)) Thread.yield();
    }

    /*
     * returns true when the byte is written
     */
    private boolean tryWrite(int b) throws IOException {
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
