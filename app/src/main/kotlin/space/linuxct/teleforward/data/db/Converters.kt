package space.linuxct.teleforward.data.db

import androidx.room.TypeConverter
import space.linuxct.teleforward.data.db.entity.OutboxImageKind
import space.linuxct.teleforward.data.db.entity.OutboxStatus
import space.linuxct.teleforward.domain.RuleMode

/**
 * Room type converters for the enums stored as their [Enum.name] strings.
 */
class Converters {

    @TypeConverter
    fun ruleModeToString(mode: RuleMode): String = mode.name

    @TypeConverter
    fun stringToRuleMode(value: String): RuleMode = RuleMode.valueOf(value)

    @TypeConverter
    fun outboxStatusToString(status: OutboxStatus): String = status.name

    @TypeConverter
    fun stringToOutboxStatus(value: String): OutboxStatus = OutboxStatus.valueOf(value)

    @TypeConverter
    fun outboxImageKindToString(kind: OutboxImageKind): String = kind.name

    @TypeConverter
    fun stringToOutboxImageKind(value: String): OutboxImageKind = OutboxImageKind.valueOf(value)
}
