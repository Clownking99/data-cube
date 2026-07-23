package com.datacube.spi.model;

/**
 * 程序包信息（Oracle PL/SQL package，含规格说明与包体）。
 */
public record PackageInfo(String schema, String name) {
}
