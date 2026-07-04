package com.trainsystemutilities.util;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * デコード byte 数の上限付き {@link InputStream} wrapper。
 *
 * <p>用途: gzip / deflate decoder を {@code TrainPresetImportPayload} 等で受ける際、
 * 圧縮後 byte 数だけでは zip bomb (= 高圧縮率で展開後巨大) を防げない。 本 wrapper を
 * 解凍ストリームの外側に被せることで、 decompressed byte が上限超過した時点で即座に
 * {@link IOException} を throw する。
 *
 * <p>P0-4 #12 (WF-E TrainPresetImport zip bomb 対策)。
 */
public final class BoundedInputStream extends FilterInputStream {

    private final long maxBytes;
    private long read = 0;

    public BoundedInputStream(InputStream in, long maxBytes) {
        super(in);
        if (maxBytes < 0) throw new IllegalArgumentException("maxBytes < 0");
        this.maxBytes = maxBytes;
    }

    @Override
    public int read() throws IOException {
        int b = in.read();
        if (b >= 0) {
            read++;
            check();
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = in.read(b, off, len);
        if (n > 0) {
            read += n;
            check();
        }
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        long s = in.skip(n);
        read += s;
        check();
        return s;
    }

    private void check() throws IOException {
        if (read > maxBytes) {
            throw new IOException("decompressed stream exceeded " + maxBytes + " bytes (= zip bomb 防御)");
        }
    }
}
