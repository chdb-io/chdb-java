package org.chdb.arrow;

import java.io.IOException;
import java.sql.SQLException;
import org.apache.arrow.c.ArrowArrayStream;
import org.apache.arrow.vector.ipc.ArrowReader;

/**
 * A borrowed native registration of a one-shot Arrow reader. Close it before its allocator.
 * A close during a materialized query waits for the session's query to finish; it cannot
 * unregister and release the native stream while that query is scanning it.
 */
public final class RegisteredArrowSource implements AutoCloseable {
    private final ArrowSession session;
    private final ArrowReader reader;
    private final ArrowArrayStream stream;
    private final String name;
    private boolean closed;

    RegisteredArrowSource(ArrowSession session, ArrowReader reader, ArrowArrayStream stream, String name) {
        this.session = session;
        this.reader = reader;
        this.stream = stream;
        this.name = name;
    }

    String name() {
        return name;
    }

    /** SQL fragment for a scan, e.g. {@code FROM arrowstream('...')}. */
    public String fromSql() {
        synchronized (session) {
            if (closed) {
                throw new IllegalStateException("Arrow source is closed");
            }
            return "arrowstream('" + name + "')";
        }
    }

    void releaseOwnedResources() throws IOException {
        closed = true;
        try {
            // Core borrows the stream until unregister. ArrowArrayStream.close() only
            // frees the struct; release() invokes the exported reader's C callback.
            stream.release();
        } finally {
            try {
                stream.close();
            } finally {
                reader.close();
            }
        }
    }

    @Override
    public void close() throws IOException, SQLException {
        session.unregister(this);
    }
}
