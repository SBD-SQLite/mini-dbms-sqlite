package sqlike.block

import sqlike.shared.PageId;

public class Page{
    private PageId pageId;
    private long pageLSN;
    private byte data[];

    public Page(PageId pageId, byte[] data) {
        this.pageId = pageId;
        this.data = data;
    }

    public PageId getPageId() { return pageId; }
    public byte[] getData()   { return data; }
    public long getPageLSN()  { return pageLSN; }
    public void setPageLSN(long lsn) { this.pageLSN = lsn; }
}
