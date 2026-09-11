package com.datacube.sqleditor;

import com.datacube.spi.model.DbType;
import com.datacube.spi.model.TableRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class TableSelectSqlTest {
    @ParameterizedTest @EnumSource(value = DbType.class, names = {"POSTGRESQL", "ORACLE"})
    void generatesQualifiedSelectPreservingIdentifierCaseSpacesDotsQuotesAndUnicode(DbType type) {
        assertEquals("SELECT *\nFROM \"Sales\".\"Order\";",
                TableSelectSql.generate(type, new TableRef("Sales", "Order")));
        assertEquals("SELECT *\nFROM \" 公司.\"\"East \".\"订单 🧊\"\"; DROP TABLE accounts;--\";",
                TableSelectSql.generate(type, new TableRef(" 公司.\"East ", "订单 🧊\"; DROP TABLE accounts;--")));
        assertEquals("SELECT *\nFROM \" \".\" \";", TableSelectSql.generate(type, new TableRef(" ", " ")));
    }

    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings = {"a\nb", "a\r\nb", "a\tb", "a\0b", "a\u007fb", "a\u0085b", "a\u2028b", "a\u2029b", "\ud800", "\udc00", "\ud800x"})
    void refusesNamesThatCannotBePreservedWithoutCleaningOrTruncation(String name) {
        assertThrows(IllegalArgumentException.class, () -> TableSelectSql.generate(DbType.POSTGRESQL, new TableRef(name, "good")));
        assertThrows(IllegalArgumentException.class, () -> TableSelectSql.generate(DbType.ORACLE, new TableRef("good", name)));
    }

    @ParameterizedTest @ValueSource(ints = {1023, 1024, 1025})
    void identifierLimitIsCheckedBeforeEscapingWithoutTruncation(int length) {
        String name = "\"".repeat(length);
        if (length <= 1024) {
            assertEquals("SELECT *\nFROM \"s\".\"" + "\"\"".repeat(length) + "\";",
                    TableSelectSql.generate(DbType.ORACLE, new TableRef("s", name)));
        } else {
            assertThrows(IllegalArgumentException.class, () -> TableSelectSql.generate(DbType.ORACLE, new TableRef("s", name)));
            assertThrows(IllegalArgumentException.class, () -> TableSelectSql.generate(DbType.ORACLE, new TableRef(name, "t")));
        }
    }

    @Test void rejectsUnsupportedDatabaseAndMissingTable() {
        assertThrows(IllegalArgumentException.class, () -> TableSelectSql.generate(DbType.REDIS, new TableRef("s", "t")));
        assertThrows(IllegalArgumentException.class, () -> TableSelectSql.generate(null, new TableRef("s", "t")));
        assertThrows(IllegalArgumentException.class, () -> TableSelectSql.generate(DbType.POSTGRESQL, null));
    }
}
