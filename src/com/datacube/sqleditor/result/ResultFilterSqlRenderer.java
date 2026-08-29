package com.datacube.sqleditor.result;

import com.datacube.spi.model.ResultColumn;

import java.sql.Types;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Renders a filtered wrapper query for one database dialect. */
@FunctionalInterface
public interface ResultFilterSqlRenderer {
    RenderedFilterQuery render(
            String originalSql, List<ResultColumn> columns, List<FilterCondition> conditions);

    /** Provider-owned decision for one result column and one database filter operator. */
    default ConditionSupport conditionSupport(ResultColumn column, FilterOperator operator) {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(operator, "operator");
        return ConditionSupport.denied(unavailableReason(column, operator, "未声明数据库类型"));
    }

    /** Returns the first unsupported condition reason without reading any condition value. */
    default String firstUnsupportedReason(
            List<ResultColumn> columns, List<FilterCondition> conditions) {
        List<ResultColumn> metadata = List.copyOf(columns);
        for (FilterCondition condition : List.copyOf(conditions)) {
            int index = condition.columnIndex();
            if (index < 0 || index >= metadata.size()) {
                return "筛选列已不存在，无法执行数据库筛选；本地筛选仍可使用";
            }
            ConditionSupport support = conditionSupport(
                    metadata.get(index), condition.operator());
            if (!support.supported()) return support.unavailableReason();
        }
        return null;
    }

    /** Shared conservative JDBC baseline selected explicitly by each provider renderer. */
    static ConditionSupport jdbcConditionSupport(
            ResultColumn column, FilterOperator operator, boolean oracleMode) {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(operator, "operator");
        int jdbcType = column.jdbcType();
        String typeName = normalizedTypeName(column.jdbcTypeName());

        String category;
        boolean supported;
        if (oracleMode && (jdbcType == Types.LONGVARCHAR
                || jdbcType == Types.LONGNVARCHAR
                || jdbcType == Types.LONGVARBINARY
                || Set.of("LONG", "LONG VARCHAR", "LONG NVARCHAR", "LONG RAW")
                        .contains(typeName))) {
            category = "Oracle LONG 类型";
            supported = false;
        } else if (Set.of("JSON", "JSONB").contains(typeName)) {
            category = "JSON 类型";
            supported = nullOperator(operator);
        } else if (Set.of("LOB", "BLOB", "CLOB", "NCLOB", "BFILE").contains(typeName)
                || jdbcType == Types.BLOB || jdbcType == Types.CLOB || jdbcType == Types.NCLOB) {
            category = "大对象类型";
            supported = nullOperator(operator);
        } else {
            switch (jdbcType) {
                case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
                        Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR -> {
                    category = "文本类型";
                    supported = textOperator(operator);
                }
                case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                        Types.REAL, Types.FLOAT, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL -> {
                    category = "数值类型";
                    supported = orderedOperator(operator);
                }
                case Types.DATE, Types.TIME, Types.TIME_WITH_TIMEZONE,
                        Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> {
                    category = "日期时间类型";
                    supported = orderedOperator(operator);
                }
                case Types.BIT, Types.BOOLEAN -> {
                    category = "布尔类型";
                    supported = equalityOrNullOperator(operator);
                }
                case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> {
                    category = "二进制类型";
                    supported = nullOperator(operator);
                }
                case Types.ARRAY -> {
                    category = "数组类型";
                    supported = nullOperator(operator);
                }
                case Types.STRUCT -> {
                    category = "结构类型";
                    supported = nullOperator(operator);
                }
                case Types.REF -> {
                    category = "引用类型";
                    supported = nullOperator(operator);
                }
                case Types.ROWID -> {
                    category = "ROWID 类型";
                    supported = nullOperator(operator);
                }
                case Types.NULL -> {
                    category = "未声明数据库类型";
                    supported = nullOperator(operator);
                }
                case Types.JAVA_OBJECT -> {
                    category = "Java 对象类型";
                    supported = nullOperator(operator);
                }
                case Types.DISTINCT -> {
                    category = "用户定义类型";
                    supported = nullOperator(operator);
                }
                case Types.DATALINK -> {
                    category = "数据链接类型";
                    supported = nullOperator(operator);
                }
                case Types.SQLXML -> {
                    category = "XML 类型";
                    supported = nullOperator(operator);
                }
                case Types.REF_CURSOR -> {
                    category = "游标类型";
                    supported = false;
                }
                default -> {
                    category = "数据库专有类型";
                    supported = nullOperator(operator);
                }
            }
        }

        return supported ? ConditionSupport.allowed()
                : ConditionSupport.denied(unavailableReason(column, operator, category));
    }

