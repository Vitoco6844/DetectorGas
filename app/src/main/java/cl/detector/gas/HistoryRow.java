package cl.detector.gas;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "readings", indices = @Index(value = {"owner", "id"}))
public class HistoryRow {
    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull public String owner;
    @NonNull public byte[] payload;
    @NonNull public byte[] nonce;

    public HistoryRow(@NonNull String owner, @NonNull byte[] payload, @NonNull byte[] nonce) {
        this.owner = owner;
        this.payload = payload;
        this.nonce = nonce;
    }
}
