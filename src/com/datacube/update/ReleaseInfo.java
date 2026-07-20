package com.datacube.update;

/**
 * GitHub Release 信息（仅保留自动更新所需字段）。
 *
 * @param tag            release 标签（如 {@code v3.0.1}）
 * @param version        去除前导 v 的版本号（如 {@code 3.0.1}）
 * @param releaseNotes   release 正文（Markdown 原文，用于更新提示展示），可能为 null
 * @param htmlUrl        release 网页地址（UNKNOWN 形态下兜底打开），可能为 null
 * @param setupExeUrl    安装包资产下载地址（名含 {@code setup.exe}），无则为 null
 * @param portableZipUrl 绿色版资产下载地址（名含 {@code portable.zip}），无则为 null
 */
public record ReleaseInfo(
        String tag,
        String version,
        String releaseNotes,
        String htmlUrl,
        String setupExeUrl,
        String portableZipUrl) {
}
