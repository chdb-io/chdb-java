package org.chdb.jdbc.memory;

import org.apache.arrow.memory.RootAllocator;

public class ArrowMemoryManger {
    public static RootAllocator ROOT_ALLOCATOR = new RootAllocator();
}
