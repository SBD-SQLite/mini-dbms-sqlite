package sqlike.shared;

import java.util.ArrayList;
import java.util.List
;
public class ColumnSchema {
    String name;
    // DataType
    int maxLength;
    List<String> constraints;

    public ColumnSchema(String name, int maxLength){
        this.name = name;
        this.maxLength = maxLength;
        this.constraints = new ArrayList<>();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getMaxLength() {
        return maxLength;
    }

    public void setMaxLength(int maxLength) {
        this.maxLength = maxLength;
    }

    public List<String> getConstraints() {
        return constraints;
    }

    public void setConstraints(List<String> constraints) {
        this.constraints = constraints;
    }
}
