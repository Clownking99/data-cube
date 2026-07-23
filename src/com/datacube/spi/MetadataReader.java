package com.datacube.spi;

import com.datacube.spi.model.CatalogInfo;
import com.datacube.spi.model.ColumnInfo;
import com.datacube.spi.model.ConstraintInfo;
import com.datacube.spi.model.IndexInfo;
import com.datacube.spi.model.PackageInfo;
import com.datacube.spi.model.RoutineInfo;
import com.datacube.spi.model.SchemaInfo;
import com.datacube.spi.model.SequenceDraft;
import com.datacube.spi.model.SequenceInfo;
import com.datacube.spi.model.TableInfo;
import com.datacube.spi.model.TableRef;
import com.datacube.spi.model.TriggerInfo;
import com.datacube.spi.model.TypeInfo;
import com.datacube.spi.model.ViewInfo;

import java.sql.SQLException;
import java.util.List;

/**
 * 元数据读取能力：为对象树/DDL 提供 catalog/schema/表/视图/列/索引/约束/函数/序列信息。
 *
 * <p>使用通用 catalog/schema 模型，不假设数据库专属层级（如 Oracle 的 user=schema）。
 * 由 {@link DatabaseProvider#metadataReader} 绑定具体 {@link java.sql.Connection} 后返回。
 */
public interface MetadataReader {

    List<CatalogInfo> catalogs() throws SQLException;

    List<SchemaInfo> schemas(String catalog) throws SQLException;

    List<TableInfo> tables(String schema) throws SQLException;

    List<ViewInfo> views(String schema) throws SQLException;

    List<ColumnInfo> columns(TableRef t) throws SQLException;

    List<IndexInfo> indexes(TableRef t) throws SQLException;

    List<ConstraintInfo> constraints(TableRef t) throws SQLException;

    List<RoutineInfo> routines(String schema) throws SQLException;

    List<PackageInfo> packages(String schema) throws SQLException;

    List<TriggerInfo> triggers(String schema) throws SQLException;

    List<TypeInfo> types(String schema) throws SQLException;

    List<SequenceInfo> sequences(String schema) throws SQLException;

    /** 读取单个序列的完整属性（最小/最大/递增/缓存/循环/有序/下一个数字）。 */
    SequenceDraft sequence(String schema, String name) throws SQLException;
}
