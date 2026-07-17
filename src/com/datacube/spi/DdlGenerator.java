package com.datacube.spi;

import com.datacube.spi.model.RoutineRef;
import com.datacube.spi.model.TableRef;

import java.sql.SQLException;

/**
 * DDL 生成能力：读取数据库对象的 CREATE 语句。
 */
public interface DdlGenerator {

    String tableDdl(TableRef t) throws SQLException;

    String viewDdl(TableRef t) throws SQLException;

    String routineDdl(RoutineRef r) throws SQLException;

    String sequenceDdl(String schema, String name) throws SQLException;
}
