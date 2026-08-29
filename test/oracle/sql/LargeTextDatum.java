package oracle.sql;

public final class LargeTextDatum {
    private final String value;

    public LargeTextDatum(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
