package cl.detector.gas;

import androidx.room.Database;
import androidx.room.RoomDatabase;

@Database(entities = {HistoryRow.class}, version = 1, exportSchema = false)
public abstract class HistoryDatabase extends RoomDatabase {
    public abstract HistoryDao history();
}
