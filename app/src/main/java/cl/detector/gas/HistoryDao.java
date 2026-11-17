package cl.detector.gas;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Transaction;
import java.util.List;

@Dao
public interface HistoryDao {
    @Insert
    void insert(HistoryRow row);

    @Query("SELECT * FROM readings WHERE owner = :owner ORDER BY id DESC LIMIT 200")
    List<HistoryRow> latest(String owner);

    @Query("SELECT * FROM readings WHERE owner = :owner AND uploaded = 0 ORDER BY id LIMIT 50")
    List<HistoryRow> pending(String owner);

    @Query("UPDATE readings SET uploaded = 1 WHERE owner = :owner AND id IN (:ids)")
    void markUploaded(String owner, List<Long> ids);

    @Query("SELECT COUNT(*) FROM readings WHERE owner = :owner AND uploaded = :uploaded")
    int count(String owner, boolean uploaded);

    @Transaction
    default CloudCounts cloudCounts(String owner) {
        return new CloudCounts(count(owner, false), count(owner, true));
    }
}
