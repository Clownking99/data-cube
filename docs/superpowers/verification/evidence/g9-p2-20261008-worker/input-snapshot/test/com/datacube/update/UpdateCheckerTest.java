package com.datacube.update;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.datacube.update.UpdateTestSupport.*;

class UpdateCheckerTest {
    static Map<String,Object> releaseObject() {
        String base=UpdateManifest.baseUrl(VERSION);
        List<Object> assets=new ArrayList<>();
        for(String name:List.of(UpdateManifest.assetName(VERSION,InstallMode.INSTALLED),
                UpdateManifest.assetName(VERSION,InstallMode.PORTABLE),"datacube-update.manifest","datacube-update.manifest.sig")) {
            assets.add(Map.of("name",name,"browser_download_url",base+name));
        }
        return new HashMap<>(Map.of("tag_name","v"+VERSION,"assets",assets,"html_url","https://evil.invalid/","body","notes"));
    }
    @Test void exactReleaseAssetsAndOfficialPageArePinnedWithoutTrustingHtmlUrl() {
        var release=UpdateChecker.map(releaseObject());
        assertTrue(release.verificationAvailable());
        assertEquals("https://github.com/Clownking99/data-cube/releases/tag/v3.0.1",release.htmlUrl());
        assertEquals(UpdateManifest.baseUrl(VERSION)+UpdateManifest.assetName(VERSION,InstallMode.PORTABLE),release.portableZipUrl());
    }
    @Test void legacyUnsignedReleaseRemainsVisibleButCannotClaimVerification() {
        var object=releaseObject();object.put("assets",List.of());
        var release=UpdateChecker.map(object);
        assertFalse(release.verificationAvailable());assertNull(release.setupExeUrl());
    }
    @Test void invalidTagsDraftsPrereleasesDuplicateNamesAndForeignAssetUrlsReject() {
        for(String tag:List.of("v3.0.1/../../other","v3.0","V3.0.1","v03.0.1","v3.0.1-beta")) {
            var object=releaseObject();object.put("tag_name",tag);
            assertThrows(IllegalStateException.class,()->UpdateChecker.map(object));
        }
        for(String flag:List.of("draft","prerelease")) {
            var object=releaseObject();object.put(flag,true);assertThrows(IllegalStateException.class,()->UpdateChecker.map(object));
        }
        String name=UpdateManifest.assetName(VERSION,InstallMode.INSTALLED);
        for(var assets:List.of(List.of(Map.of("name",name,"browser_download_url","https://evil.invalid/x")),
                List.of(Map.of("name",name,"browser_download_url",UpdateManifest.baseUrl(VERSION)+name),
                        Map.of("name",name,"browser_download_url",UpdateManifest.baseUrl(VERSION)+name)))) {
            var object=releaseObject();object.put("assets",assets);assertThrows(IllegalStateException.class,()->UpdateChecker.map(object));
        }
    }
    @Test void jsonRejectsDuplicateKeysTrailingInputAndDeepPayloads() {
        for(String input:List.of("{\"tag_name\":\"a\",\"tag_name\":\"b\"}","{}garbage","[true] false",
                "[".repeat(70)+"0"+"]".repeat(70),"{\"body\":\"raw\nnewline\"}")) {
            assertThrows(IllegalArgumentException.class,()->MiniJson.parse(input));
        }
    }
    @Test void installerLocationMustMatchExactlyAndIncompleteQueriesStayUnknown() {
        String target="C:\\apps\\DataCube";
        assertEquals(InstallMode.INSTALLED,InstallMode.registeredMode(target,key->"InstallLocation    REG_SZ    c:\\apps\\datacube\\"));
        for(String location:List.of("C:\\apps","C:\\apps\\DataCube\\nested","C:\\other\\DataCube")) {
            assertEquals(InstallMode.PORTABLE,InstallMode.registeredMode(target,key->
                    "DisplayName    REG_SZ    DataCube\nInstallLocation    REG_SZ    "+location));
        }
        assertEquals(InstallMode.UNKNOWN,InstallMode.registeredMode(target,key->null));
        assertEquals(InstallMode.UNKNOWN,InstallMode.registeredMode(target,key->key.startsWith("HKCU")?"":null));
    }
}