    /** pgjdbc-safe value predicates require both the JDBC code and PostgreSQL type name. */
    static ConditionSupport postgresConditionSupport(
            ResultColumn column, FilterOperator operator) {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(operator, "operator");
        String typeName = normalizedTypeName(column.jdbcTypeName());
        String valueCategory = postgresValueCategory(column.jdbcType(), typeName);
        if (valueCategory != null) {
            boolean supported = switch (valueCategory) {
                case "文本类型" -> textOperator(operator);
                case "数值类型", "日期时间类型" -> orderedOperator(operator);
                case "布尔类型" -> equalityOrNullOperator(operator);
                default -> false;
            };
            return supported ? ConditionSupport.allowed()
                    : ConditionSupport.denied(
                            unavailableReason(column, operator, valueCategory));
        }

        String restrictedCategory = postgresRestrictedCategory(typeName);
        if (restrictedCategory != null) {
            return nullOperator(operator) ? ConditionSupport.allowed()
                    : ConditionSupport.denied(
                            unavailableReason(column, operator, restrictedCategory));
        }

        ConditionSupport jdbcBaseline = jdbcConditionSupport(column, operator, false);
        if (Set.of("JSON", "JSONB", "LOB", "BLOB", "CLOB", "NCLOB", "BFILE")
                .contains(typeName) || !jdbcValueCapableType(column.jdbcType())) {
            return jdbcBaseline;
        }
        if (nullOperator(operator)) return ConditionSupport.allowed();
        return ConditionSupport.denied(
                unavailableReason(column, operator, "数据库专有类型"));
    }

    private static boolean jdbcValueCapableType(int jdbcType) {
        return switch (jdbcType) {
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
                    Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
                    Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                    Types.REAL, Types.FLOAT, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL,
                    Types.DATE, Types.TIME, Types.TIME_WITH_TIMEZONE,
                    Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE,
                    Types.BIT, Types.BOOLEAN -> true;
            default -> false;
        };
    }

    private static String postgresValueCategory(int jdbcType, String typeName) {
        return switch (jdbcType) {
            case Types.CHAR -> typeName.equals("BPCHAR") ? "文本类型" : null;
            case Types.VARCHAR -> Set.of("VARCHAR", "TEXT").contains(typeName)
                    ? "文本类型" : null;
            case Types.SMALLINT -> typeName.equals("INT2") ? "数值类型" : null;
            case Types.INTEGER -> typeName.equals("INT4") ? "数值类型" : null;
            case Types.BIGINT -> typeName.equals("INT8") ? "数值类型" : null;
            case Types.NUMERIC -> typeName.equals("NUMERIC") ? "数值类型" : null;
            case Types.REAL -> typeName.equals("FLOAT4") ? "数值类型" : null;
            case Types.DOUBLE -> typeName.equals("FLOAT8") ? "数值类型" : null;
            case Types.BIT -> typeName.equals("BOOL") ? "布尔类型" : null;
            case Types.DATE -> typeName.equals("DATE") ? "日期时间类型" : null;
            case Types.TIME -> typeName.equals("TIME") ? "日期时间类型" : null;
            case Types.TIMESTAMP -> typeName.equals("TIMESTAMP")
                    ? "日期时间类型" : null;
            default -> null;
        };
    }

    private static String postgresRestrictedCategory(String typeName) {
        return switch (typeName) {
            case "OID" -> "PostgreSQL OID 类型";
            case "MONEY" -> "PostgreSQL money 类型";
            case "BIT", "VARBIT" -> "PostgreSQL 位串类型";
            case "CHAR" -> "PostgreSQL 内部 char 类型";
            case "NAME" -> "PostgreSQL name 类型";
            case "TIMETZ", "TIMESTAMPTZ" -> "PostgreSQL 带时区日期时间类型";
            default -> null;
        };
    }

    private static boolean textOperator(FilterOperator operator) {
        return switch (operator) {
            case EQ, NE, CONTAINS, STARTS_WITH, ENDS_WITH, IS_NULL, IS_NOT_NULL -> true;
            default -> false;
        };
    }

    private static boolean orderedOperator(FilterOperator operator) {
        return switch (operator) {
            case EQ, NE, GT, GTE, LT, LTE, IS_NULL, IS_NOT_NULL -> true;
            default -> false;
        };
    }

    private static boolean equalityOrNullOperator(FilterOperator operator) {
        return operator == FilterOperator.EQ || operator == FilterOperator.NE
                || nullOperator(operator);
    }

    private static boolean nullOperator(FilterOperator operator) {
        return operator == FilterOperator.IS_NULL || operator == FilterOperator.IS_NOT_NULL;
    }

    private static String unavailableReason(
            ResultColumn column, FilterOperator operator, String category) {
        String label = column.label().isBlank()
                ? "第 " + (column.index() + 1) + " 列" : safeMetadataText(column.label());
        return "列“" + label + "”（" + category + "）不支持数据库筛选运算符“"
                + operatorLabel(operator) + "”；本地筛选仍可使用";
    }

    private static String safeMetadataText(String value) {
        StringBuilder safe = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            safe.append(Character.isISOControl(character) ? ' ' : character);
        }
        return safe.toString();
    }

    private static String normalizedTypeName(String value) {
        return value == null ? "" : value.trim().replace('_', ' ')
                .replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
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

    record ConditionSupport(boolean supported, String unavailableReason) {
        public ConditionSupport {
            if (supported) {
                unavailableReason = null;
            } else if (unavailableReason == null || unavailableReason.isBlank()) {
                throw new IllegalArgumentException("unsupported condition requires a reason");
            }
        }

        public static ConditionSupport allowed() {
            return new ConditionSupport(true, null);
        }

        public static ConditionSupport denied(String reason) {
            return new ConditionSupport(false, reason);
        }
    }
}
