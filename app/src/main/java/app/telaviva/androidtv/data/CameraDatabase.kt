package app.telaviva.androidtv.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class CameraDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE cameras (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                host TEXT NOT NULL,
                main_rtsp_uri TEXT NOT NULL,
                sub_rtsp_uri TEXT,
                credentials_ciphertext BLOB NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException("No camera database migration from $oldVersion to $newVersion")
    }

    companion object {
        const val DATABASE_NAME = "camera_profiles.db"
        private const val DATABASE_VERSION = 1
    }
}
