package com.datacube.sqleditor;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Objects;

/** Pure editor state for one SQL script's identity and saved text baseline. */
public final class SqlScriptDocument {
    private Path path;
    private SqlScriptFileStore.Target target;
    private String baseline;
    private int baselineLength;
    private long baselineHash;
    /** Exact physical text persisted to disk; CodeArea exposes its normalized view. */
    private PhysicalTextBuffer physical;
    private String preferredSeparator;
    private int incrementalChangeCount;
    private int fullTextMaterializationCount;

    public SqlScriptDocument() { this(""); }

    public SqlScriptDocument(String baseline) {
        setPhysical(Objects.requireNonNull(baseline, "baseline"));
        setBaseline(baseline);
    }

    public Path path() { return path; }

    public SqlScriptFileStore.Target target() { return target; }

    /** Fast current-state dirty check for the editor's incremental change stream. */
    public boolean dirty() {
        if (physical.normalizedLength() != baselineLength || physical.normalizedHash() != baselineHash) {
            return true;
        }
        // A hash match is only a fast reject. Confirm equal-length candidates exactly.
        return !physical.matchesNormalized(baseline);
    }

    /** Compatibility query for callers holding an independent editor snapshot. */
    public boolean dirty(String currentText) {
        return !baseline.equals(normalize(Objects.requireNonNull(currentText, "currentText")));
    }

    public String title(String fallback) {
        String baseTitle = path == null ? Objects.requireNonNull(fallback, "fallback")
                : path.getFileName().toString();
        return dirty() ? baseTitle + "*" : baseTitle;
    }

    public String title(String fallback, String currentText) {
        String baseTitle = path == null ? Objects.requireNonNull(fallback, "fallback")
                : path.getFileName().toString();
        return dirty(currentText) ? baseTitle + "*" : baseTitle;
    }

    public void attach(SqlScriptFileStore.Loaded loaded) { bind(loaded, true); }

    public void saved(SqlScriptFileStore.Loaded loaded) {
        // Keep a newer editor shadow intact when a save settles after another edit.
        bind(loaded, false);
    }

    /** The normalized representation that is safe to put in RichTextFX. */
    public String normalizedText() {
        fullTextMaterializationCount++;
        return physical.normalizedText();
    }

    /** Exact UTF-16 text to pass to the file store. */
    public String physicalText() {
        fullTextMaterializationCount++;
        return physical.physicalText();
    }

    /**
     * Applies RichTextFX's precise plain-text change. Positions and lengths are UTF-16 offsets.
     * This updates a chunked physical-text buffer, so ordinary keystrokes neither diff nor rebuild
     * the complete script.
     */
    public void editorTextChanged(int position, String removed, String inserted) {
        Objects.requireNonNull(removed, "removed");
        Objects.requireNonNull(inserted, "inserted");
        if (position == 0 && normalizedLength(removed) == physical.normalizedLength()) {
            replaceWholeNormalized(removed, inserted);
            incrementalChangeCount++;
            return;
        }
        physical.replace(position, normalizedLength(removed), toPhysical(inserted, preferredSeparator));
        incrementalChangeCount++;
    }

    int incrementalChangeCount() { return incrementalChangeCount; }

    int fullTextMaterializationCount() { return fullTextMaterializationCount; }

    int segmentCount() { return physical.segmentCount(); }

    int maxSegmentLength() { return physical.maxSegmentLength(); }

    int treeHeight() { return physical.treeHeight(); }

    int physicalLength() { return physical.physicalLength(); }

    int tinySegmentCount(int limit) { return physical.tinySegmentCount(limit); }

    private void bind(SqlScriptFileStore.Loaded loaded, boolean replacePhysicalText) {
        SqlScriptFileStore.Loaded snapshot = Objects.requireNonNull(loaded, "loaded");
        Path nextPath = Objects.requireNonNull(snapshot.path(), "loaded.path");
        SqlScriptFileStore.Target nextTarget = Objects.requireNonNull(snapshot.target(), "loaded.target");
        String raw = Objects.requireNonNull(snapshot.text(), "loaded.text");
        path = nextPath;
        target = nextTarget;
        setBaseline(raw);
        if (replacePhysicalText) setPhysical(raw);
    }

    private void setBaseline(String raw) {
        baseline = normalize(raw);
        baselineLength = baseline.length();
        baselineHash = hash(baseline);
    }

