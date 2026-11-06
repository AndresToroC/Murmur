package com.nochesonora.data.db

import androidx.room.TypeConverter
import com.nochesonora.core.classification.Category

/** Los enums se guardan por nombre para que las consultas puedan compararlos como texto. */
class Converters {
    @TypeConverter fun categoryToString(c: Category?): String? = c?.name
    @TypeConverter fun stringToCategory(s: String?): Category? = s?.let { Category.valueOf(it) }
    @TypeConverter fun statusToString(s: SessionStatus?): String? = s?.name
    @TypeConverter fun stringToStatus(s: String?): SessionStatus? = s?.let { SessionStatus.valueOf(it) }
}
