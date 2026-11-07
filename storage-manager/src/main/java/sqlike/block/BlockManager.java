package sqlike.block;

import java.nio.file.Path;
import java.util.Map;
import java.nio.channels.FileChannel;

public class BlockManager{
    private Path dataDirectory;
    private int pageSize;
    private Map<Integer, String> openFiles;


    public BlockManager(Path dataDirectory, int pageSize) {
        this.dataDirectory = dataDirectory;
        this.pageSize = pageSize;
        this.openFiles = new java.util.HashMap<>();
    }


    // SM-09 reads the configured page size; no disk behavior is changed.
    public int getPageSize() {
        return pageSize;
    }
}
