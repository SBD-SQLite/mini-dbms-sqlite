package sqlike.buffer;

import sqlike.block.Page;

public class BufferFrame{
    private Page page;
    private boolean dirty;
    private int pinCount;

    public BufferFrame(Page p){
        this.page = p;
        this.pinCount = 1;
    }

    public void pin(){
        this.pinCount++;
    }

    public void unpin(){
        this.pinCount--;
    }

    public Page getPage(){
        return page;
    }

    public int getPin(){
        return pinCount;
    }

    public boolean getDirty(){
        return dirty;
    }

    public void setDirty(boolean dirty){
        this.dirty = dirty;
    }

}
