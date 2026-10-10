package com.datacube.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.datacube.update.UpdateTestSupport.*;

/** Executes the shipped PowerShell code only on synthetic images; process launch is injected. */
@EnabledOnOs(OS.WINDOWS)
class PortableUpdateHelperTest {
    @TempDir Path directory;
    @ParameterizedTest
    @ValueSource(strings={"success","swap-failure","new-start-failure","new-exited","unconfirmed","old-still-running","asset-mutated","rollback-failure","startup-check-failure"})
    void helperPreservesRecoverableImagesAcrossEveryHandoffPhase(String scenario) throws Exception {
        var f=new UpdateTestSupport();var applier=f.applier();
        Path app=image(directory.resolve("app 用户 [x] &%!'"),(byte)42);
        try(var control=control()) {
            var ready=applier.prepare(release(),"3.0.0",InstallMode.PORTABLE,app,control,null);
            if(scenario.equals("asset-mutated")) Files.write(ready.asset,new byte[]{0});
            String harness="""
                    $ErrorActionPreference='Stop'
                    . %s
                    $scenario=%s
                    $fixturePlan=Get-Content -LiteralPath %s -Raw -Encoding UTF8 | ConvertFrom-Json
                    $move={
                        param($from,$to)
                        if($scenario -eq 'swap-failure' -and $from -eq (Join-Path $fixturePlan.workspace 'new\\DataCube')) { throw 'synthetic swap failure' }
                        if($scenario -eq 'rollback-failure' -and $from -eq (Join-Path $fixturePlan.workspace 'previous')) { throw 'synthetic rollback failure' }
                        [IO.Directory]::Move($from,$to)
                    }.GetNewClosure()
                    $launch={
                        param($exe,$arguments)
                        if($arguments.Count -gt 0) {
                            if($scenario -in @('new-start-failure','rollback-failure')) { throw 'synthetic start failure' }
                            if($scenario -eq 'success') {
                                @{token=$fixturePlan.token;version=$fixturePlan.version;appDir=$fixturePlan.appDir;pid=1001} |
                                    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $fixturePlan.workspace 'startup-ack.json') -Encoding UTF8
                            }
                            return [pscustomobject]@{Id=1001;HasExited=($scenario -eq 'new-exited')}
                        }
                        return [pscustomobject]@{Id=1002;HasExited=$false}
                    }.GetNewClosure()
                    $wait={param($p) return $scenario -ne 'old-still-running'}.GetNewClosure()
                    $startup={param($p,$process) if($scenario -eq 'startup-check-failure'){throw 'synthetic startup check failure'}}.GetNewClosure()
                    Invoke-DataCubeUpdate -PlanFile %s -Move $move -Launch $launch -WaitOriginal $wait -WaitStartup $startup
                    """.formatted(ps(ready.workspace.resolve("update-helper.ps1").toString()),ps(scenario),ps(ready.plan.toString()),ps(ready.plan.toString()));
            String output=run(harness,0);
            var state=(Map<?,?>)MiniJson.parse(Files.readString(ready.workspace.resolve("state.json")).replace("\uFEFF",""));
            String expected=switch(scenario){
                case "success"->"START_CONFIRMED";
                case "swap-failure","new-start-failure","new-exited"->"ROLLED_BACK";
                case "unconfirmed"->"START_UNCONFIRMED_BACKUP_RETAINED";
                case "old-still-running"->"CANCELLED_BEFORE_SWAP";
                case "asset-mutated"->"FAILED_BEFORE_SWAP";
                case "startup-check-failure"->"RECOVERY_REQUIRED_PROCESS_RUNNING";
                default->"RECOVERY_REQUIRED_BACKUP_RETAINED";
            };
            assertEquals(expected,state.get("state"),output);
            if(Set.of("success","unconfirmed","startup-check-failure").contains(scenario)) {
                assertArrayEquals(IMAGE.get("DataCube/DataCube.exe"),Files.readAllBytes(app.resolve("DataCube.exe")));
                assertArrayEquals(new byte[]{42},Files.readAllBytes(ready.workspace.resolve("previous/DataCube.exe")));
            } else if(scenario.equals("rollback-failure")) {
                assertArrayEquals(new byte[]{42},Files.readAllBytes(ready.workspace.resolve("previous/DataCube.exe")));
                assertTrue(Files.exists(ready.workspace.resolve("failed-new/DataCube.exe")));
            } else {
                assertArrayEquals(new byte[]{42},Files.readAllBytes(app.resolve("DataCube.exe")));
            }
            assertTrue(Files.exists(ready.asset),"evidence is never deleted before startup confirmation");
        }
    }
    @Test void installedHandoffCannotClaimInstallerCompletionOrReplaceTheRunningImage() throws Exception {
        var f=new UpdateTestSupport();Path app=image(directory.resolve("installed"),(byte)42);
        try(var control=control()) {
            var ready=f.applier().prepare(release(),"3.0.0",InstallMode.INSTALLED,app,control,null);
            run("$ErrorActionPreference='Stop'\n. "+ps(ready.workspace.resolve("update-helper.ps1").toString())
                    +"\n$expected="+ps(ready.asset.toString())
                    +"\n$launch={param($exe,$arguments) if($exe -cne $expected -or $arguments.Count -ne 0){throw 'WRONG_LAUNCH'}; return [pscustomobject]@{Id=1001;HasExited=$false}}.GetNewClosure()"
                    +"\nInvoke-DataCubeUpdate -PlanFile "+ps(ready.plan.toString())+" -WaitOriginal {param($p) $true} -Launch $launch",0);
            var state=(Map<?,?>)MiniJson.parse(Files.readString(ready.workspace.resolve("state.json")).replace("\uFEFF",""));
            assertEquals("INSTALLER_STARTED_UNCONFIRMED",state.get("state"));
            assertArrayEquals(new byte[]{42},Files.readAllBytes(app.resolve("DataCube.exe")));
            assertFalse(Files.exists(ready.workspace.resolve("previous")));
        }
    }
    @Test void malformedOwnershipCannotMoveTheOriginalImageOrAnUnrelatedDirectory() throws Exception {
        var f=new UpdateTestSupport();Path app=image(directory.resolve("app"),(byte)42);
        try(var control=control()) {
            var ready=f.applier().prepare(release(),"3.0.0",InstallMode.PORTABLE,app,control,null);
            Path unrelated=image(directory.resolve("unrelated"),(byte)43);
            String plan=Files.readString(ready.plan).replace(UpdateStartup.quote(ready.workspace.toString()),UpdateStartup.quote(unrelated.toString()));
            Files.writeString(ready.plan,plan);
            run("$ErrorActionPreference='Stop'\n. "+ps(ready.workspace.resolve("update-helper.ps1").toString())
                    +"\nInvoke-DataCubeUpdate -PlanFile "+ps(ready.plan.toString()),1);
            assertArrayEquals(new byte[]{42},Files.readAllBytes(app.resolve("DataCube.exe")));
            assertArrayEquals(new byte[]{43},Files.readAllBytes(unrelated.resolve("DataCube.exe")));
        }
    }
    private String run(String script,int expectedExit) throws Exception {
        var receipt=new UpdateHelperProcess().run(directory,script,expectedExit);
        return new String(Base64.getDecoder().decode(receipt.stdoutBase64()),StandardCharsets.UTF_8)
                + new String(Base64.getDecoder().decode(receipt.stderrBase64()),StandardCharsets.UTF_8);
    }

    static String ps(String value){return "'"+value.replace("'","''")+"'";}
}
