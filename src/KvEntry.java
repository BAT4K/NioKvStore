import java.io.Serializable;

public class KvEntry implements Serializable {
    public String value;
    public long expiryAt; // Timestamp in milliseconds. -1 means no expiry.

    public KvEntry(String value) {
        this.value = value;
        this.expiryAt = -1;
    }
}