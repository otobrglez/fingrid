package fingrid.persistence.entities;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.LocalDateTime;
import java.time.YearMonth;

@Converter
public class YearMonthConverter implements AttributeConverter<YearMonth, LocalDateTime> {

    @Override
    public LocalDateTime convertToDatabaseColumn(YearMonth yearMonth) {
        if (yearMonth == null) {
            return null;
        }
        return yearMonth.atDay(1).atStartOfDay();
    }

    @Override
    public YearMonth convertToEntityAttribute(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return YearMonth.from(dateTime);
    }
}
