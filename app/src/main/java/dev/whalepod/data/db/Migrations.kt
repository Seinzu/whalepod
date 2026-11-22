package dev.whalepod.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 2 adds combined feeds: a `feeds` table (one row per RSS feed, each belonging to a podcast) and
 * `episodes.feedId` / `episodes.duplicateOfId`. Every existing podcast gets one feed — its own URL — and
 * its episodes are attached to it. Only additive changes are made, so no data is copied or dropped.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `feeds` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`podcastId` INTEGER NOT NULL, `url` TEXT NOT NULL, `title` TEXT, `author` TEXT, " +
                "`description` TEXT, `imageUrl` TEXT, `preferred` INTEGER NOT NULL, " +
                "`lastRefreshedAt` INTEGER NOT NULL, FOREIGN KEY(`podcastId`) REFERENCES `podcasts`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_feeds_url` ON `feeds` (`url`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_feeds_podcastId` ON `feeds` (`podcastId`)")
        db.execSQL(
            "INSERT INTO `feeds` (`podcastId`, `url`, `title`, `author`, `description`, `imageUrl`, " +
                "`preferred`, `lastRefreshedAt`) " +
                "SELECT `id`, `feedUrl`, `title`, `author`, `description`, `imageUrl`, 1, `lastRefreshedAt` " +
                "FROM `podcasts`",
        )

        db.execSQL("ALTER TABLE `episodes` ADD COLUMN `feedId` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `episodes` ADD COLUMN `duplicateOfId` INTEGER")
        db.execSQL(
            "UPDATE `episodes` SET `feedId` = " +
                "(SELECT `f`.`id` FROM `feeds` `f` WHERE `f`.`podcastId` = `episodes`.`podcastId`)",
        )
        db.execSQL("DROP INDEX IF EXISTS `index_episodes_podcastId_guid`")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_episodes_feedId_guid` ON `episodes` (`feedId`, `guid`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_podcastId` ON `episodes` (`podcastId`)")
    }
}
