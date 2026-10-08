package com.datacube.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static com.datacube.update.UpdateTestSupport.*;

class UpdateManifestTest {
    @Test void authenticatedManifestBindsBothExactArtifactsAndSupportsReviewedKeyRotation() throws Exception {
        var f=new UpdateTestSupport(); byte[] text=f.responses.get("datacube-update.manifest");
        var result=f.verifier.verify(release(),"3.0.0",text,f.sign(text));
        assertEquals(VERSION,result.version);
        assertEquals(hash(SETUP),result.asset(InstallMode.INSTALLED).sha256());
        assertEquals(f.archive.length,result.asset(InstallMode.PORTABLE).size());
        var rotated=new UpdateManifest(Map.of("test-key",f.keys.getPublic(),"next-key",
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic()),CLOCK);
        assertEquals(VERSION,rotated.verify(release(),"3.0.0",text,f.sign(text)).version);
    }
    @Test void missingRevokedUnknownAndWrongKeysNeverAuthorizeAnUpdate() throws Exception {
        var f=new UpdateTestSupport(); byte[] text=f.responses.get("datacube-update.manifest");
        for(var keys:java.util.List.of(Map.<String,java.security.PublicKey>of(),
                Map.of("next-key",f.keys.getPublic()),
                Map.of("test-key",KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic()))) {
            var verifier=new UpdateManifest(keys,CLOCK);
            assertThrows(Exception.class,()->verifier.verify(release(),"3.0.0",text,f.sign(text)));
        }
        assertFalse(UpdateManifest.bundled().configured(),"no invented production trust root");
    }
    @Test void changedBytesTruncatedSignatureAndOversizedManifestReject() throws Exception {
        var f=new UpdateTestSupport(); byte[] text=f.responses.get("datacube-update.manifest"); byte[] signature=f.sign(text);
        byte[] changed=text.clone(); changed[changed.length-3]^=1;
        assertThrows(Exception.class,()->f.verifier.verify(release(),"3.0.0",changed,signature));
        assertThrows(Exception.class,()->f.verifier.verify(release(),"3.0.0",text,new byte[63]));
        assertThrows(Exception.class,()->f.verifier.verify(release(),"3.0.0",new byte[16385],signature));
    }
    @ParameterizedTest @ValueSource(strings={"version","platform","expired","future","lifetime","name","size-zero","size-large","digest","extra","crlf"})
    void evenCorrectlySignedInvalidClaimsAreRejected(String mutation) throws Exception {
        var f=new UpdateTestSupport();
        String original=new String(f.responses.get("datacube-update.manifest"),StandardCharsets.UTF_8);
        long now=CLOCK.instant().getEpochSecond();
        String changed=switch(mutation) {
            case "version"->original.replace("version=3.0.1","version=3.0.2");
            case "platform"->original.replace("windows-x64","linux-x64");
            case "expired"->original.replace("expires="+(now+86400),"expires="+now);
            case "future"->original.replace("issued="+(now-60),"issued="+(now+301));
            case "lifetime"->original.replace("expires="+(now+86400),"expires="+(now+91L*86400));
            case "name"->original.replace("win64-setup.exe","win32-setup.exe");
            case "size-zero"->original.replace("\t"+SETUP.length+"\t","\t0\t");
            case "size-large"->original.replace("\t"+SETUP.length+"\t","\t1073741825\t");
            case "digest"->original.replace(hash(SETUP),"0".repeat(63));
            case "extra"->original+"extra\n";
            default->original.replace("\n","\r\n");
        };
        byte[] bytes=changed.getBytes(StandardCharsets.UTF_8);
        assertThrows(Exception.class,()->f.verifier.verify(release(),"3.0.0",bytes,f.sign(bytes)));
    }
    @ParameterizedTest @ValueSource(strings={"3.0.1","3.0.2","4.0.0","0.0.0-dev","03.0.0"})
    void downgradeReplayAndInvalidCurrentVersionAreRejected(String current) throws Exception {
        var f=new UpdateTestSupport(); byte[] text=f.responses.get("datacube-update.manifest");
        assertThrows(Exception.class,()->f.verifier.verify(release(),current,text,f.sign(text)));
    }
}