    private void setPhysical(String raw) {
        physical = new PhysicalTextBuffer(raw);
        preferredSeparator = separatorOf(raw);
    }

    /** RichTextFX emits one whole-text change for replaceText/paste; preserve its shared margins. */
    private void replaceWholeNormalized(String before, String after) {
        int prefix = 0;
        int shared = Math.min(before.length(), after.length());
        while (prefix < shared && before.charAt(prefix) == after.charAt(prefix)) prefix++;
        int suffix = 0;
        while (suffix < before.length() - prefix && suffix < after.length() - prefix
                && before.charAt(before.length() - 1 - suffix) == after.charAt(after.length() - 1 - suffix)) {
            suffix++;
        }
        physical.replace(prefix, before.length() - prefix - suffix,
                toPhysical(after.substring(prefix, after.length() - suffix), preferredSeparator));
    }

    private static String toPhysical(String text, String separator) {
        StringBuilder physical = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character == '\r') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                physical.append(separator);
            } else if (character == '\n') {
                physical.append(separator);
            } else {
                physical.append(character);
            }
        }
        return physical.toString();
    }

    private static String normalize(String text) {
        StringBuilder normalized = new StringBuilder(text.length());
        appendNormalized(normalized, text);
        return normalized.toString();
    }

    private static void appendNormalized(StringBuilder destination, String text) {
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character == '\r') {
                destination.append('\n');
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
            } else {
                destination.append(character);
            }
        }
    }

    private static int normalizedLength(String text) {
        int normalized = 0;
        for (int i = 0; i < text.length(); i++, normalized++) {
            if (text.charAt(i) == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
        }
        return normalized;
    }

    private static String separatorOf(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\r') return i + 1 < text.length() && text.charAt(i + 1) == '\n'
                    ? "\r\n" : "\r";
            if (text.charAt(i) == '\n') return "\n";
        }
        return "\n";
    }

    private static long hash(String text) {
        long result = 0;
        for (int i = 0; i < text.length(); i++) result = result * PhysicalTextBuffer.HASH_BASE + text.charAt(i);
        return result;
    }

    /** A treap of bounded raw-text chunks, indexed by normalized UTF-16 length. */
    private static final class PhysicalTextBuffer {
        private static final int CHUNK_SIZE = 4096;
        private static final long HASH_BASE = 1_000_000_007L;
        private static long priorityState = 0x9E3779B97F4A7C15L;

        private Node root;

        private PhysicalTextBuffer(String raw) {
            for (int start = 0; start < raw.length();) {
                int remaining = raw.length() - start;
                int chunks = (remaining + CHUNK_SIZE - 1) / CHUNK_SIZE;
                int end = start + (remaining + chunks - 1) / chunks;
                if (end < raw.length() && raw.charAt(end - 1) == '\r' && raw.charAt(end) == '\n') {
                    end += end - start < CHUNK_SIZE ? 1 : -1;
                }
                root = merge(root, new Node(raw.substring(start, end)));
                start = end;
            }
        }

        int normalizedLength() { return normalizedLength(root); }

        long normalizedHash() { return normalizedHash(root); }

        int segmentCount() { return segmentCount(root); }

        int maxSegmentLength() { return maxSegmentLength(root); }

        int treeHeight() { return treeHeight(root); }

        int physicalLength() { return physicalLength(root); }

        int tinySegmentCount(int limit) { return tinySegmentCount(root, limit); }

        void replace(int position, int removedLength, String insertedRaw) {
            if (position < 0 || removedLength < 0 || position > normalizedLength()
                    || position + removedLength > normalizedLength()) {
                throw new IllegalArgumentException("invalid plain-text change range");
            }
            Split leading = split(root, position);
            Split trailing = split(leading.right, removedLength);
            Node inserted = new PhysicalTextBuffer(insertedRaw).root;
            root = joinBoundaries(joinBoundaries(leading.left, inserted), trailing.right);
        }

        String physicalText() {
            StringBuilder text = new StringBuilder(physicalLength(root));
            appendPhysical(root, text);
            return text.toString();
        }

        String normalizedText() {
            StringBuilder text = new StringBuilder(normalizedLength());
            appendNormalized(root, text);
            return text.toString();
        }

        boolean matchesNormalized(String expected) {
            if (expected.length() != normalizedLength()) return false;
            NormalizedIterator iterator = new NormalizedIterator(root);
            for (int i = 0; i < expected.length(); i++) {
                if (!iterator.hasNext() || iterator.next() != expected.charAt(i)) return false;
            }
            return !iterator.hasNext();
        }

        private static Split split(Node node, int position) {
            if (node == null) return new Split(null, null);
            int leftLength = normalizedLength(node.left);
            if (position < leftLength) {
                Split split = split(node.left, position);
                node.left = split.right;
                refresh(node);
                return new Split(split.left, node);
            }
            int end = leftLength + node.logicalLength;
            if (position > end) {
                Split split = split(node.right, position - end);
                node.right = split.left;
                refresh(node);
                return new Split(node, split.right);
            }
            int within = position - leftLength;
            if (within == 0) {
                Node left = node.left;
                node.left = null;
                refresh(node);
                return new Split(left, node);
            }
            if (within == node.logicalLength) {
                Node right = node.right;
                node.right = null;
                refresh(node);
                return new Split(node, right);
            }
            int rawOffset = rawOffsetForNormalizedOffset(node.raw, within);
            Node leftPiece = new Node(node.raw.substring(0, rawOffset));
            Node rightPiece = new Node(node.raw.substring(rawOffset));
            return new Split(merge(node.left, leftPiece), merge(rightPiece, node.right));
        }

        private static Node merge(Node left, Node right) {
            if (left == null) return right;
            if (right == null) return left;
            if (left.priority >= right.priority) {
                left.right = merge(left.right, right);
                refresh(left);
                return left;
            }
            right.left = merge(left, right.left);
            refresh(right);
            return right;
        }

        /**
         * Rechunks a bounded window around one edit. A CR ending the left fragment and
         * an LF starting the right fragment were distinct logical separators before the join, so
         * emit {@code CR + CRLF} instead of accidentally serializing them as one CRLF token.
         */
        private static Node joinBoundaries(Node left, Node right) {
            if (left == null) return right;
            if (right == null) return left;
            Boundary before = extractTail(left);
            Boundary after = extractHead(right);
            String boundaryEscape = before.raw.endsWith("\r") && after.raw.startsWith("\n") ? "\r" : "";
            Node joined = new PhysicalTextBuffer(before.raw + boundaryEscape + after.raw).root;
            return merge(merge(before.rest, joined), after.rest);
        }

        private static Boundary extractTail(Node node) {
            Node rest = node;
            ArrayDeque<String> raw = new ArrayDeque<>();
            for (int count = 0; rest != null && count < 2; count++) {
                ExtractLast extracted = popLast(rest);
                rest = extracted.rest;
                raw.addFirst(extracted.raw);
            }
            return new Boundary(rest, String.join("", raw));
        }

        private static Boundary extractHead(Node node) {
            Node rest = node;
            StringBuilder raw = new StringBuilder(CHUNK_SIZE * 2);
            for (int count = 0; rest != null && count < 2; count++) {
                ExtractFirst extracted = popFirst(rest);
                rest = extracted.rest;
                raw.append(extracted.raw);
            }
            return new Boundary(rest, raw.toString());
        }

        private static ExtractLast popLast(Node node) {
            if (node.right == null) {
                Node rest = node.left;
                node.left = null;
                refresh(node);
                return new ExtractLast(rest, node.raw);
            }
            ExtractLast extracted = popLast(node.right);
            node.right = extracted.rest;
            refresh(node);
            return new ExtractLast(node, extracted.raw);
        }

        private static ExtractFirst popFirst(Node node) {
            if (node.left == null) {
                Node rest = node.right;
                node.right = null;
                refresh(node);
                return new ExtractFirst(rest, node.raw);
            }
            ExtractFirst extracted = popFirst(node.left);
            node.left = extracted.rest;
            refresh(node);
            return new ExtractFirst(node, extracted.raw);
        }

        private static void appendPhysical(Node node, StringBuilder destination) {
            if (node == null) return;
            appendPhysical(node.left, destination);
            destination.append(node.raw);
            appendPhysical(node.right, destination);
        }

        private static void appendNormalized(Node node, StringBuilder destination) {
            if (node == null) return;
            appendNormalized(node.left, destination);
            SqlScriptDocument.appendNormalized(destination, node.raw);
            appendNormalized(node.right, destination);
        }

        private static int rawOffsetForNormalizedOffset(String raw, int logicalOffset) {
            int logical = 0;
            for (int rawOffset = 0; rawOffset < raw.length();) {
                if (logical == logicalOffset) return rawOffset;
                char character = raw.charAt(rawOffset++);
                if (character == '\r' && rawOffset < raw.length() && raw.charAt(rawOffset) == '\n') rawOffset++;
                logical++;
            }
            return raw.length();
        }

        private static int normalizedLength(Node node) { return node == null ? 0 : node.totalLogicalLength; }

        private static int physicalLength(Node node) { return node == null ? 0 : node.totalPhysicalLength; }

        private static int segmentCount(Node node) { return node == null ? 0 : node.totalSegments; }

        private static int maxSegmentLength(Node node) {
            if (node == null) return 0;
            return Math.max(node.raw.length(), Math.max(maxSegmentLength(node.left), maxSegmentLength(node.right)));
        }

        private static int treeHeight(Node node) {
            return node == null ? 0 : 1 + Math.max(treeHeight(node.left), treeHeight(node.right));
        }

        private static int tinySegmentCount(Node node, int limit) {
            if (node == null) return 0;
            return tinySegmentCount(node.left, limit) + tinySegmentCount(node.right, limit)
                    + (node.raw.length() < limit ? 1 : 0);
        }

        private static long normalizedHash(Node node) { return node == null ? 0 : node.totalHash; }

        private static void refresh(Node node) {
            int rightLength = normalizedLength(node.right);
            node.totalLogicalLength = normalizedLength(node.left) + node.logicalLength + rightLength;
            node.totalPhysicalLength = physicalLength(node.left) + node.raw.length() + physicalLength(node.right);
            node.totalSegments = segmentCount(node.left) + 1 + segmentCount(node.right);
            node.totalHash = normalizedHash(node.left) * power(HASH_BASE, node.logicalLength + rightLength)
                    + node.logicalHash * power(HASH_BASE, rightLength) + normalizedHash(node.right);
        }

        private static long power(long base, int exponent) {
            long result = 1;
            long factor = base;
            for (int remaining = exponent; remaining != 0; remaining >>>= 1) {
                if ((remaining & 1) != 0) result *= factor;
                factor *= factor;
            }
            return result;
        }

        private static int nextPriority() {
            priorityState ^= priorityState << 13;
            priorityState ^= priorityState >>> 7;
            priorityState ^= priorityState << 17;
            return (int) priorityState;
        }

        private static final class Node {
            private final String raw;
            private final int logicalLength;
            private final long logicalHash;
            private final int priority = nextPriority();
            private Node left;
            private Node right;
            private int totalLogicalLength;
            private int totalPhysicalLength;
            private int totalSegments;
            private long totalHash;

            private Node(String raw) {
                this.raw = raw;
                logicalLength = SqlScriptDocument.normalizedLength(raw);
                logicalHash = hashNormalized(raw);
                refresh(this);
            }
        }

        private static long hashNormalized(String raw) {
            long result = 0;
            for (int i = 0; i < raw.length(); i++) {
                char character = raw.charAt(i);
                if (character == '\r') {
                    character = '\n';
                    if (i + 1 < raw.length() && raw.charAt(i + 1) == '\n') i++;
                }
                result = result * HASH_BASE + character;
            }
            return result;
        }

        private record Split(Node left, Node right) { }

        private record ExtractLast(Node rest, String raw) { }

        private record ExtractFirst(Node rest, String raw) { }

        private record Boundary(Node rest, String raw) { }

        private static final class NormalizedIterator {
            private final ArrayDeque<Node> nodes = new ArrayDeque<>();
            private String raw = "";
            private int rawOffset;

            private NormalizedIterator(Node root) { pushLeft(root); }

            boolean hasNext() {
                while (rawOffset >= raw.length() && !nodes.isEmpty()) {
                    Node next = nodes.pop();
                    raw = next.raw;
                    rawOffset = 0;
                    pushLeft(next.right);
                }
                return rawOffset < raw.length();
            }

            char next() {
                char character = raw.charAt(rawOffset++);
                if (character == '\r') {
                    if (rawOffset < raw.length() && raw.charAt(rawOffset) == '\n') rawOffset++;
                    return '\n';
                }
                return character;
            }

            private void pushLeft(Node node) {
                while (node != null) {
                    nodes.push(node);
                    node = node.left;
                }
            }
        }
    }
}
