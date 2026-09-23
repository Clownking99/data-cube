package com.datacube.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.datacube.update.UpdateTestSupport.*;

class PortableArchiveTest {
    @TempDir Path directory;
    @Test void completeImageAtExactEntryAndByteLimitsExtractsOnlyUnderOwnedRoot() throws Exception {
        Path zip=Files.write(directory.resolve("image.zip"),zip(IMAGE));
        try(var control=control()) {
            Path image=PortableArchive.extract(zip,directory.resolve("new"),control,4,12);
            assertArrayEquals(IMAGE.get("DataCube/DataCube.exe"),Files.readAllBytes(image.resolve("DataCube.exe")));
            assertEquals(directory.resolve("new/DataCube"),image);
        }
    }
    @ParameterizedTest @ValueSource(strings={"../outside","/DataCube/a","DataCube/../outside","DataCube/a\\b","DataCube/a:b","DataCube/NUL.txt","DataCube/a.","DataCube/a ","DataCube//x","Other/a"})
    void unsafeWindowsPathsNeverWriteOutsideOwnedStaging(String name) throws Exception {
        Path zip=Files.write(directory.resolve("image.zip"),zip(Map.of(name,new byte[]{1})));
        try(var control=control()) {assertThrows(Exception.class,()->PortableArchive.extract(zip,directory.resolve("new"),control));}
        assertFalse(Files.exists(directory.resolve("outside")));
    }
    @Test void duplicateCaseAliasesIncompleteImagesAndExpansionLimitsReject() throws Exception {
        var duplicate=new LinkedHashMap<String,byte[]>();duplicate.put("DataCube/a",new byte[]{1});duplicate.put("DataCube/A",new byte[]{2});
        List<Map<String,byte[]>> cases=List.of(duplicate,Map.of("DataCube/DataCube.exe",new byte[]{1}),IMAGE,IMAGE);
        for(int i=0;i<cases.size();i++) {
            Path zip=Files.write(directory.resolve("image"+i+".zip"),zip(cases.get(i)));
            final int index=i;
            try(var control=control()) {
                assertThrows(Exception.class,()->PortableArchive.extract(zip,directory.resolve("new"+index),control,index==2?3:4,index==3?11:100));
            }
        }
    }
    @Test void existingDestinationAndCancelledExtractionDoNotReplaceAnything() throws Exception {
        Path zip=Files.write(directory.resolve("image.zip"),zip(IMAGE));
        Path root=Files.createDirectory(directory.resolve("existing"));Files.writeString(root.resolve("keep"),"old");
        try(var control=control()) {
            assertThrows(FileAlreadyExistsException.class,()->PortableArchive.extract(zip,root,control));
            assertEquals("old",Files.readString(root.resolve("keep")));
            control.cancel();
            assertThrows(java.util.concurrent.CancellationException.class,()->PortableArchive.extract(zip,directory.resolve("cancelled"),control));
        }
    }
    @Test void directoryEntryCannotHideUncountedExpandedData() throws Exception {
        var entries=new LinkedHashMap<String,byte[]>(IMAGE);
        entries.put("DataCube/hidden/",new byte[1024]);
        Path archive=Files.write(directory.resolve("directory-payload.zip"),zip(entries));
        try(var control=control()) {
            var failure=assertThrows(java.io.IOException.class,()->PortableArchive.extract(archive,directory.resolve("new"),control,10,12));
            assertTrue(failure.getMessage().contains("目录条目包含数据"));
        }
    }
}
