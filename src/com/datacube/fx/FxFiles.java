package com.datacube.fx;

import java.io.File;

/** 文件对话框相关的小工具。 */
final class FxFiles {

    private FxFiles() {
    }

    /**
     * 返回适合作为「保存」对话框初始目录的路径：优先用户主目录下的 Downloads（下载）目录，
     * 不存在则回退到用户主目录；均不可用时返回 {@code null}（交由系统默认）。
     *
     * <p>避免保存对话框停在「此电脑」等设备级位置（在部分环境下会显示为网络位置）。
     */
    static File defaultSaveDir() {
        String home = System.getProperty("user.home");
        if (home == null || home.isEmpty()) {
            return null;
        }
        File downloads = new File(home, "Downloads");
        if (downloads.isDirectory()) {
            return downloads;
        }
        File h = new File(home);
        return h.isDirectory() ? h : null;
    }
}
