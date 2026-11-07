package sqlike.index;

import java.util.List;

import sqlike.shared.RID;
import sqlike.shared.Value;
import sqlike.storage.StorageException;

public interface Index {
    public List<RID> search(Value key) throws StorageException;
    public void insert(Value key, RID rid, long lsn) throws StorageException;
    public void remove(Value key, RID rid, long lsn) throws StorageException;
    public void flush() throws StorageException;

}
