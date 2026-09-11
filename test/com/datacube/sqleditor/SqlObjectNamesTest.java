package com.datacube.sqleditor;

import com.datacube.spi.model.DbType;
import com.datacube.spi.model.TableRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SqlObjectNamesTest {
    @ParameterizedTest @EnumSource(value = DbType.class, names = {"POSTGRESQL", "ORACLE"})
    void preservesExactMetadataAsTwoQuotedIdentifiersWithoutAddingSql(DbType type) {
        assertEquals("\"Sales\".\"Order\"", SqlObjectNames.qualifiedName(type, new TableRef("Sales", "Order")));
        assertEquals("\" 公司.\"\"East \".\"订单 🧊\"\"; DROP TABLE accounts;--\"",
                SqlObjectNames.qualifiedName(type, new TableRef(" 公司.\"East ", "订单 🧊\"; DROP TABLE accounts;--")));
        assertEquals("\" \".\" \"", SqlObjectNames.qualifiedName(type, new TableRef(" ", " ")));
    }

    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings = {"x\ny", "x\r\ny", "x\ty", "\0", "\u007f", "\u0085", "\u2028", "\u2029",
            "\ud800", "\udc00", "\ud800x"})
    void rejectsUnrepresentableNamesInsteadOfCleaningThem(String name) {
        assertThrows(IllegalArgumentException.class, () -> SqlObjectNames.qualifiedName(DbType.POSTGRESQL, new TableRef(name, "t")));
        assertThrows(IllegalArgumentException.class, () -> SqlObjectNames.qualifiedName(DbType.ORACLE, new TableRef("s", name)));
    }

    @ParameterizedTest @ValueSource(ints = {1023, 1024, 1025})
    void boundsEachInputIdentifierBeforeQuoteExpansion(int length) {
        String name = "\"".repeat(length);
        if (length <= 1024) {
            String quoted = "\"" + "\"\"".repeat(length) + "\"";
            assertEquals(quoted + "." + quoted, SqlObjectNames.qualifiedName(DbType.POSTGRESQL, new TableRef(name, name)));
        } else {
            assertThrows(IllegalArgumentException.class, () -> SqlObjectNames.qualifiedName(DbType.POSTGRESQL, new TableRef(name, "t")));
            assertThrows(IllegalArgumentException.class, () -> SqlObjectNames.qualifiedName(DbType.ORACLE, new TableRef("s", name)));
        }
    }

    @Test void rejectsMissingObjectOrUnsupportedDialect() {
        assertThrows(IllegalArgumentException.class, () -> SqlObjectNames.qualifiedName(DbType.POSTGRESQL, null));
        assertThrows(IllegalArgumentException.class, () -> SqlObjectNames.qualifiedName(null, new TableRef("s", "t")));
        assertThrows(IllegalArgumentException.class, () -> SqlObjectNames.qualifiedName(DbType.REDIS, new TableRef("s", "t")));
    }
}
