package com.datacube.config;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Windows current-user credential protection through DPAPI and the JDK 25 FFM API. */
final class DpapiCredentialProtector implements CredentialProtector {

    private static final int CRYPTPROTECT_UI_FORBIDDEN = 0x1;
    private static final long POINTER_OFFSET = alignedOffset(
            ValueLayout.JAVA_INT.byteSize(), ValueLayout.ADDRESS.byteAlignment());
    private static final MemoryLayout DATA_BLOB = dataBlobLayout();

    private final MethodHandle cryptProtectData;
    private final MethodHandle cryptUnprotectData;
    private final MethodHandle localFree;
    private final MethodHandle getLastError;

    DpapiCredentialProtector() {
        try {
            var linker = java.lang.foreign.Linker.nativeLinker();
            SymbolLookup crypt32 = SymbolLookup.libraryLookup("crypt32.dll", Arena.global());
            SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());
            FunctionDescriptor cryptDescriptor = FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS);
            cryptProtectData = linker.downcallHandle(
                    crypt32.find("CryptProtectData").orElseThrow(), cryptDescriptor);
            cryptUnprotectData = linker.downcallHandle(
                    crypt32.find("CryptUnprotectData").orElseThrow(), cryptDescriptor);
            localFree = linker.downcallHandle(
                    kernel32.find("LocalFree").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            getLastError = linker.downcallHandle(
                    kernel32.find("GetLastError").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT));
        } catch (Throwable error) {
            throw new IllegalStateException("Windows DPAPI 初始化失败", error);
        }
    }

    @Override
    public String scheme() {
        return "dpapi";
    }

    @Override
    public String protect(String plain) {
        byte[] input = plain.getBytes(StandardCharsets.UTF_8);
        return Base64.getEncoder().encodeToString(call(cryptProtectData, input, "保护"));
    }

    @Override
    public String unprotect(String payload) {
        final byte[] input;
        try {
            input = Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException damaged) {
            throw new IllegalStateException("Windows DPAPI 凭据格式损坏", damaged);
        }
        return new String(call(cryptUnprotectData, input, "解密"), StandardCharsets.UTF_8);
    }

    private byte[] call(MethodHandle operation, byte[] input, String action) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment inputBytes = arena.allocate(input.length);
            inputBytes.copyFrom(MemorySegment.ofArray(input));
            MemorySegment inputBlob = arena.allocate(DATA_BLOB);
            writeBlob(inputBlob, input.length, inputBytes);
            MemorySegment outputBlob = arena.allocate(DATA_BLOB);
            writeBlob(outputBlob, 0, MemorySegment.NULL);

            int success = (int) operation.invoke(inputBlob, MemorySegment.NULL, MemorySegment.NULL,
                    MemorySegment.NULL, MemorySegment.NULL, CRYPTPROTECT_UI_FORBIDDEN, outputBlob);
            if (success == 0) {
                int errorCode = (int) getLastError.invoke();
                throw new IllegalStateException("Windows DPAPI " + action + "失败，错误码 "
                        + Integer.toUnsignedString(errorCode));
            }
            return copyAndFree(outputBlob);
        } catch (IllegalStateException error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException("Windows DPAPI " + action + "调用失败", error);
        }
    }

    private byte[] copyAndFree(MemorySegment blob) throws Throwable {
        int length = blob.get(ValueLayout.JAVA_INT, 0);
        MemorySegment pointer = blob.get(ValueLayout.ADDRESS, POINTER_OFFSET);
        try {
            if (length < 0 || pointer.address() == 0) {
                throw new IllegalStateException("Windows DPAPI 返回了无效结果");
            }
            return pointer.reinterpret(length).toArray(ValueLayout.JAVA_BYTE);
        } finally {
            if (pointer.address() != 0) localFree.invoke(pointer);
        }
    }

    private static void writeBlob(MemorySegment blob, int length, MemorySegment pointer) {
        blob.set(ValueLayout.JAVA_INT, 0, length);
        blob.set(ValueLayout.ADDRESS, POINTER_OFFSET, pointer);
    }

    private static MemoryLayout dataBlobLayout() {
        long padding = POINTER_OFFSET - ValueLayout.JAVA_INT.byteSize();
        if (padding == 0) {
            return MemoryLayout.structLayout(
                    ValueLayout.JAVA_INT.withName("cbData"),
                    ValueLayout.ADDRESS.withName("pbData"));
        }
        return MemoryLayout.structLayout(
                ValueLayout.JAVA_INT.withName("cbData"),
                MemoryLayout.paddingLayout(padding),
                ValueLayout.ADDRESS.withName("pbData"));
    }

    private static long alignedOffset(long offset, long alignment) {
        return (offset + alignment - 1) & -alignment;
    }
}
