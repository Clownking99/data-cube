package com.datacube.redis;

/** Immutable per-pane display budgets; test instances can only be smaller. */
public record RedisDisplayLimits(int consoleChars, int outputEntries, int outputChars,
                                 int historyEntries, int historyChars, int keys, int keyBytes,
                                 int singleKeyBytes, int treeNodes, int depth, int separatorChars,
                                 int matchChars, int labelChars, int rows, int cellChars,
                                 int pageChars, int editorChars) {
    public static final RedisDisplayLimits DEFAULT = new RedisDisplayLimits(
            64 * 1024, 200, 512 * 1024, 100, 64 * 1024, 5000, 2 * 1024 * 1024,
            64 * 1024, 20_000, 32, 64, 4096, 4096, 1000, 4096, 256 * 1024, 1024 * 1024);
    public RedisDisplayLimits {
        int[] values = {consoleChars,outputEntries,outputChars,historyEntries,historyChars,keys,keyBytes,
                singleKeyBytes,treeNodes,depth,separatorChars,matchChars,labelChars,rows,cellChars,pageChars,editorChars};
        int[] maxima = {65536,200,524288,100,65536,5000,2097152,65536,20000,32,64,4096,4096,1000,4096,262144,1048576};
        for (int i = 0; i < values.length; i++) if (values[i] < 1 || values[i] > maxima[i]) throw new IllegalArgumentException("Display budgets must be positive and no larger than defaults");
    }
}
