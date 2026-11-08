package com.nexachat.core.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.List;

/**
 * Access to persisted users for contacts and presence.
 */
@Dao
public interface UserDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    Completable insertOrUpdate(UserEntity user);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    Completable insertOrUpdateAll(List<UserEntity> users);

    @Query("SELECT * FROM users WHERE id = :id")
    Maybe<UserEntity> findById(String id);

    @Query("SELECT * FROM users WHERE id = :id")
    Flowable<UserEntity> observeById(String id);

    @Query("SELECT * FROM users ORDER BY displayName ASC")
    Flowable<List<UserEntity>> getAllUsers();

    @Query("SELECT * FROM users ORDER BY displayName ASC")
    Single<List<UserEntity>> getAllUsersList();

    @Query("DELETE FROM users WHERE id = :id")
    Completable deleteById(String id);

    @Query("DELETE FROM users")
    Completable clear();
}
