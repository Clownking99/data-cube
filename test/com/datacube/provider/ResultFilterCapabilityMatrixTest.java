package com.datacube.provider;

import com.datacube.provider.oracle.OracleResultFilterSqlRenderer;
import com.datacube.provider.postgres.PgResultFilterSqlRenderer;
import com.datacube.spi.model.ResultColumn;
import com.datacube.sqleditor.result.FilterCondition;
import com.datacube.sqleditor.result.FilterConnector;
import com.datacube.sqleditor.result.FilterOperator;
import com.datacube.sqleditor.result.RenderedFilterQuery;
import com.datacube.sqleditor.result.ResultFilterSqlRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultFilterCapabilityMatrixTest {
    private static final Set<FilterOperator> TEXT_OPERATORS = Set.of(
            FilterOperator.EQ, FilterOperator.NE, FilterOperator.CONTAINS,
            FilterOperator.STARTS_WITH, FilterOperator.ENDS_WITH,
            FilterOperator.IS_NULL, FilterOperator.IS_NOT_NULL);
    private static final Set<FilterOperator> ORDERED_OPERATORS = Set.of(
            FilterOperator.EQ, FilterOperator.NE, FilterOperator.GT, FilterOperator.GTE,
            FilterOperator.LT, FilterOperator.LTE,
            FilterOperator.IS_NULL, FilterOperator.IS_NOT_NULL);
    private static final Set<FilterOperator> BOOLEAN_OPERATORS = Set.of(
            FilterOperator.EQ, FilterOperator.NE,
            FilterOperator.IS_NULL, FilterOperator.IS_NOT_NULL);
    private static final Set<FilterOperator> NULL_OPERATORS = Set.of(
            FilterOperator.IS_NULL, FilterOperator.IS_NOT_NULL);
    private static final Set<Integer> ALL_JDBC_TYPES = Set.of(
            Types.BIT, Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
            Types.FLOAT, Types.REAL, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL,
            Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
            Types.DATE, Types.TIME, Types.TIMESTAMP,
            Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY,
            Types.NULL, Types.OTHER, Types.JAVA_OBJECT, Types.DISTINCT, Types.STRUCT,
            Types.ARRAY, Types.BLOB, Types.CLOB, Types.REF, Types.DATALINK,
            Types.BOOLEAN, Types.ROWID, Types.NCHAR, Types.NVARCHAR,
            Types.LONGNVARCHAR, Types.NCLOB, Types.SQLXML, Types.REF_CURSOR,
            Types.TIME_WITH_TIMEZONE, Types.TIMESTAMP_WITH_TIMEZONE);

    static Stream<ProviderCase> providers() {
        return Stream.of(
                new ProviderCase("PostgreSQL", new PgResultFilterSqlRenderer(), false),
                new ProviderCase("Oracle", new OracleResultFilterSqlRenderer(), true));
    }

    @Test
    void testMatrixExplicitlyCoversEveryJavaSqlTypeConstant() throws Exception {
        Set<Integer> declared = Arrays.stream(Types.class.getFields())
                .filter(field -> field.getType() == int.class
                        && Modifier.isPublic(field.getModifiers())
                        && Modifier.isStatic(field.getModifiers()))
                .map(ResultFilterCapabilityMatrixTest::intValue)
                .collect(Collectors.toSet());

        assertEquals(declared, ALL_JDBC_TYPES);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("providers")
    void conditionSupportCoversEveryJdbcTypeAndEveryOperator(ProviderCase provider) {
        for (int jdbcType : ALL_JDBC_TYPES) {
            String typeName = typeName(jdbcType, provider.oracle());
            ResultColumn column = new ResultColumn(0, "VALUE", jdbcType, typeName);
            Set<FilterOperator> expected = expectedOperators(jdbcType, typeName, provider.oracle());
            for (FilterOperator operator : FilterOperator.values()) {
                ResultFilterSqlRenderer.ConditionSupport support =
                        provider.renderer().conditionSupport(column, operator);

                assertEquals(expected.contains(operator), support.supported(),
                        provider.name() + " / " + typeName + " / " + operator);
                if (support.supported()) {
                    assertNull(support.unavailableReason());
                } else {
                    assertNotNull(support.unavailableReason());
                    assertFalse(support.unavailableReason().isBlank());
                    assertTrue(support.unavailableReason().contains("VALUE"));
                    assertTrue(support.unavailableReason().contains(operatorLabel(operator)));
                }
            }
        }

        ResultColumn vendor = new ResultColumn(0, "VENDOR_VALUE", -100_001, "VENDOR_OBJECT");
        for (FilterOperator operator : FilterOperator.values()) {
            assertEquals(NULL_OPERATORS.contains(operator),
                    provider.renderer().conditionSupport(vendor, operator).supported());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("providers")
    void jsonTypeNameOverridesAnApparentlyTextualJdbcTypeWithValueFreeReason(ProviderCase provider) {
        ResultColumn json = new ResultColumn(2, "PAYLOAD", Types.VARCHAR, "JsOnB");

        for (FilterOperator operator : FilterOperator.values()) {
            ResultFilterSqlRenderer.ConditionSupport support =
                    provider.renderer().conditionSupport(json, operator);

            assertEquals(NULL_OPERATORS.contains(operator), support.supported(), operator.name());
            if (!support.supported()) {
                assertEquals("列“PAYLOAD”（JSON 类型）不支持数据库筛选运算符“"
                                + operatorLabel(operator) + "”；本地筛选仍可使用",
                        support.unavailableReason());
                assertFalse(support.unavailableReason().contains("JsOnB"));
            }
        }
    }

    @Test
    void uncommonJdbcTypesUseSpecificFixedCategoriesWithoutEchoingTypeNames() {
        ResultFilterSqlRenderer renderer = new PgResultFilterSqlRenderer();
        List<CategoryCase> cases = List.of(
                new CategoryCase(Types.NULL, "DRIVER_NULL_SECRET", "未声明数据库类型"),
                new CategoryCase(Types.JAVA_OBJECT, "DRIVER_JAVA_SECRET", "Java 对象类型"),
                new CategoryCase(Types.DISTINCT, "DRIVER_UDT_SECRET", "用户定义类型"),
                new CategoryCase(Types.DATALINK, "DRIVER_LINK_SECRET", "数据链接类型"),
                new CategoryCase(Types.SQLXML, "DRIVER_XML_SECRET", "XML 类型"),
                new CategoryCase(Types.STRUCT, "DRIVER_STRUCT_SECRET", "结构类型"),
                new CategoryCase(Types.REF, "DRIVER_REF_SECRET", "引用类型"));

        for (CategoryCase categoryCase : cases) {
            ResultFilterSqlRenderer.ConditionSupport support = renderer.conditionSupport(
                    new ResultColumn(0, "VALUE", categoryCase.jdbcType(),
                            categoryCase.driverTypeName()),
                    FilterOperator.EQ);

            assertFalse(support.supported());
            assertEquals("列“VALUE”（" + categoryCase.category()
                            + "）不支持数据库筛选运算符“等于”；本地筛选仍可使用",
                    support.unavailableReason());
            assertFalse(support.unavailableReason().contains(categoryCase.driverTypeName()));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("providers")
    void bfileIsALobAndOracleLongCodesStayFullyDisabledWithoutTypeNames(
            ProviderCase provider) {
        ResultColumn bfile = new ResultColumn(0, "FILE_VALUE", Types.OTHER, "BFILE");
        assertFalse(provider.renderer().conditionSupport(bfile, FilterOperator.EQ).supported());
        assertTrue(provider.renderer().conditionSupport(
                bfile, FilterOperator.IS_NULL).supported());

        if (!provider.oracle()) return;
        for (int jdbcType : List.of(
                Types.LONGVARCHAR, Types.LONGNVARCHAR, Types.LONGVARBINARY)) {
            ResultColumn oracleLong = new ResultColumn(0, "LONG_VALUE", jdbcType, "");
            for (FilterOperator operator : FilterOperator.values()) {
                assertFalse(provider.renderer().conditionSupport(
                                oracleLong, operator).supported(),
                        jdbcType + " / " + operator);
            }
        }
    }

    @Test
    void postgresRequiresExplicitPgjdbcTypePairsAcrossEveryOperator() {
        ResultFilterSqlRenderer renderer = new PgResultFilterSqlRenderer();
        List<PgTypeCase> cases = List.of(
                new PgTypeCase(Types.CHAR, "bpchar", TEXT_OPERATORS, "文本类型"),
                new PgTypeCase(Types.VARCHAR, "varchar", TEXT_OPERATORS, "文本类型"),
                new PgTypeCase(Types.VARCHAR, "text", TEXT_OPERATORS, "文本类型"),
                new PgTypeCase(Types.SMALLINT, "int2", ORDERED_OPERATORS, "数值类型"),
                new PgTypeCase(Types.INTEGER, "int4", ORDERED_OPERATORS, "数值类型"),
                new PgTypeCase(Types.BIGINT, "int8", ORDERED_OPERATORS, "数值类型"),
                new PgTypeCase(Types.NUMERIC, "numeric", ORDERED_OPERATORS, "数值类型"),
                new PgTypeCase(Types.REAL, "float4", ORDERED_OPERATORS, "数值类型"),
                new PgTypeCase(Types.DOUBLE, "float8", ORDERED_OPERATORS, "数值类型"),
                new PgTypeCase(Types.BIT, "bool", BOOLEAN_OPERATORS, "布尔类型"),
                new PgTypeCase(Types.DATE, "date", ORDERED_OPERATORS, "日期时间类型"),
                new PgTypeCase(Types.TIME, "time", ORDERED_OPERATORS, "日期时间类型"),
                new PgTypeCase(Types.TIMESTAMP, "timestamp", ORDERED_OPERATORS, "日期时间类型"),
                new PgTypeCase(Types.BIGINT, "oid", NULL_OPERATORS, "PostgreSQL OID 类型"),
                new PgTypeCase(Types.DOUBLE, "money", NULL_OPERATORS, "PostgreSQL money 类型"),
                new PgTypeCase(Types.BIT, "bit", NULL_OPERATORS, "PostgreSQL 位串类型"),
                new PgTypeCase(Types.OTHER, "varbit", NULL_OPERATORS, "PostgreSQL 位串类型"),
                new PgTypeCase(Types.CHAR, "char", NULL_OPERATORS, "PostgreSQL 内部 char 类型"),
                new PgTypeCase(Types.VARCHAR, "name", NULL_OPERATORS, "PostgreSQL name 类型"),
                new PgTypeCase(Types.TIME, "timetz", NULL_OPERATORS,
                        "PostgreSQL 带时区日期时间类型"),
                new PgTypeCase(Types.TIMESTAMP, "timestamptz", NULL_OPERATORS,
                        "PostgreSQL 带时区日期时间类型"),
                new PgTypeCase(Types.VARCHAR, "private_domain", NULL_OPERATORS,
                        "数据库专有类型"));

        for (PgTypeCase typeCase : cases) {
            ResultColumn column = new ResultColumn(
                    0, "VALUE", typeCase.jdbcType(), typeCase.typeName());
            for (FilterOperator operator : FilterOperator.values()) {
                ResultFilterSqlRenderer.ConditionSupport support =
                        renderer.conditionSupport(column, operator);

                assertEquals(typeCase.operators().contains(operator), support.supported(),
                        typeCase.typeName() + " / " + operator);
                if (!support.supported()) {
                    assertEquals("列“VALUE”（" + typeCase.category()
                                    + "）不支持数据库筛选运算符“"
                                    + operatorLabel(operator) + "”；本地筛选仍可使用",
                            support.unavailableReason());
                    assertFalse(support.unavailableReason().contains("private_domain"));
                }
            }
        }
    }

    @Test
    void legacyRendererDefaultsToFailClosedConditionSupport() {
        ResultFilterSqlRenderer legacy = (sql, columns, conditions) ->
                new RenderedFilterQuery(sql, List.of());
        ResultColumn column = new ResultColumn(0, "NAME", Types.VARCHAR, "VARCHAR");

        ResultFilterSqlRenderer.ConditionSupport support =
                legacy.conditionSupport(column, FilterOperator.EQ);

        assertFalse(support.supported());
        assertEquals("列“NAME”（未声明数据库类型）不支持数据库筛选运算符“等于”；本地筛选仍可使用",
                support.unavailableReason());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("providers")
    void rendererPreflightRejectsFirstUnsupportedConditionWithoutDisclosingValues(
            ProviderCase provider) {
        String firstSecret = "first-secret-value";
        String secondSecret = "second-secret-value";
        List<ResultColumn> columns = List.of(
                new ResultColumn(0, "PAYLOAD", Types.OTHER, "JSONB"),
                new ResultColumn(1, "DOCUMENT", Types.CLOB, "CLOB"));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> provider.renderer().render("select payload, document from events", columns,
                        List.of(new FilterCondition(0, FilterConnector.AND,
                                        FilterOperator.EQ, firstSecret),
                                new FilterCondition(1, FilterConnector.AND,
                                        FilterOperator.EQ, secondSecret))));

        assertEquals("列“PAYLOAD”（JSON 类型）不支持数据库筛选运算符“等于”；本地筛选仍可使用",
                failure.getMessage());
        assertFalse(failure.getMessage().contains(firstSecret));
        assertFalse(failure.getMessage().contains(secondSecret));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("providers")
    void unsupportedValueCategoriesStillAllowParameterFreeNullPredicates(ProviderCase provider) {
        List<ResultColumn> columns = List.of(
                new ResultColumn(0, "JSON_VALUE", Types.OTHER, "JSONB"),
                new ResultColumn(1, "LOB_VALUE", Types.CLOB, "CLOB"),
                new ResultColumn(2, "BINARY_VALUE", Types.VARBINARY, "VARBINARY"),
                new ResultColumn(3, "ARRAY_VALUE", Types.ARRAY, "ARRAY"),
                new ResultColumn(4, "STRUCT_VALUE", Types.STRUCT, "STRUCT"),
                new ResultColumn(5, "VENDOR_VALUE", Types.OTHER, "UUID"));
        List<FilterCondition> conditions = java.util.stream.IntStream.range(0, columns.size())
                .mapToObj(index -> new FilterCondition(index,
                        index == 0 ? FilterConnector.AND : FilterConnector.OR,
                        index % 2 == 0 ? FilterOperator.IS_NULL : FilterOperator.IS_NOT_NULL,
                        null))
                .toList();

        RenderedFilterQuery query = provider.renderer().render(
                "select * from values_table", columns, conditions);

        assertTrue(query.parameters().isEmpty());
        assertEquals(columns.size(), query.sql().split("IS NULL|IS NOT NULL", -1).length - 1);
    }

    private static Set<FilterOperator> expectedOperators(
            int jdbcType, String typeName, boolean oracle) {
        if (!oracle) return expectedPostgresOperators(jdbcType, typeName);
        if (oracle && Set.of("LONG", "LONG VARCHAR", "LONG NVARCHAR", "LONG RAW")
                .contains(typeName)) {
            return Set.of();
        }
        if (jdbcType == Types.REF_CURSOR) return Set.of();
        return switch (jdbcType) {
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
                    Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR -> TEXT_OPERATORS;
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                    Types.REAL, Types.FLOAT, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL,
                    Types.DATE, Types.TIME, Types.TIME_WITH_TIMEZONE,
                    Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> ORDERED_OPERATORS;
            case Types.BIT, Types.BOOLEAN -> BOOLEAN_OPERATORS;
            default -> NULL_OPERATORS;
        };
    }

    private static Set<FilterOperator> expectedPostgresOperators(
            int jdbcType, String typeName) {
        if (jdbcType == Types.REF_CURSOR) return Set.of();
        if (jdbcType == Types.CHAR && typeName.equals("bpchar")
                || jdbcType == Types.VARCHAR
                        && Set.of("varchar", "text").contains(typeName)) {
            return TEXT_OPERATORS;
        }
        if (jdbcType == Types.SMALLINT && typeName.equals("int2")
                || jdbcType == Types.INTEGER && typeName.equals("int4")
                || jdbcType == Types.BIGINT && typeName.equals("int8")
                || jdbcType == Types.NUMERIC && typeName.equals("numeric")
                || jdbcType == Types.REAL && typeName.equals("float4")
                || jdbcType == Types.DOUBLE && typeName.equals("float8")
                || jdbcType == Types.DATE && typeName.equals("date")
                || jdbcType == Types.TIME && typeName.equals("time")
                || jdbcType == Types.TIMESTAMP && typeName.equals("timestamp")) {
            return ORDERED_OPERATORS;
        }
        if (jdbcType == Types.BIT && typeName.equals("bool")) return BOOLEAN_OPERATORS;
        return NULL_OPERATORS;
    }

    private static String typeName(int jdbcType, boolean oracle) {
        if (!oracle) {
            return switch (jdbcType) {
                case Types.CHAR -> "bpchar";
                case Types.VARCHAR -> "varchar";
                case Types.SMALLINT -> "int2";
                case Types.INTEGER -> "int4";
                case Types.BIGINT -> "int8";
                case Types.REAL -> "float4";
                case Types.DOUBLE -> "float8";
                case Types.NUMERIC -> "numeric";
                case Types.BIT -> "bool";
                case Types.DATE -> "date";
                case Types.TIME -> "time";
                case Types.TIMESTAMP -> "timestamp";
                case Types.BINARY -> "bytea";
                case Types.REF_CURSOR -> "refcursor";
                default -> jdbcTypeName(jdbcType);
            };
        }
        if (oracle) {
            if (jdbcType == Types.LONGVARCHAR) return "LONG";
            if (jdbcType == Types.LONGNVARCHAR) return "LONG NVARCHAR";
            if (jdbcType == Types.LONGVARBINARY) return "LONG RAW";
        }
        return jdbcTypeName(jdbcType);
    }

    private static String jdbcTypeName(int jdbcType) {
        return Arrays.stream(Types.class.getFields())
                .filter(field -> field.getType() == int.class)
                .filter(field -> intValue(field) == jdbcType)
                .map(Field::getName)
                .findFirst().orElse("VENDOR");
    }

    private static int intValue(Field field) {
        try {
            return field.getInt(null);
        } catch (IllegalAccessException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String operatorLabel(FilterOperator operator) {
        return switch (operator) {
            case EQ -> "等于";
            case NE -> "不等于";
            case CONTAINS -> "包含";
            case STARTS_WITH -> "开头是";
            case ENDS_WITH -> "结尾是";
            case GT -> "大于";
            case GTE -> "大于等于";
            case LT -> "小于";
            case LTE -> "小于等于";
            case IS_NULL -> "为空";
            case IS_NOT_NULL -> "非空";
        };
    }

    record ProviderCase(String name, ResultFilterSqlRenderer renderer, boolean oracle) {
        @Override
        public String toString() {
            return name;
        }
    }

    record CategoryCase(int jdbcType, String driverTypeName, String category) {
    }

    record PgTypeCase(
            int jdbcType, String typeName, Set<FilterOperator> operators, String category) {
    }
}
