package cl.detector.gas;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

@Dao
public interface HistoryDao {
    @Insert
    void insert(HistoryRow row);

    @Query("SELECT * FROM readings WHERE owner = :owner ORDER BY id DESC LIMIT 200")
    List<HistoryRow> latest(String owner);
}
